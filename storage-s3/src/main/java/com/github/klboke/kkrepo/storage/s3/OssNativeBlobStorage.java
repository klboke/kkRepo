package com.github.klboke.kkrepo.storage.s3;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.AbortMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.CompleteMultipartUpload;
import com.aliyun.sdk.service.oss2.models.CompleteMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.InitiateMultipartUploadRequest;
import com.aliyun.sdk.service.oss2.models.Part;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.models.UploadPartRequest;
import com.aliyun.sdk.service.oss2.models.UploadPartResult;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Blob storage backed by the native Aliyun OSS SDK v2. One instance per blob-store record (built by
 * {@link S3BlobStorageFactory}). Object metadata/existence is owned by MySQL, so this only does
 * by-key byte I/O (get/put/stat/delete) — no list/rename.
 *
 * <p>Unlike the AWS S3 SDK against OSS, the native SDK signs with OSS's own scheme, so a one-shot
 * {@code InputStream} can be uploaded directly (no aws-chunked rejection, no mark/reset payload
 * re-read).
 */
public final class OssNativeBlobStorage implements BlobStorage {
  private final OSSClient client;
  private final S3BlobStoreConfig config;
  private final ExecutorService multipartExecutor;

  public OssNativeBlobStorage(OSSClient client, S3BlobStoreConfig config) {
    this.client = client;
    this.config = config;
    this.multipartExecutor = ObjectStorageMultipartSupport.executor(
        config.name(), ObjectStorageMultipartSupport.storeParallelism(config));
  }

