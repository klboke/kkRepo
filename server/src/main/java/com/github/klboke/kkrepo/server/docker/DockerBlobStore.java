package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.docker.DockerConstants;
import com.github.klboke.kkrepo.protocol.docker.DockerDigest;
import com.github.klboke.kkrepo.protocol.docker.DockerErrorCode;
import com.github.klboke.kkrepo.protocol.docker.DockerProtocolException;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DockerBlobStore {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final DockerMetrics metrics;

  @Autowired
  public DockerBlobStore(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      AssetMetadataCache assetMetadataCache,
      TransientTransactionRetry transactionRetry,
      GroupMemberAssetCache groupMemberAssetCache,
      DockerMetrics metrics) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.assetMetadataCache = assetMetadataCache;
    this.transactionRetry = transactionRetry;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.metrics = metrics;
  }

  public DockerBlobStore(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      AssetMetadataCache assetMetadataCache,
      TransientTransactionRetry transactionRetry) {
    this(assetDao, blobStorageRegistry, assetMetadataCache, transactionRetry, null, null);
  }

  public Optional<StoredBlob> findBlob(RepositoryRuntime runtime, DockerDigest digest) {
    Optional<StoredBlob> internalPath = assetDao.findAssetByPath(runtime.id(), blobPath(digest))
        .flatMap(asset -> asset.assetBlobId() == null
            ? Optional.empty()
            : assetDao.findBlobById(asset.assetBlobId()).map(blob -> new StoredBlob(asset, blob)));
    if (internalPath.isPresent() || !digest.isSha256()) {
      return internalPath;
    }
    return assetDao.findDockerBlobAssetBySha256(runtime.id(), digest.hex())
        .flatMap(asset -> asset.assetBlobId() == null
            ? Optional.empty()
            : assetDao.findBlobById(asset.assetBlobId()).map(blob -> new StoredBlob(asset, blob)));
  }

  public DockerResponse getBlob(RepositoryRuntime runtime, DockerDigest digest, boolean headOnly) {
    StoredBlob stored = findBlob(runtime, digest)
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, digest.value()));
    assetDao.touchLastDownloaded(stored.asset().id(), Instant.now());
    DockerResponse response = headOnly
        ? DockerResponse.noBody(200, stored.blob().size(), contentType(stored.blob()), stored.asset().lastUpdatedAt())
        : DockerResponse.body(
            200,
            () -> open(stored.blob()),
            stored.blob().size(),
            contentType(stored.blob()),
            stored.asset().lastUpdatedAt());
    return response
        .withContentType(contentType(stored.blob()))
        .withHeader(DockerConstants.CONTENT_DIGEST_HEADER, digest.value())
        .withHeader("Accept-Ranges", "bytes")
        .withHeader("ETag", "\"" + digest.value() + "\"");
  }

  @Transactional
  public int deleteBlob(RepositoryRuntime runtime, DockerDigest digest) {
    StoredBlob stored = findBlob(runtime, digest)
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, digest.value()));
    assetDao.deleteAssetById(stored.asset().id());
    if (stored.asset().assetBlobId() != null) {
      assetDao.markBlobDeletedIfUnreferenced(stored.asset().assetBlobId(), "docker blob deleted");
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), stored.asset().path());
    invalidateGroupMemberCaches(runtime);
    return 1;
  }

  public StoredBlob putBlob(
      RepositoryRuntime runtime,
      DockerDigest digest,
      InputStream body,
      long size,
      String contentType,
      String createdBy,
      String createdByIp) {
    if (!digest.isSha256()) {
      throw new DockerProtocolException(DockerErrorCode.DIGEST_INVALID, "only sha256 blobs are supported", 400);
    }
    Optional<AssetBlobRecord> reusable = assetDao.findReusableBlobBySha256(
        runtime.blobStoreId(), digest.hex(), size);
    if (reusable.isPresent()) {
      return persistBlobRecordReuse(runtime, digest, reusable.get(), null, createdBy, createdByIp);
    }
    BlobStorage storage = storage(runtime);
    BlobReference reference = storage.put(
        runtime.name(),
        blobPath(digest),
        body,
        size,
        digest.hex());
    try {
      return executePersist(() -> persistBlob(
          runtime, digest, reference, size, contentType, createdBy, createdByIp));
    } catch (RuntimeException e) {
      BlobTransactionCleanup.deleteIfUnreferenced(
          assetDao, storage, runtime.blobStoreId(), reference, "Docker blob persist failure");
      throw e;
    }
  }

  public StoredBlob putVerifiedBlob(
      RepositoryRuntime runtime,
      DockerDigest digest,
      InputStream body,
      long size,
      String contentType,
      String createdBy,
      String createdByIp) {
    if (!digest.isSha256()) {
      throw new DockerProtocolException(DockerErrorCode.DIGEST_INVALID, "only sha256 blobs are supported", 400);
    }
    try (VerifyingInputStream verified = new VerifyingInputStream(body, digest, size)) {
      Optional<AssetBlobRecord> reusable = assetDao.findReusableBlobBySha256(
          runtime.blobStoreId(), digest.hex(), size);
      if (reusable.isPresent()) {
        try {
          verified.transferTo(java.io.OutputStream.nullOutputStream());
          StoredBlob stored = persistBlobRecordReuse(runtime, digest, reusable.get(), null, createdBy, createdByIp);
          recordDigest(runtime, "verified_blob", "success");
          return stored;
        } catch (DockerProtocolException e) {
          recordDigest(runtime, "verified_blob", "failure");
          throw e;
        }
      }
      BlobStorage storage = storage(runtime);
      BlobReference reference = null;
      try {
        reference = storage.put(
            runtime.name(),
            blobPath(digest),
            verified,
            size,
            digest.hex());
        verified.verifyDigest();
        BlobReference uploadedReference = reference;
        StoredBlob stored = executePersist(() -> persistBlob(
            runtime, digest, uploadedReference, size, contentType, createdBy, createdByIp));
        recordDigest(runtime, "verified_blob", "success");
        return stored;
      } catch (RuntimeException e) {
        recordDigest(runtime, "verified_blob", "failure");
        if (reference != null) {
          BlobTransactionCleanup.deleteIfUnreferenced(
              assetDao, storage, runtime.blobStoreId(), reference, "Docker blob persist failure");
        }
        throw e;
      }
    } catch (IOException e) {
      recordDigest(runtime, "verified_blob", "failure");
      throw new UncheckedIOException(e);
    }
  }

  public Optional<StoredBlob> mountBlob(
      RepositoryRuntime target,
      RepositoryRuntime source,
      DockerDigest digest,
      String imageName,
      String createdBy,
      String createdByIp) {
    if (!sameBlobStore(target, source)) {
      return Optional.empty();
    }
    Optional<StoredBlob> sourceBlob = findBlob(source, digest);
    if (sourceBlob.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(executePersist(() -> persistBlobRecordReuse(
        target, digest, sourceBlob.get().blob(), imageName, createdBy, createdByIp)));
  }

  public Optional<StoredBlob> mountBlob(
      RepositoryRuntime target,
      RepositoryRuntime source,
      StoredBlob sourceBlob,
      DockerDigest digest,
      String imageName,
      String createdBy,
      String createdByIp) {
    if (sourceBlob == null || !sameBlobStore(target, source)) {
      return Optional.empty();
    }
    return Optional.of(executePersist(() -> persistBlobRecordReuse(
        target, digest, sourceBlob.blob(), imageName, createdBy, createdByIp)));
  }

  private StoredBlob persistBlob(
      RepositoryRuntime runtime,
      DockerDigest digest,
      BlobReference reference,
      long size,
      String contentType,
      String createdBy,
      String createdByIp) {
    Instant now = Instant.now();
    String blobRef = BlobReferenceCodec.format(reference);
    AssetBlobRecord blob = assetDao.findReusableBlobBySha256(runtime.blobStoreId(), digest.hex(), size)
        .orElseGet(() -> assetDao.insertBlobOrFindExisting(new AssetBlobRecord(
            null,
            runtime.blobStoreId(),
            blobRef,
            PersistenceHashes.blobRefHash(blobRef),
            reference.objectKey(),
            PersistenceHashes.objectKeyHash(reference.objectKey()),
            null,
            digest.hex(),
            null,
            size,
            contentType(contentType),
            createdBy,
            createdByIp,
            now,
            now,
            Map.of("dockerDigest", digest.value()))));
    return persistBlobRecordReuse(runtime, digest, blob, null, createdBy, createdByIp);
  }

  @Transactional
  StoredBlob persistBlobRecordReuse(
      RepositoryRuntime runtime,
      DockerDigest digest,
      AssetBlobRecord blob,
      String imageName,
      String createdBy,
      String createdByIp) {
    Instant now = Instant.now();
    String path = blobPath(digest);
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("docker", Map.of(
        "digest", digest.value(),
        "imageName", imageName == null ? "" : imageName,
        "kind", "blob"));
    AssetRecord asset;
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      assetDao.updateAssetBlobBindingAndMetadata(
          prior.id(),
          null,
          blob.id(),
          "BLOB",
          contentType(blob),
          blob.size(),
          now,
          attrs);
      asset = new AssetRecord(
          prior.id(),
          prior.repositoryId(),
          null,
          blob.id(),
          prior.format(),
          prior.path(),
          prior.pathHash(),
          prior.name(),
          "BLOB",
          contentType(blob),
          blob.size(),
          prior.lastDownloadedAt(),
          now,
          attrs);
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          null,
          blob.id(),
          RepositoryFormat.DOCKER,
          path,
          PersistenceHashes.pathHash(path),
          digest.value(),
          "BLOB",
          contentType(blob),
          blob.size(),
          null,
          now,
          attrs);
      OptionalLong id = assetDao.tryInsertAsset(record);
      if (id.isPresent()) {
        asset = new AssetRecord(
            id.getAsLong(),
            record.repositoryId(),
            record.componentId(),
            record.assetBlobId(),
            record.format(),
            record.path(),
            record.pathHash(),
            record.name(),
            record.kind(),
            record.contentType(),
            record.size(),
            record.lastDownloadedAt(),
            record.lastUpdatedAt(),
            record.attributes());
      } else {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path).orElseThrow();
        assetDao.updateAssetBlobBindingAndMetadata(
            prior.id(), null, blob.id(), "BLOB", contentType(blob), blob.size(), now, attrs);
        asset = prior;
      }
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), path);
    invalidateGroupMemberCaches(runtime);
    return new StoredBlob(asset, blob);
  }

  public String blobPath(DockerDigest digest) {
    String hex = digest.hex();
    String prefix = hex.length() >= 2 ? hex.substring(0, 2) : "xx";
    return "docker/blobs/" + digest.algorithm() + "/" + prefix + "/" + hex;
  }

  public BlobStorage storage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  public InputStream openBlob(AssetBlobRecord blob) {
    return open(blob);
  }

  private InputStream open(AssetBlobRecord blob) {
    return blobStorageRegistry.forBlobStoreId(blob.blobStoreId())
        .get(BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> new DockerProtocolException(DockerErrorCode.BLOB_UNKNOWN, blob.sha256()));
  }

  private boolean sameBlobStore(RepositoryRuntime target, RepositoryRuntime source) {
    return target.blobStoreId() != null && target.blobStoreId().equals(source.blobStoreId());
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    if (runtime.blobStoreId() == null) {
      throw new IllegalStateException("Docker repository " + runtime.name() + " has no blob store");
    }
    return runtime.blobStoreId();
  }

  private <T> T executePersist(java.util.function.Supplier<T> callback) {
    if (transactionRetry == null) {
      return callback.get();
    }
    return transactionRetry.executeIfNoTransaction("Persist Docker blob", callback);
  }

  private void invalidateGroupMemberCaches(RepositoryRuntime runtime) {
    if (groupMemberAssetCache != null && runtime != null) {
      groupMemberAssetCache.invalidateMemberAfterCommit(runtime.id());
      recordCache(runtime, "group_member", "invalidate_member");
    }
  }

  private void recordDigest(RepositoryRuntime runtime, String target, String outcome) {
    if (metrics != null) {
      metrics.digestVerification(runtime, target, outcome);
    }
  }

  private void recordCache(RepositoryRuntime runtime, String cache, String result) {
    if (metrics != null) {
      metrics.cache(cache, runtime, result);
    }
  }

  private static String contentType(AssetBlobRecord blob) {
    return contentType(blob.contentType());
  }

  private static String contentType(String value) {
    return value == null || value.isBlank()
        ? "application/octet-stream"
        : value;
  }

  public record StoredBlob(AssetRecord asset, AssetBlobRecord blob) {
  }

  private static final class VerifyingInputStream extends java.io.FilterInputStream {
    private final DockerDigest expectedDigest;
    private final long expectedSize;
    private final MessageDigest sha256;
    private long size;
    private boolean verified;

    private VerifyingInputStream(InputStream delegate, DockerDigest expectedDigest, long expectedSize) {
      super(delegate);
      this.expectedDigest = expectedDigest;
      this.expectedSize = expectedSize;
      try {
        this.sha256 = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) {
        sha256.update((byte) value);
        size++;
      } else {
        verifyDigest();
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int read = super.read(b, off, len);
      if (read > 0) {
        sha256.update(b, off, read);
        size += read;
      } else if (read < 0) {
        verifyDigest();
      }
      return read;
    }

    private void verifyDigest() {
      if (verified) {
        return;
      }
      verified = true;
      String actual = HexFormat.of().formatHex(sha256.digest());
      if (size != expectedSize || !actual.equals(expectedDigest.hex())) {
        throw new DockerProtocolException(DockerErrorCode.DIGEST_INVALID, "uploaded blob digest mismatch", 400);
      }
    }
  }
}
