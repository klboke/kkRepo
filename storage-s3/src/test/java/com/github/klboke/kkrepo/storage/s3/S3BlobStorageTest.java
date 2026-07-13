package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

class S3BlobStorageTest {
  @TempDir
  Path tempDir;

  @Test
  void putUploadsReplayableAndOneShotStreamsWithSanitizedMetadata() throws Exception {
    S3Client client = mock(S3Client.class);
    List<byte[]> uploaded = new ArrayList<>();
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
      RequestBody body = invocation.getArgument(1);
      try (InputStream in = body.contentStreamProvider().newStream()) {
        uploaded.add(in.readAllBytes());
      }
      return PutObjectResponse.builder().build();
    });
    S3BlobStorage storage = new S3BlobStorage(client, config(64 * 1024 * 1024L));
    try {
      byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      BlobReference firstRef = storage.put(
          "maven\nhosted", "com/acme/a.jar", new ByteArrayInputStream(first), first.length, "a".repeat(64));
      BlobReference secondRef = storage.put(
          "raw", "files/data.bin", oneShot(second), second.length, "b".repeat(64));

      assertEquals(List.of(first.length, second.length), uploaded.stream().map(bytes -> bytes.length).toList());
      assertArrayEquals(first, uploaded.get(0));
      assertArrayEquals(second, uploaded.get(1));
      assertTrue(firstRef.objectKey().contains("a".repeat(64)));
      assertTrue(secondRef.objectKey().contains("b".repeat(64)));

      ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
      verify(client, atLeastOnce()).putObject(requests.capture(), any(RequestBody.class));
      assertEquals("maven_hosted", requests.getAllValues().get(0).metadata().get("repository"));
      assertEquals("com/acme/a.jar", requests.getAllValues().get(0).metadata().get("logical-path"));
    } finally {
      storage.close();
    }
  }

  @Test
  void getRangeStatAndDeletePreserveBlobSemantics() throws Exception {
    S3Client client = mock(S3Client.class);
    AtomicReference<GetObjectRequest> lastGet = new AtomicReference<>();
    when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
      GetObjectRequest request = invocation.getArgument(0);
      lastGet.set(request);
      byte[] bytes = request.range() == null
          ? "0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
          : "3456".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      return new ResponseInputStream<>(
          GetObjectResponse.builder().contentLength((long) bytes.length).build(),
          new ByteArrayInputStream(bytes));
    });
    Instant modified = Instant.parse("2026-01-02T03:04:05Z");
    when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
        .contentLength(10L)
        .eTag("\"etag\"")
        .contentType("application/octet-stream")
        .lastModified(modified)
        .metadata(Map.of("sha256", "f".repeat(64)))
        .build());
    S3BlobStorage storage = new S3BlobStorage(client, config(64 * 1024 * 1024L));
    BlobReference reference = new BlobReference("bucket", "prefix/object", "old", 1);
    try {
      try (InputStream full = storage.get(reference).orElseThrow()) {
        assertEquals("0123456789", new String(full.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII));
        BlobRangeReader rangeReader = assertInstanceOf(BlobRangeReader.class, full);
        try (InputStream range = rangeReader.openRange(3, 4)) {
          assertEquals("3456", new String(range.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII));
        }
      }
      assertEquals("bytes=3-6", lastGet.get().range());

      BlobObjectMetadata metadata = storage.stat(reference).orElseThrow();
      assertEquals(10, metadata.reference().size());
      assertEquals("f".repeat(64), metadata.reference().sha256());
      assertEquals("\"etag\"", metadata.eTag());
      assertEquals(modified, metadata.lastModified());
      assertTrue(storage.exists(reference));

      storage.delete(reference);
      ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
      verify(client).deleteObject(deleted.capture());
      assertEquals("prefix/object", deleted.getValue().key());
    } finally {
      storage.close();
    }
  }

  @Test
  void missingObjectsAndInvalidRangesAreHandledWithoutMaskingOtherErrors() {
    S3Client client = mock(S3Client.class);
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().message("missing").build());
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
    S3BlobStorage storage = new S3BlobStorage(client, config(64 * 1024 * 1024L));
    BlobReference reference = new BlobReference("bucket", "missing", "a", 1);
    try {
      assertTrue(storage.get(reference).isEmpty());
      assertTrue(storage.getRange(reference, 0, 1).isEmpty());
      assertTrue(storage.stat(reference).isEmpty());
      assertFalse(storage.exists(reference));
      assertEquals(0, storage.getRange(reference, 0, 0).orElseThrow().readAllBytes().length);
      assertThrows(IllegalArgumentException.class, () -> storage.getRange(reference, -1, 1));
      assertThrows(IllegalArgumentException.class, () -> storage.getRange(reference, 0, -1));
    } catch (IOException e) {
      throw new AssertionError(e);
    } finally {
      storage.close();
    }
  }

  @Test
  void multipartFileUploadCompletesSortedParts() throws Exception {
    S3Client client = mock(S3Client.class);
    when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
    when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
      UploadPartRequest request = invocation.getArgument(0);
      return UploadPartResponse.builder().eTag("etag-" + request.partNumber()).build();
    });
    when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
        .thenReturn(CompleteMultipartUploadResponse.builder().build());
    Path file = tempDir.resolve("large.bin");
    Files.write(file, new byte[6 * 1024 * 1024]);
    S3BlobStorage storage = new S3BlobStorage(client, config(1));
    try {
      BlobReference reference = storage.putFile("maven", "large.bin", file, "c".repeat(64));

      assertEquals(Files.size(file), reference.size());
      ArgumentCaptor<CompleteMultipartUploadRequest> completed =
          ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
      verify(client).completeMultipartUpload(completed.capture());
      assertEquals(
          List.of(1, 2),
          completed.getValue().multipartUpload().parts().stream().map(part -> part.partNumber()).toList());
      verify(client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    } finally {
      storage.close();
    }
  }

  @Test
  void multipartFailureAbortsUploadAndSurfacesOriginalError() throws Exception {
    S3Client client = mock(S3Client.class);
    when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-2").build());
    S3Exception.Builder failureBuilder = S3Exception.builder();
    failureBuilder.statusCode(500);
    failureBuilder.message("upload failed");
    S3Exception failure = (S3Exception) failureBuilder.build();
    when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenThrow(failure);
    Path file = tempDir.resolve("large-failure.bin");
    Files.write(file, new byte[6 * 1024 * 1024]);
    S3BlobStorage storage = new S3BlobStorage(client, config(1));
    try {
      assertEquals(failure, assertThrows(
          S3Exception.class,
          () -> storage.putFile("maven", "large.bin", file, "d".repeat(64))));
      verify(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    } finally {
      storage.close();
    }
  }

  private static S3BlobStoreConfig config(long multipartThreshold) {
    return S3BlobStoreConfig.of(
        1,
        "test",
        "http://localhost:9000",
        "us-east-1",
        "bucket",
        "prefix",
        Map.of(
            "multipartThresholdBytes", multipartThreshold,
            "multipartPartSizeBytes", 5L * 1024 * 1024,
            "multipartConcurrency", 2));
  }

  private static InputStream oneShot(byte[] bytes) {
    return new FilterInputStream(new ByteArrayInputStream(bytes)) {
      @Override
      public boolean markSupported() {
        return false;
      }
    };
  }
}