  @Override
  public BlobReference prepareVerifiedUpload(String repository, String sha256, long size) {
    return new BlobReference(config.bucket(),
        BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256), sha256, size);
  }

  @Override
  public com.github.klboke.kkrepo.core.VerifiedBlobDigests uploadVerified(
      BlobReference target, InputStream content, java.util.function.Consumer<String> multipartStarted) {
    var reader = new com.github.klboke.kkrepo.core.VerifiedBlobReader(target, content);
    if (target.size() == 0) {
      var digests = reader.finish();
      client.putObject(PutObjectRequest.newBuilder().bucket(target.bucket()).key(target.objectKey())
          .metadata(Map.of("sha256", target.sha256())).body(BinaryData.fromBytes(new byte[0])).build());
      return digests;
    }
    String uploadId = client.initiateMultipartUpload(InitiateMultipartUploadRequest.newBuilder()
        .bucket(target.bucket()).key(target.objectKey()).metadata(Map.of("sha256", target.sha256()))
        .build()).initiateMultipartUpload().uploadId();
    try {
      multipartStarted.accept(uploadId);
      List<Part> parts = new ArrayList<>();
      byte[] part;
      while ((part = reader.nextPart()).length > 0) {
        long number = parts.size() + 1L;
        var uploaded = client.uploadPart(UploadPartRequest.newBuilder().bucket(target.bucket())
            .key(target.objectKey()).uploadId(uploadId).partNumber(number).contentLength((long) part.length)
            .body(BinaryData.fromBytes(part)).build());
        parts.add(Part.newBuilder().partNumber(number).eTag(uploaded.eTag()).build());
      }
      var digests = reader.finish();
      client.completeMultipartUpload(CompleteMultipartUploadRequest.newBuilder().bucket(target.bucket())
          .key(target.objectKey()).uploadId(uploadId)
          .completeMultipartUpload(CompleteMultipartUpload.newBuilder().parts(parts).build()).build());
      return digests;
    } catch (RuntimeException failure) {
      try { abortVerifiedMultipart(target, uploadId); }
      catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  @Override
  public void discardVerifiedUpload(BlobReference target, String multipartId) {
    if (multipartId != null) {
      abortVerifiedMultipart(target, multipartId);
    } else {
      var uploads = client.listMultipartUploads(com.aliyun.sdk.service.oss2.models.ListMultipartUploadsRequest.newBuilder()
          .bucket(target.bucket()).prefix(target.objectKey()).maxUploads(10L).build());
      if (uploads.uploads() != null) {
        for (var upload : uploads.uploads()) {
          if (target.objectKey().equals(upload.key())) abortVerifiedMultipart(target, upload.uploadId());
        }
      }
    }
    delete(target);
  }

  private void abortVerifiedMultipart(BlobReference target, String uploadId) {
    try {
      client.abortMultipartUpload(AbortMultipartUploadRequest.newBuilder().bucket(target.bucket())
          .key(target.objectKey()).uploadId(uploadId).build());
    } catch (ServiceException error) {
      if (error.statusCode() != 404) throw error;
    }
  }

  @Override
  public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
    String objectKey = BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256);
    client.putObject(PutObjectRequest.newBuilder()
        .bucket(config.bucket())
        .key(objectKey)
        .metadata(metadata(repository, logicalPath, sha256))
        .body(BinaryData.fromStream(content, size))
        .build());
    return new BlobReference(config.bucket(), objectKey, sha256, size);
  }

  @Override
  public BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
    try {
      long size = Files.size(file);
      String objectKey = BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256);
      if (size <= 0 || config.multipartThresholdBytes() <= 0 || size < config.multipartThresholdBytes()) {
        try (InputStream in = Files.newInputStream(file)) {
          client.putObject(PutObjectRequest.newBuilder()
              .bucket(config.bucket())
              .key(objectKey)
              .metadata(metadata(repository, logicalPath, sha256))
              .body(BinaryData.fromStream(in, size))
              .build());
        }
        return new BlobReference(config.bucket(), objectKey, sha256, size);
      }
      multipartPutFile(repository, logicalPath, file, size, sha256, objectKey);
      return new BlobReference(config.bucket(), objectKey, sha256, size);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to upload file to OSS: " + file, e);
    }
  }

  private void multipartPutFile(
      String repository, String logicalPath, Path file, long size, String sha256, String objectKey)
      throws IOException {
    String uploadId = client.initiateMultipartUpload(InitiateMultipartUploadRequest.newBuilder()
        .bucket(config.bucket())
        .key(objectKey)
        .build()).initiateMultipartUpload().uploadId();
    List<Part> parts = new ArrayList<>();
    List<Future<Part>> futures = new ArrayList<>();
    try {
      long partSize = ObjectStorageMultipartSupport.partSize(size, config.multipartPartSizeBytes());
      int partCount = ObjectStorageMultipartSupport.partCount(size, partSize);
      futures = new ArrayList<>(partCount);
      for (int partNumber = 1; partNumber <= partCount; partNumber++) {
        long offset = (long) (partNumber - 1) * partSize;
        long currentSize = Math.min(partSize, size - offset);
        int part = partNumber;
        long partOffset = offset;
        futures.add(multipartExecutor.submit(
            () -> uploadPart(file, objectKey, uploadId, part, partOffset, currentSize)));
      }
      for (Future<Part> future : futures) {
        parts.add(future.get());
      }
      parts.sort(Comparator.comparing(Part::partNumber));
      client.completeMultipartUpload(CompleteMultipartUploadRequest.newBuilder()
          .bucket(config.bucket())
          .key(objectKey)
          .uploadId(uploadId)
          .completeMultipartUpload(CompleteMultipartUpload.newBuilder().parts(parts).build())
          .build());
    } catch (InterruptedException e) {
      cancel(futures);
      abortMultipartUpload(objectKey, uploadId);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while uploading multipart OSS object: " + objectKey, e);
    } catch (ExecutionException e) {
      cancel(futures);
      abortMultipartUpload(objectKey, uploadId);
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      throw new IllegalStateException("Failed to upload multipart OSS object: " + objectKey, cause);
    } catch (RuntimeException e) {
      cancel(futures);
      abortMultipartUpload(objectKey, uploadId);
      throw e;
    }
  }

  private Part uploadPart(
      Path file,
      String objectKey,
      String uploadId,
      int partNumber,
      long offset,
      long length) throws IOException {
    try (InputStream in = ObjectStorageMultipartSupport.openFileSlice(file, offset, length)) {
      UploadPartResult up = client.uploadPart(UploadPartRequest.newBuilder()
          .bucket(config.bucket())
          .key(objectKey)
          .uploadId(uploadId)
          .partNumber((long) partNumber)
          .contentLength(length)
          .body(BinaryData.fromStream(in, length))
          .build());
      return Part.newBuilder().partNumber((long) partNumber).eTag(up.eTag()).build();
    }
  }

  private void abortMultipartUpload(String objectKey, String uploadId) {
    try {
      client.abortMultipartUpload(AbortMultipartUploadRequest.newBuilder()
          .bucket(config.bucket())
          .key(objectKey)
          .uploadId(uploadId)
          .build());
    } catch (RuntimeException ignored) {
      // surface the original failure
    }
  }

  private static void cancel(List<? extends Future<?>> futures) {
    for (Future<?> future : futures) {
      future.cancel(true);
    }
  }

  @Override
  public void close() {
    multipartExecutor.shutdown();
  }

  @Override
  public Optional<InputStream> get(BlobReference reference) {
    try {
      GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
          .bucket(reference.bucket())
          .key(reference.objectKey())
          .build());
      return Optional.of(closeResultWithBody(result, reference));
    } catch (RuntimeException e) {
      if (isNotFound(e)) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public Optional<InputStream> getRange(BlobReference reference, long start, long length) {
    if (start < 0 || length < 0) {
      throw new IllegalArgumentException("range start and length must be non-negative");
    }
    if (length == 0) {
      return Optional.of(InputStream.nullInputStream());
    }
    try {
      GetObjectResult result = client.getObject(GetObjectRequest.newBuilder()
          .bucket(reference.bucket())
          .key(reference.objectKey())
          .range(ObjectStorageMultipartSupport.byteRange(start, length))
          .build());
      return Optional.of(closeResultWithBody(result, reference));
    } catch (RuntimeException e) {
      if (isNotFound(e)) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public boolean exists(BlobReference reference) {
    return stat(reference).isPresent();
  }

  @Override
  public Optional<BlobObjectMetadata> stat(BlobReference reference) {
    try {
      HeadObjectResult head = client.headObject(HeadObjectRequest.newBuilder()
          .bucket(reference.bucket())
          .key(reference.objectKey())
          .build());
      Map<String, String> meta = head.metadata();
      String sha256 = meta == null ? reference.sha256() : meta.getOrDefault("sha256", reference.sha256());
      long length = head.contentLength() == null ? reference.size() : head.contentLength();
      BlobReference resolved = new BlobReference(reference.bucket(), reference.objectKey(), sha256, length);
      return Optional.of(new BlobObjectMetadata(
          resolved, head.eTag(), head.contentType(), parseInstant(head.lastModified())));
    } catch (RuntimeException e) {
      if (isNotFound(e)) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public void delete(BlobReference reference) {
    client.deleteObject(DeleteObjectRequest.newBuilder()
        .bucket(reference.bucket())
        .key(reference.objectKey())
        .build());
  }

  private InputStream closeResultWithBody(GetObjectResult result, BlobReference reference) {
    return new RangeReadableOssInputStream(result, reference);
  }

  private static Map<String, String> metadata(String repository, String logicalPath, String sha256) {
    // Skip blank values: OSS computes the V4 signature over the canonicalized x-oss-meta-* headers,
    // and an empty metadata value makes the client/server signatures disagree (SignatureDoesNotMatch,
    // EC 0002-00000201) — e.g. the read/write probe passes an empty sha256.
    Map<String, String> meta = new LinkedHashMap<>();
    putIfPresent(meta, "repository", repository);
    putIfPresent(meta, "logical-path", logicalPath);
    putIfPresent(meta, "sha256", sha256);
    return meta;
  }

  private static void putIfPresent(Map<String, String> meta, String key, String value) {
    String sanitized = safeMetadata(value);
    if (!sanitized.isEmpty()) {
      meta.put(key, sanitized);
    }
  }

  public static boolean isNotFound(Throwable t) {
    while (t != null) {
      if (t instanceof ServiceException se) {
        return se.statusCode() == 404
            || "NoSuchKey".equals(se.errorCode())
            || "NoSuchBucket".equals(se.errorCode());
      }
      t = t.getCause();
    }
    return false;
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
    } catch (RuntimeException ignored) {
      try {
        return Instant.parse(value);
      } catch (RuntimeException ignored2) {
        return null;
      }
    }
  }

  private static String safeMetadata(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder sanitized = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      sanitized.append(ch >= 0x20 && ch <= 0x7e ? ch : '_');
    }
    return sanitized.toString();
  }

  private final class RangeReadableOssInputStream extends FilterInputStream implements BlobRangeReader {
    private final GetObjectResult result;
    private final BlobReference reference;

    private RangeReadableOssInputStream(GetObjectResult result, BlobReference reference) {
      super(result.body());
      this.result = result;
      this.reference = reference;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        try {
          result.close();
        } catch (IOException e) {
          throw e;
        } catch (Exception e) {
          throw new IOException("Failed to close OSS get result", e);
        }
      }
    }

    @Override
    public InputStream openRange(long start, long length) {
      return OssNativeBlobStorage.this.getRange(reference, start, length)
          .orElseThrow(() -> new IllegalStateException(
              "Missing OSS object while opening range: " + reference.objectKey()));
    }
  }

}
