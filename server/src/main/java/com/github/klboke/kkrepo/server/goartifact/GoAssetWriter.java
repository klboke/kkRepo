package com.github.klboke.kkrepo.server.goartifact;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;

@Component
public class GoAssetWriter {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;

  public GoAssetWriter(AssetDao assetDao, ComponentDao componentDao, BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache, TransientTransactionRetry transactionRetry) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.browseNodeDao = browseNodeDao;
    this.assetMetadataCache = assetMetadataCache;
    this.transactionRetry = transactionRetry;
  }

  public record Stored(AssetRecord asset, AssetBlobRecord blob, Path responseFile) {
    public InputStream openBody() {
      return TempBlobFiles.openDeleteOnClose(responseFile);
    }

    public void discardBody() {
      TempBlobFiles.deleteQuietly(responseFile);
    }
  }

  private record Digests(String md5, String sha1, String sha256, long size) {}

  private record DigestedUpload(
      BlobReference reference,
      Digests digests,
      Path tempFile,
      boolean uploaded) {}

  public Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      GoPath path,
      InputStream body,
      Map<String, String> extraBlobAttributes) {
    return write(runtime, storage, blobStoreId, path, body, extraBlobAttributes, false);
  }

  public Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      GoPath path,
      InputStream body,
      Map<String, String> extraBlobAttributes,
      boolean keepResponseFile) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, extraBlobAttributes);
    try {
      Stored stored = executePersist(
          "Persist Go asset " + runtime.name() + "/" + path.path(),
          () -> persist(runtime, blobStoreId, path, upload, extraBlobAttributes,
              keepResponseFile ? upload.tempFile() : null));
      cleanupUnusedUploadedBlob(storage, blobStoreId, upload, stored.blob());
      if (!keepResponseFile) {
        TempBlobFiles.deleteQuietly(upload.tempFile());
      }
      return stored;
    } catch (RuntimeException e) {
      cleanupUploadedBlob(storage, blobStoreId, upload);
      TempBlobFiles.deleteQuietly(upload.tempFile());
      throw e;
    }
  }

  private <T> T executePersist(String operation, java.util.function.Supplier<T> callback) {
    if (transactionRetry == null) {
      return callback.get();
    }
    return transactionRetry.executeIfNoTransaction(operation, callback);
  }

  private DigestedUpload uploadWithDigests(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      GoPath path,
      InputStream body,
      Map<String, String> extraBlobAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-go-", ".tmp");
      MessageDigest md5 = digest("MD5");
      MessageDigest sha1 = digest("SHA-1");
      MessageDigest sha256 = digest("SHA-256");
      long size;
      try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
        size = streamWithDigests(UpstreamBodyReadException.wrap(body), out, md5, sha1, sha256);
      }
      String md5Hex = hex(md5.digest());
      String sha1Hex = hex(sha1.digest());
      String sha256Hex = hex(sha256.digest());
      Digests digests = new Digests(md5Hex, sha1Hex, sha256Hex, size);
      Optional<AssetBlobRecord> reusable = precheckedReusableBlob(blobStoreId, sha256Hex, size, extraBlobAttributes);
      if (reusable.isPresent()) {
        AssetBlobRecord blob = reusable.get();
        BlobReference ref = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
        return new DigestedUpload(ref, digests, tmp, false);
      }
      BlobReference ref = storage.putFile(runtime.name(), path.path(), tmp, sha256Hex);
      return new DigestedUpload(ref, digests, tmp, true);
    } catch (RuntimeException | IOException e) {
      TempBlobFiles.deleteQuietly(tmp);
      if (e instanceof IOException io) {
        throw new IllegalStateException("Failed to buffer Go proxy content for " + path.path(), io);
      }
      throw (RuntimeException) e;
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "Go metadata persist failure");
  }

  private void cleanupUnusedUploadedBlob(
      BlobStorage storage,
      long blobStoreId,
      DigestedUpload upload,
      AssetBlobRecord persistedBlob) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfNotReferencedByMetadata(
        assetDao, storage, blobStoreId, upload.reference(),
        persistedBlob == null ? null : persistedBlob.objectKey(), "Go metadata reuse");
  }

  private Optional<AssetBlobRecord> reusableBlob(
      long blobStoreId,
      String sha256,
      long size,
      Map<String, String> extraBlobAttributes) {
    if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()) {
      return assetDao.recoverDeletedBlobBySha256(blobStoreId, sha256, size);
    }
    return assetDao.findReusableBlobBySha256(blobStoreId, sha256, size);
  }

  private Optional<AssetBlobRecord> precheckedReusableBlob(
      long blobStoreId,
      String sha256,
      long size,
      Map<String, String> extraBlobAttributes) {
    if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()) {
      return Optional.empty();
    }
    return assetDao.findReusableBlobBySha256(blobStoreId, sha256, size);
  }

  private Stored persist(
      RepositoryRuntime runtime,
      long blobStoreId,
      GoPath path,
      DigestedUpload upload,
      Map<String, String> extraBlobAttributes,
      Path responseFile) {
    Instant now = Instant.now();
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);

    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path.path());
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);

    Map<String, Object> blobAttrs = new LinkedHashMap<>();
    if (extraBlobAttributes != null) {
      extraBlobAttributes.forEach((k, v) -> { if (v != null && !v.isBlank()) blobAttrs.put(k, v); });
    }
    AssetBlobRecord persistedBlob = reusableBlob(blobStoreId, digests.sha256(), digests.size(), extraBlobAttributes)
        .orElse(null);
    long blobId;
    if (persistedBlob == null) {
      AssetBlobRecord blobRecord = new AssetBlobRecord(
          null,
          blobStoreId,
          blobRef,
          PersistenceHashes.blobRefHash(blobRef),
          ref.objectKey(),
          PersistenceHashes.objectKeyHash(ref.objectKey()),
          digests.sha1(),
          digests.sha256(),
          digests.md5(),
          digests.size(),
          path.contentType(),
          "proxy",
          runtime.proxyRemoteUrl(),
          now,
          now,
          blobAttrs);
      persistedBlob = assetDao.insertBlobOrFindExisting(blobRecord);
      blobId = persistedBlob.id();
    } else {
      blobId = persistedBlob.id();
      if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()) {
        assetDao.updateBlobAttributes(blobId, blobAttrs);
        persistedBlob = persistedBlob.withAttributes(blobAttrs);
      }
    }

    Long componentId = path.hasComponent() ? upsertComponent(runtime, path, now) : null;
    Map<String, Object> attrs = assetAttributes(path);
    AssetRecord persistedAsset;
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blobId, path.kind().name(),
          path.contentType(), digests.size(), now, attrs);
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blobId,
          RepositoryFormat.GO,
          path.path(),
          PersistenceHashes.pathHash(path.path()),
          path.fileName(),
          path.kind().name(),
          path.contentType(),
          digests.size(),
          null,
          now,
          attrs);
      OptionalLong insertedAssetId = assetDao.tryInsertAsset(record);
      if (insertedAssetId.isPresent()) {
        long assetId = insertedAssetId.getAsLong();
        persistedAsset = new AssetRecord(
            assetId, record.repositoryId(), record.componentId(), record.assetBlobId(),
            record.format(), record.path(), record.pathHash(), record.name(), record.kind(),
            record.contentType(), record.size(), record.lastDownloadedAt(), record.lastUpdatedAt(),
            record.attributes());
      } else {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path.path())
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent Go asset insert won but row is not visible for " + runtime.name() + "/" + path.path()));
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, path.kind().name(),
            path.contentType(), digests.size(), now, attrs);
      }
    }

    if (previousBlobId != null && previousBlobId != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlobId, "asset replaced");
    }

    try {
      browseNodeDao.upsertPathAncestors(runtime.id(), path.path(), persistedAsset.id(), componentId);
    } catch (CannotAcquireLockException ignored) {
      // Browse indexing is a side effect for proxy reads; content serving must not fail on
      // concurrent first-hit directory creation.
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), path.path());
    return new Stored(persistedAsset, persistedBlob, responseFile);
  }

  private AssetRecord updateExistingAsset(
      AssetRecord prior,
      Long componentId,
      long blobId,
      String kind,
      String contentType,
      long size,
      Instant lastUpdatedAt,
      Map<String, Object> attributes) {
    Long effectiveComponentId = componentId != null ? componentId : prior.componentId();
    assetDao.updateAssetBlobBindingAndMetadata(
        prior.id(), effectiveComponentId, blobId, kind, contentType, size, lastUpdatedAt, attributes);
    return new AssetRecord(
        prior.id(), prior.repositoryId(), effectiveComponentId, blobId, prior.format(), prior.path(),
        prior.pathHash(), prior.name(), kind, contentType, size, prior.lastDownloadedAt(),
        lastUpdatedAt, attributes);
  }

  private long upsertComponent(RepositoryRuntime runtime, GoPath path, Instant now) {
    byte[] coordinate = PersistenceHashes.componentCoordinateHash(null, path.module(), path.version());
    ComponentRecord rec = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.GO,
        null,
        path.module(),
        path.version(),
        "go-module",
        coordinate,
        Map.of("module", path.module(), "version", path.version()),
        now);
    return componentDao.upsertReturningId(rec);
  }

  private static Map<String, Object> assetAttributes(GoPath path) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("module", path.module());
    if (path.version() != null) {
      attributes.put("version", path.version());
    }
    attributes.put("asset_kind", path.kind().name());
    return attributes;
  }

  private static long streamWithDigests(InputStream in, OutputStream out, MessageDigest... digests)
      throws IOException {
    byte[] buf = new byte[TempBlobFiles.responseBufferSize()];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      for (MessageDigest d : digests) d.update(buf, 0, n);
      out.write(buf, 0, n);
      total += n;
    }
    return total;
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing digest algorithm: " + algorithm, e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

}
