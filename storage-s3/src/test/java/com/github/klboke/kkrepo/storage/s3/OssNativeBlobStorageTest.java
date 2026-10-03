package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.AbortMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.CompleteMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.InitiateMultipartUpload;
import com.aliyun.sdk.service.oss2.models.InitiateMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.InitiateMultipartUploadResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.models.UploadPartRequest;
import com.aliyun.sdk.service.oss2.models.UploadPartResult;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class OssNativeBlobStorageTest {
  @TempDir
  Path tempDir;

  @Test
  void verifiedStreamingChecksOidBeforeCompleteAndPersistsHandleBeforeParts() throws Exception {
    OSSClient client = mock(OSSClient.class);
    var initiated = mock(InitiateMultipartUploadResult.class);
    var handle = mock(InitiateMultipartUpload.class);
    when(handle.uploadId()).thenReturn("durable-handle");
    when(initiated.initiateMultipartUpload()).thenReturn(handle);
    when(client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(initiated);
    var partResult = mock(UploadPartResult.class);
    when(partResult.eTag()).thenReturn("part");
    when(client.uploadPart(any(UploadPartRequest.class))).thenReturn(partResult);
    OssNativeBlobStorage storage = new OssNativeBlobStorage(client, config(1));
    byte[] bytes = new byte[com.github.klboke.kkrepo.core.VerifiedBlobReader.PART_BYTES + 1];
    String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    var target = storage.prepareVerifiedUpload("lfs", sha, bytes.length);
    var result = storage.uploadVerified(target, new ByteArrayInputStream(bytes), id -> {
      assertEquals("durable-handle", id);
      verify(client, never()).uploadPart(any(UploadPartRequest.class));
    });
    assertEquals(sha, result.sha256());
    assertFalse(target.objectKey().equals(storage.prepareVerifiedUpload("lfs", sha, bytes.length).objectKey()));
    verify(client, org.mockito.Mockito.times(2)).uploadPart(any(UploadPartRequest.class));
    verify(client).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    org.mockito.Mockito.clearInvocations(client);
    var invalid = storage.prepareVerifiedUpload("lfs", "f".repeat(64), bytes.length);
    assertThrows(com.github.klboke.kkrepo.core.BlobIntegrityException.class,
        () -> storage.uploadVerified(invalid, new ByteArrayInputStream(bytes), id -> {}));
    verify(client, never()).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    verify(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    storage.close();
  }

  @Test
  void verifiedEmptyObjectsAndCrashRecoveryUseExactAttemptKeys() {
    OSSClient client = mock(OSSClient.class);
    var storage = new OssNativeBlobStorage(client, config(1));
    var target = storage.prepareVerifiedUpload("lfs",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 0);
    assertEquals(0, storage.uploadVerified(target, InputStream.nullInputStream(), id -> org.junit.jupiter.api.Assertions.fail()).size());
    verify(client).putObject(any(PutObjectRequest.class));
    verify(client, never()).initiateMultipartUpload(any(InitiateMultipartUploadRequest.class));
    var result = mock(com.aliyun.sdk.service.oss2.models.ListMultipartUploadsResult.class);
    var lost = mock(com.aliyun.sdk.service.oss2.models.Upload.class);
    var other = mock(com.aliyun.sdk.service.oss2.models.Upload.class);
    when(lost.key()).thenReturn(target.objectKey());
    when(lost.uploadId()).thenReturn("lost-handle");
    when(other.key()).thenReturn(target.objectKey() + "-other");
    when(result.uploads()).thenReturn(List.of(lost, other));
    when(client.listMultipartUploads(any(com.aliyun.sdk.service.oss2.models.ListMultipartUploadsRequest.class))).thenReturn(result);
    storage.discardVerifiedUpload(target, null);
    var aborted = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
    verify(client).abortMultipartUpload(aborted.capture());
    assertEquals("lost-handle", aborted.getValue().uploadId());
    assertEquals(target.objectKey(), aborted.getValue().key());
    var deleted = ArgumentCaptor.forClass(com.aliyun.sdk.service.oss2.models.DeleteObjectRequest.class);
    verify(client).deleteObject(deleted.capture());
    assertEquals(target.objectKey(), deleted.getValue().key());
    var missing = mock(ServiceException.class);
    when(missing.statusCode()).thenReturn(404);
    org.mockito.Mockito.doThrow(missing).when(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    storage.discardVerifiedUpload(target, "already-completed");
    var retry = mock(ServiceException.class);
    when(retry.statusCode()).thenReturn(503);
    org.mockito.Mockito.doThrow(retry).when(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    assertThrows(ServiceException.class, () -> storage.discardVerifiedUpload(target, "retry-later"));
    storage.close();
  }

  @Test
  void putOmitsBlankMetadataAndUploadsOriginalBytes() {
    OSSClient client = mock(OSSClient.class);
    OssNativeBlobStorage storage = new OssNativeBlobStorage(client, config(64 * 1024 * 1024L));
    byte[] bytes = "oss-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    try {
      BlobReference reference = storage.put(
          "raw\nhosted", "files/data.bin", new ByteArrayInputStream(bytes), bytes.length, "");

      ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
      verify(client).putObject(request.capture());
      assertEquals("raw_hosted", request.getValue().metadata().get("repository"));
      assertFalse(request.getValue().metadata().containsKey("sha256"));
      assertArrayEquals(bytes, request.getValue().body().toBytes());
      assertEquals(bytes.length, reference.size());
    } finally {
      storage.close();
    }
  }

  @Test
  void getRangeStatDeleteAndResultCloseUseNativeSdkSemantics() throws Exception {
    OSSClient client = mock(OSSClient.class);
    AtomicReference<GetObjectRequest> lastGet = new AtomicReference<>();
    AtomicReference<GetObjectResult> lastResult = new AtomicReference<>();
    when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
      GetObjectRequest request = invocation.getArgument(0);
      lastGet.set(request);
      GetObjectResult result = mock(GetObjectResult.class);
      byte[] bytes = request.range() == null
          ? "0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
          : "234".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      when(result.body()).thenReturn(new ByteArrayInputStream(bytes));
      lastResult.set(result);
      return result;
    });
    HeadObjectResult head = mock(HeadObjectResult.class);
    when(head.metadata()).thenReturn(Map.of("sha256", "e".repeat(64)));
    when(head.contentLength()).thenReturn(10L);
    when(head.eTag()).thenReturn("etag");
    when(head.contentType()).thenReturn("application/octet-stream");
    when(head.lastModified()).thenReturn("Fri, 02 Jan 2026 03:04:05 GMT");
    when(client.headObject(any(HeadObjectRequest.class))).thenReturn(head);
    OssNativeBlobStorage storage = new OssNativeBlobStorage(client, config(64 * 1024 * 1024L));
    BlobReference reference = new BlobReference("bucket", "prefix/object", "old", 1);
    try {
      InputStream full = storage.get(reference).orElseThrow();
      GetObjectResult fullResult = lastResult.get();
      assertEquals("0123456789", new String(full.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII));
      BlobRangeReader rangeReader = assertInstanceOf(BlobRangeReader.class, full);
      full.close();
      verify(fullResult).close();

      try (InputStream range = rangeReader.openRange(2, 3)) {
        assertEquals("234", new String(range.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII));
      }
      assertEquals("bytes=2-4", lastGet.get().range());

      BlobObjectMetadata metadata = storage.stat(reference).orElseThrow();
      assertEquals(10, metadata.reference().size());
      assertEquals("e".repeat(64), metadata.reference().sha256());
      assertEquals(Instant.parse("2026-01-02T03:04:05Z"), metadata.lastModified());
      assertTrue(storage.exists(reference));

      storage.delete(reference);
      verify(client).deleteObject(any(com.aliyun.sdk.service.oss2.models.DeleteObjectRequest.class));
    } finally {
      storage.close();
    }
  }

  @Test
  void missingObjectsNestedErrorsAndInvalidRangesAreHandled() throws Exception {
    OSSClient client = mock(OSSClient.class);
    ServiceException missing = ServiceException.newBuilder().statusCode(404).build();
    when(client.getObject(any(GetObjectRequest.class))).thenThrow(new IllegalStateException(missing));
    when(client.headObject(any(HeadObjectRequest.class))).thenThrow(missing);
    OssNativeBlobStorage storage = new OssNativeBlobStorage(client, config(64 * 1024 * 1024L));
    BlobReference reference = new BlobReference("bucket", "missing", "a", 1);
    try {
      assertTrue(OssNativeBlobStorage.isNotFound(new IllegalStateException(missing)));
      assertFalse(OssNativeBlobStorage.isNotFound(new IllegalArgumentException("other")));
      assertTrue(storage.get(reference).isEmpty());
      assertTrue(storage.getRange(reference, 0, 1).isEmpty());
      assertTrue(storage.stat(reference).isEmpty());
      assertFalse(storage.exists(reference));
      assertEquals(0, storage.getRange(reference, 0, 0).orElseThrow().readAllBytes().length);
      assertThrows(IllegalArgumentException.class, () -> storage.getRange(reference, -1, 1));
      assertThrows(IllegalArgumentException.class, () -> storage.getRange(reference, 1, -1));
    } finally {
      storage.close();
    }
  }

  @Test
  void multipartFileUploadCompletesAllParts() throws Exception {
    OSSClient client = mock(OSSClient.class);
    InitiateMultipartUploadResult initiated = mock(InitiateMultipartUploadResult.class);
    InitiateMultipartUpload details = InitiateMultipartUpload.newBuilder()
        .bucket("bucket")
        .key("key")
        .uploadId("upload-1")
        .build();
    when(initiated.initiateMultipartUpload()).thenReturn(details);
    when(client.initiateMultipartUpload(any(InitiateMultipartUploadRequest.class))).thenReturn(initiated);
    when(client.uploadPart(any(UploadPartRequest.class))).thenAnswer(invocation -> {
      UploadPartRequest request = invocation.getArgument(0);
      UploadPartResult result = mock(UploadPartResult.class);
      when(result.eTag()).thenReturn("etag-" + request.partNumber());
      return result;
    });
    Path file = tempDir.resolve("large-oss.bin");
    Files.write(file, new byte[6 * 1024 * 1024]);
    OssNativeBlobStorage storage = new OssNativeBlobStorage(client, config(1));
    try {
      BlobReference reference = storage.putFile("raw", "large.bin", file, "f".repeat(64));

      assertEquals(Files.size(file), reference.size());
      ArgumentCaptor<CompleteMultipartUploadRequest> completed =
          ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
      verify(client).completeMultipartUpload(completed.capture());
      assertEquals(
          List.of(1L, 2L),
          completed.getValue().completeMultipartUpload().parts().stream()
              .map(part -> part.partNumber()).toList());
      verify(client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    } finally {
      storage.close();
    }
  }

  private static S3BlobStoreConfig config(long multipartThreshold) {
    return S3BlobStoreConfig.of(
        1,
        "oss-test",
        "https://oss-cn-hangzhou.aliyuncs.com",
        "cn-hangzhou",
        "bucket",
        "prefix",
        Map.of(
            "engine", S3BlobStoreConfig.ENGINE_OSS_NATIVE,
            "accessKey", "ak",
            "secretKey", "sk",
            "multipartThresholdBytes", multipartThreshold,
            "multipartPartSizeBytes", 5L * 1024 * 1024,
            "multipartConcurrency", 2));
  }
}
