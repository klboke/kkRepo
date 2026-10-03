package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * One instance per S3 blob store record. Built by {@link S3BlobStorageFactory} so the same
 * config + S3 client is reused across calls without leaking through Spring as a singleton.
 */
public final class S3BlobStorage implements BlobStorage {
  private final S3Client s3Client;
  private final S3BlobStoreConfig config;
  private final ExecutorService multipartExecutor;

  public S3BlobStorage(S3Client s3Client, S3BlobStoreConfig config) {
    this.s3Client = s3Client;
    this.config = config;
    this.multipartExecutor = ObjectStorageMultipartSupport.executor(
        config.name(), ObjectStorageMultipartSupport.storeParallelism(config));
  }

  public S3BlobStoreConfig config() {
    return config;
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
      s3Client.putObject(PutObjectRequest.builder().bucket(target.bucket()).key(target.objectKey())
          .contentType("application/octet-stream").metadata(Map.of("sha256", target.sha256())).build(),
          RequestBody.empty());
      return digests;
    }
    String uploadId = s3Client.createMultipartUpload(
        software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.builder()
            .bucket(target.bucket()).key(target.objectKey()).contentType("application/octet-stream")
            .metadata(Map.of("sha256", target.sha256())).build()).uploadId();
    try {
      multipartStarted.accept(uploadId);
      var parts = new java.util.ArrayList<software.amazon.awssdk.services.s3.model.CompletedPart>();
      byte[] part;
      while ((part = reader.nextPart()).length > 0) {
        int number = parts.size() + 1;
        var uploaded = s3Client.uploadPart(software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
            .bucket(target.bucket()).key(target.objectKey()).uploadId(uploadId).partNumber(number)
            .contentLength((long) part.length).build(), RequestBody.fromBytes(part));
        parts.add(software.amazon.awssdk.services.s3.model.CompletedPart.builder()
            .partNumber(number).eTag(uploaded.eTag()).build());
      }
      var digests = reader.finish();
      s3Client.completeMultipartUpload(software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.builder()
          .bucket(target.bucket()).key(target.objectKey()).uploadId(uploadId)
          .multipartUpload(software.amazon.awssdk.services.s3.model.CompletedMultipartUpload.builder().parts(parts).build())
          .build());
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
      // Recover the crash window between provider initiation and persisting its handle.
      var uploads = s3Client.listMultipartUploads(software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest.builder()
          .bucket(target.bucket()).prefix(target.objectKey()).maxUploads(10).build());
      for (var upload : uploads.uploads()) {
        if (target.objectKey().equals(upload.key())) abortVerifiedMultipart(target, upload.uploadId());
      }
    }
    delete(target);
  }

  private void abortVerifiedMultipart(BlobReference target, String uploadId) {
    try {
      s3Client.abortMultipartUpload(software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.builder()
          .bucket(target.bucket()).key(target.objectKey()).uploadId(uploadId).build());
    } catch (software.amazon.awssdk.services.s3.model.S3Exception error) {
      if (error.statusCode() != 404) throw error;
    }
  }

  @Override
  public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
    String objectKey = BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256);
    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(config.bucket())
        .key(objectKey)
        .contentLength(size)
        .metadata(Map.of(
            "repository", safeMetadata(repository),
            "logical-path", safeMetadata(logicalPath),
            "sha256", safeMetadata(sha256)))
        .build();
    if (content.markSupported()) {
      s3Client.putObject(request, RequestBody.fromInputStream(content, size));
    } else {
      // With chunked encoding disabled (for OSS compatibility) the SDK signs the payload and reads
      // the body twice; a one-shot stream (e.g. a proxy network stream) can't mark/reset. Spool to
      // a re-readable temp file.
      Path spooled = null;
      try {
        spooled = Files.createTempFile("nexus-s3-put-", ".tmp");
        Files.copy(content, spooled, StandardCopyOption.REPLACE_EXISTING);
        s3Client.putObject(request, RequestBody.fromFile(spooled));
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to spool blob for S3 upload: " + objectKey, e);
      } finally {
        if (spooled != null) {
          try {
            Files.deleteIfExists(spooled);
          } catch (IOException ignored) {
            // best-effort temp cleanup
          }
        }
      }
    }
    return new BlobReference(config.bucket(), objectKey, sha256, size);
  }

  @Override
  public BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
    try {
      long size = Files.size(file);
      String objectKey = BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256);
      if (size <= 0 || config.multipartThresholdBytes() <= 0 || size < config.multipartThresholdBytes()) {
        // Upload straight from the file (re-readable) rather than a one-shot InputStream: with
        // chunked encoding disabled (for OSS compatibility) the SDK signs the payload and must read
        // the body twice, which a Files.newInputStream cannot do (mark/reset unsupported).
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(config.bucket())
            .key(objectKey)
            .metadata(Map.of(
                "repository", safeMetadata(repository),
                "logical-path", safeMetadata(logicalPath),
                "sha256", safeMetadata(sha256)))
            .build();
        s3Client.putObject(request, RequestBody.fromFile(file));
        return new BlobReference(config.bucket(), objectKey, sha256, size);
      }
      multipartPutFile(repository, logicalPath, file, size, sha256, objectKey);
      return new BlobReference(config.bucket(), objectKey, sha256, size);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to upload file to S3: " + file, e);
    }
  }

  private void multipartPutFile(
      String repository,
      String logicalPath,
      Path file,
      long size,
      String sha256,
      String objectKey) {
    var create = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
        .bucket(config.bucket())
        .key(objectKey)
        .metadata(Map.of(
            "repository", safeMetadata(repository),
            "logical-path", safeMetadata(logicalPath),
            "sha256", safeMetadata(sha256)))
        .build());
    String uploadId = create.uploadId();
    List<CompletedPart> parts = new ArrayList<>();
    List<Future<CompletedPart>> futures = new ArrayList<>();
    try {
      long partSize = ObjectStorageMultipartSupport.partSize(size, config.multipartPartSizeBytes());
      int partCount = ObjectStorageMultipartSupport.partCount(size, partSize);
      futures = new ArrayList<>(partCount);
      for (int partNumber = 1; partNumber <= partCount; partNumber++) {
        long offset = (long) (partNumber - 1) * partSize;
        long partOffset = offset;
        long currentSize = Math.min(partSize, size - offset);
        int part = partNumber;
        futures.add(multipartExecutor.submit(
            () -> uploadPart(file, objectKey, uploadId, part, partOffset, currentSize)));
      }
      for (Future<CompletedPart> future : futures) {
        parts.add(future.get());
      }
      parts.sort(Comparator.comparingInt(CompletedPart::partNumber));
      s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
          .bucket(config.bucket())
          .key(objectKey)
          .uploadId(uploadId)
          .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
          .build());
    } catch (InterruptedException e) {
      cancel(futures);
      abortMultipartUpload(objectKey, uploadId);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while uploading multipart S3 object: " + objectKey, e);
    } catch (ExecutionException e) {
      cancel(futures);
      abortMultipartUpload(objectKey, uploadId);
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Failed to upload multipart S3 object: " + objectKey, cause);
    } catch (RuntimeException e) {
      cancel(futures);
      abortMultipartUpload(objectKey, uploadId);
      throw e;
    }
  }

  private CompletedPart uploadPart(
      Path file,
      String objectKey,
      String uploadId,
      int partNumber,
      long offset,
      long length) {
    // Re-readable provider: with chunked encoding disabled the SDK signs each part and may read it
    // more than once, so each read opens an independent channel already positioned at the part.
    ContentStreamProvider provider = () -> ObjectStorageMultipartSupport.openFileSlice(file, offset, length);
    var response = s3Client.uploadPart(UploadPartRequest.builder()
            .bucket(config.bucket())
            .key(objectKey)
            .uploadId(uploadId)
            .partNumber(partNumber)
            .contentLength(length)
            .build(),
        RequestBody.fromContentProvider(provider, length, "application/octet-stream"));
    return CompletedPart.builder()
        .partNumber(partNumber)
        .eTag(response.eTag())
        .build();
  }

  private void abortMultipartUpload(String objectKey, String uploadId) {
    try {
      s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
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
      ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
          .bucket(reference.bucket())
          .key(reference.objectKey())
          .build());
      return Optional.of(new RangeReadableS3InputStream(response, reference));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
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
      ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
          .bucket(reference.bucket())
          .key(reference.objectKey())
          .range(ObjectStorageMultipartSupport.byteRange(start, length))
          .build());
      return Optional.of(new RangeReadableS3InputStream(response, reference));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
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
      var response = s3Client.headObject(HeadObjectRequest.builder()
          .bucket(reference.bucket())
          .key(reference.objectKey())
          .build());
      BlobReference resolved = new BlobReference(
          reference.bucket(),
          reference.objectKey(),
          response.metadata().getOrDefault("sha256", reference.sha256()),
          response.contentLength());
      return Optional.of(new BlobObjectMetadata(
          resolved,
          response.eTag(),
          response.contentType(),
          response.lastModified()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public void delete(BlobReference reference) {
    s3Client.deleteObject(DeleteObjectRequest.builder()
        .bucket(reference.bucket())
        .key(reference.objectKey())
        .build());
  }

  private static String safeMetadata(String value) {
    if (value == null || value.isEmpty()) return "";
    StringBuilder sanitized = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      sanitized.append(ch >= 0x20 && ch <= 0x7e ? ch : '_');
    }
    return sanitized.toString();
  }

  private final class RangeReadableS3InputStream extends FilterInputStream implements BlobRangeReader {
    private final BlobReference reference;

    private RangeReadableS3InputStream(InputStream delegate, BlobReference reference) {
      super(delegate);
      this.reference = reference;
    }

    @Override
    public InputStream openRange(long start, long length) {
      return S3BlobStorage.this.getRange(reference, start, length)
          .orElseThrow(() -> new IllegalStateException(
              "Missing S3 object while opening range: " + reference.objectKey()));
    }
  }
}
