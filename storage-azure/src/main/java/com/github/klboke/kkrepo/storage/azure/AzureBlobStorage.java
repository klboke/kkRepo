package com.github.klboke.kkrepo.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobUploadFromFileOptions;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.github.klboke.kkrepo.core.BlobObjectKeys;
import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobRangeReader;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.VerifiedBlobDigests;
import com.github.klboke.kkrepo.core.VerifiedBlobReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Azure Blob Storage adapter, one instance per blob store record.
 *
 * <p>Large uploads use the block-blob protocol. Unlike S3 multipart there is no server-side upload
 * ID: blocks are staged against the destination blob name and become visible only when the block
 * list is committed. Because {@link #prepareVerifiedUpload} allocates an attempt-unique key, an
 * interrupted attempt leaves uncommitted blocks on a name that is never reused; Azure discards
 * them automatically after seven days, and {@link #discardVerifiedUpload} removes the blob when
 * the attempt had already been committed.
 */
public final class AzureBlobStorage implements BlobStorage {
  private static final String CONTENT_TYPE = "application/octet-stream";
  /** Azure metadata names must be valid C# identifiers, so no hyphenated keys. */
  private static final String META_REPOSITORY = "repository";
  private static final String META_LOGICAL_PATH = "logicalpath";
  private static final String META_SHA256 = "sha256";

  private final BlobServiceClient serviceClient;
  private final AzureBlobStoreConfig config;

  public AzureBlobStorage(BlobServiceClient serviceClient, AzureBlobStoreConfig config) {
    this.serviceClient = serviceClient;
    this.config = config;
  }

  public AzureBlobStoreConfig config() {
    return config;
  }

  @Override
  public BlobReference prepareVerifiedUpload(String repository, String sha256, long size) {
    return new BlobReference(
        config.container(),
        BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256),
        sha256,
        size);
  }

  @Override
  public VerifiedBlobDigests uploadVerified(
      BlobReference target, InputStream content, Consumer<String> multipartStarted) {
    VerifiedBlobReader reader = new VerifiedBlobReader(target, content);
    BlockBlobClient blockClient = blobClient(target).getBlockBlobClient();
    Map<String, String> metadata = Map.of(META_SHA256, safeMetadata(target.sha256()));

    if (target.size() == 0) {
      VerifiedBlobDigests digests = reader.finish();
      blockClient.commitBlockListWithResponse(
          new BlockBlobCommitBlockListOptions(List.of())
              .setMetadata(metadata)
              .setHeaders(new BlobHttpHeaders().setContentType(CONTENT_TYPE)),
          null, null);
      return digests;
    }

    // The attempt ID both namespaces the staged block IDs and is the handle the caller persists
    // before any block is sent, so a crashed attempt can still be identified and discarded.
    String attemptId = UUID.randomUUID().toString().replace("-", "");
    multipartStarted.accept(attemptId);
    try {
      List<String> blockIds = new ArrayList<>();
      byte[] part;
      while ((part = reader.nextPart()).length > 0) {
        String blockId = blockId(attemptId, blockIds.size());
        blockClient.stageBlock(blockId, new ByteArrayInputStream(part), part.length);
        blockIds.add(blockId);
      }
      VerifiedBlobDigests digests = reader.finish();
      blockClient.commitBlockListWithResponse(
          new BlockBlobCommitBlockListOptions(blockIds)
              .setMetadata(metadata)
              .setHeaders(new BlobHttpHeaders().setContentType(CONTENT_TYPE)),
          null, null);
      return digests;
    } catch (RuntimeException failure) {
      try {
        deleteQuietly(target);
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public void discardVerifiedUpload(BlobReference target, String multipartId) {
    // Uncommitted blocks are invisible and expire on their own; only a committed attempt needs an
    // explicit delete, and the attempt-unique key guarantees this touches no published blob.
    deleteQuietly(target);
  }

  @Override
  public BlobReference put(
      String repository, String logicalPath, InputStream content, long size, String sha256) {
    // The SDK buffers a stream of unknown length in heap (up to the single-upload threshold, then
    // one block per concurrent transfer). Spool to a re-readable file instead, as the S3 adapter
    // does, so memory stays bounded however many uploads run at once.
    Path spooled = null;
    try {
      spooled = Files.createTempFile("kkrepo-azure-put-", ".tmp");
      Files.copy(content, spooled, StandardCopyOption.REPLACE_EXISTING);
      String objectKey = BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256);
      uploadFile(objectKey, spooled, repository, logicalPath, sha256);
      return new BlobReference(config.container(), objectKey, sha256, size);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to spool blob for Azure upload", e);
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

  @Override
  public BlobReference putFile(String repository, String logicalPath, Path file, String sha256) {
    String objectKey = BlobObjectKeys.immutableObjectKey(config.prefix(), repository, sha256);
    uploadFile(objectKey, file, repository, logicalPath, sha256);
    try {
      return new BlobReference(config.container(), objectKey, sha256, Files.size(file));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to size blob upload file: " + file, e);
    }
  }

  private void uploadFile(
      String objectKey, Path file, String repository, String logicalPath, String sha256) {
    blobClient(objectKey).uploadFromFileWithResponse(
        new BlobUploadFromFileOptions(file.toString())
            .setParallelTransferOptions(transferOptions())
            .setHeaders(new BlobHttpHeaders().setContentType(CONTENT_TYPE))
            .setMetadata(metadata(repository, logicalPath, sha256)),
        null, null);
  }

  @Override
  public Optional<InputStream> get(BlobReference reference) {
    try {
      return Optional.of(new RangeReadableAzureInputStream(
          blobClient(reference).openInputStream(new BlobInputStreamOptions()), reference));
    } catch (BlobStorageException e) {
      return notFoundOrRethrow(e);
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
      BlobInputStreamOptions options = new BlobInputStreamOptions()
          .setRange(new BlobRange(start, length));
      return Optional.of(new RangeReadableAzureInputStream(
          blobClient(reference).openInputStream(options), reference));
    } catch (BlobStorageException e) {
      return notFoundOrRethrow(e);
    }
  }

  @Override
  public boolean exists(BlobReference reference) {
    return stat(reference).isPresent();
  }

  @Override
  public Optional<BlobObjectMetadata> stat(BlobReference reference) {
    try {
      BlobProperties properties = blobClient(reference).getProperties();
      Map<String, String> metadata =
          properties.getMetadata() == null ? Map.of() : properties.getMetadata();
      BlobReference resolved = new BlobReference(
          reference.bucket(),
          reference.objectKey(),
          metadata.getOrDefault(META_SHA256, reference.sha256()),
          properties.getBlobSize());
      Instant lastModified = properties.getLastModified() == null
          ? null
          : properties.getLastModified().toInstant();
      return Optional.of(new BlobObjectMetadata(
          resolved, properties.getETag(), properties.getContentType(), lastModified));
    } catch (BlobStorageException e) {
      if (isNotFound(e)) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public void delete(BlobReference reference) {
    try {
      blobClient(reference).deleteIfExists();
    } catch (BlobStorageException e) {
      if (!isNotFound(e)) {
        throw e;
      }
    }
  }

  private ParallelTransferOptions transferOptions() {
    return new ParallelTransferOptions()
        .setBlockSizeLong(config.blockSizeBytes())
        .setMaxSingleUploadSizeLong(Math.max(1, config.uploadThresholdBytes()))
        .setMaxConcurrency(config.uploadConcurrency());
  }

  private Map<String, String> metadata(String repository, String logicalPath, String sha256) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(META_REPOSITORY, safeMetadata(repository));
    metadata.put(META_LOGICAL_PATH, safeMetadata(logicalPath));
    metadata.put(META_SHA256, safeMetadata(sha256));
    return metadata;
  }

  private BlobClient blobClient(BlobReference reference) {
    BlobContainerClient container = serviceClient.getBlobContainerClient(
        reference.bucket() == null || reference.bucket().isBlank()
            ? config.container()
            : reference.bucket());
    return container.getBlobClient(reference.objectKey());
  }

  private BlobClient blobClient(String objectKey) {
    return serviceClient.getBlobContainerClient(config.container()).getBlobClient(objectKey);
  }

  private void deleteQuietly(BlobReference reference) {
    try {
      blobClient(reference).deleteIfExists();
    } catch (BlobStorageException e) {
      if (!isNotFound(e)) {
        throw e;
      }
    }
  }

  private static <T> Optional<T> notFoundOrRethrow(BlobStorageException e) {
    if (isNotFound(e)) {
      return Optional.empty();
    }
    throw e;
  }

  private static boolean isNotFound(BlobStorageException e) {
    return e.getStatusCode() == 404;
  }

  /**
   * Block IDs must be equal-length, Base64 and unique within the blob. The attempt ID keeps a retry
   * from reusing the blocks of an earlier attempt on the same key.
   */
  static String blockId(String attemptId, int index) {
    if (index >= AzureBlobStoreConfig.MAX_BLOCKS) {
      throw new IllegalArgumentException(
          "Block blob upload exceeds " + AzureBlobStoreConfig.MAX_BLOCKS + " blocks");
    }
    String raw = String.format("%s-%06d", attemptId, index);
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Azure metadata travels in HTTP headers, so values must stay printable ASCII. */
  static String safeMetadata(String value) {
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

  private final class RangeReadableAzureInputStream extends FilterInputStream
      implements BlobRangeReader {
    private final BlobReference reference;

    private RangeReadableAzureInputStream(InputStream delegate, BlobReference reference) {
      super(delegate);
      this.reference = reference;
    }

    @Override
    public InputStream openRange(long start, long length) {
      return AzureBlobStorage.this.getRange(reference, start, length)
          .orElseThrow(() -> new IllegalStateException(
              "Missing Azure blob while opening range: " + reference.objectKey()));
    }
  }
}
