package com.github.klboke.kkrepo.server.raw;

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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class RawAssetWriter {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;

  RawAssetWriter(AssetDao assetDao, ComponentDao componentDao, BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache, TransientTransactionRetry transactionRetry) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.browseNodeDao = browseNodeDao;
    this.assetMetadataCache = assetMetadataCache;
    this.transactionRetry = transactionRetry;
  }

  record Stored(AssetRecord asset, AssetBlobRecord blob, Digests digests, boolean created, Path responseFile) {
    InputStream openBody() {
      return TempBlobFiles.openDeleteOnClose(responseFile);
    }

    void discardBody() {
      TempBlobFiles.deleteQuietly(responseFile);
    }
  }

  record Digests(String md5, String sha1, String sha256, String sha512, long size) {
  }

  Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      Map<String, ?> extraBlobAttributes,
      String createdBy,
      String createdByIp) {
    return write(runtime, storage, blobStoreId, path, body, contentTypeHint,
        extraBlobAttributes, createdBy, createdByIp, ComponentBinding.perAsset(), false);
  }

  Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      Map<String, ?> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, path, body, contentTypeHint,
        extraBlobAttributes, createdBy, createdByIp, ComponentBinding.perAsset(), keepResponseFile);
  }

  Stored writeUnindexed(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      Map<String, ?> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, path, body, contentTypeHint,
        extraBlobAttributes, createdBy, createdByIp, ComponentBinding.none(), keepResponseFile);
  }

  Stored writeWithComponent(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      Map<String, ?> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      ComponentRecord component,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, path, body, contentTypeHint,
        extraBlobAttributes, createdBy, createdByIp, ComponentBinding.explicit(component),
        keepResponseFile);
  }

  private Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      Map<String, ?> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      ComponentBinding componentBinding,
      boolean keepResponseFile) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, extraBlobAttributes);
    try {
      Stored stored = executePersist(
          "Persist raw asset " + runtime.name() + "/" + path,
          () -> persist(runtime, storage, blobStoreId, path, upload, contentTypeHint,
              extraBlobAttributes, createdBy, createdByIp, componentBinding,
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

  @Transactional
  int deleteAsset(RepositoryRuntime runtime, BlobStorage storage, String path) {
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    if (existing.isEmpty()) {
      return 0;
    }
    AssetRecord asset = existing.get();
    Long componentId = asset.componentId();
    deleteOne(storage, asset);
    if (componentId != null) {
      componentDao.deleteIfNoAssets(componentId);
    }
    return 1;
  }

  private Stored persist(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      DigestedUpload upload,
      String contentTypeHint,
      Map<String, ?> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      ComponentBinding componentBinding,
      Path responseFile) {
    Instant now = Instant.now();
    String contentType = contentTypeHint == null || contentTypeHint.isBlank()
        ? MediaType.APPLICATION_OCTET_STREAM_VALUE
        : contentTypeHint;
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);

    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    Long previousComponentId = existing.map(AssetRecord::componentId).orElse(null);
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);
    AssetBlobRecord previousBlob = previousBlobId == null
        ? null
        : assetDao.findBlobById(previousBlobId).orElse(null);

    Map<String, Object> blobAttrs = new LinkedHashMap<>();
    blobAttrs.put("sha512", digests.sha512());
    if (extraBlobAttributes != null) {
      extraBlobAttributes.forEach((key, value) -> {
        if (value != null) blobAttrs.put(key, value);
      });
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
          contentType,
          createdBy,
          createdByIp,
          now,
          now,
          blobAttrs);
      persistedBlob = assetDao.insertBlobOrFindExisting(blobRecord);
      blobId = persistedBlob.id();
      if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()
          && !blobAttrs.equals(persistedBlob.attributes())) {
        assetDao.updateBlobAttributes(blobId, blobAttrs);
        persistedBlob = persistedBlob.withAttributes(blobAttrs);
      }
    } else {
      blobId = persistedBlob.id();
      if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()) {
        assetDao.updateBlobAttributes(blobId, blobAttrs);
        persistedBlob = persistedBlob.withAttributes(blobAttrs);
      }
    }

    Long componentId = componentId(runtime, path, now, componentBinding);
    Map<String, Object> assetAttrs = Map.of("path", path);
    AssetRecord persistedAsset;
    boolean created;
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blobId, contentType,
          digests.size(), now, assetAttrs);
      created = false;
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blobId,
          runtime.format(),
          path,
          PersistenceHashes.pathHash(path),
          fileName(path),
          runtime.format().id(),
          contentType,
          digests.size(),
          null,
          now,
          assetAttrs);
      OptionalLong insertedAssetId = assetDao.tryInsertAsset(record);
      if (insertedAssetId.isPresent()) {
        long assetId = insertedAssetId.getAsLong();
        persistedAsset = new AssetRecord(
            assetId, record.repositoryId(), record.componentId(), record.assetBlobId(),
            record.format(), record.path(), record.pathHash(), record.name(), record.kind(),
            record.contentType(), record.size(), record.lastDownloadedAt(), record.lastUpdatedAt(),
            record.attributes());
        created = true;
      } else {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path)
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent raw asset insert won but row is not visible for " + runtime.name() + "/" + path));
        previousComponentId = prior.componentId();
        previousBlob = prior.assetBlobId() == null ? null : assetDao.findBlobById(prior.assetBlobId()).orElse(null);
        persistedAsset = updateExistingAsset(prior, componentId, blobId, contentType,
            digests.size(), now, assetAttrs);
        created = false;
      }
    }

    if (previousBlob != null && previousBlob.id() != null && previousBlob.id() != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlob.id(), "asset replaced");
    }
    browseNodeDao.upsertPathAncestors(runtime.id(), path, persistedAsset.id(), componentId);
    if (previousComponentId != null && !previousComponentId.equals(componentId)) {
      componentDao.deleteIfNoAssets(previousComponentId);
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), path);
    return new Stored(persistedAsset, persistedBlob, digests, created, responseFile);
  }

  private AssetRecord updateExistingAsset(
      AssetRecord prior,
      Long componentId,
      long blobId,
      String contentType,
      long size,
      Instant lastUpdatedAt,
      Map<String, Object> attributes) {
    assetDao.updateAssetBlobBindingAndMetadata(
        prior.id(), componentId, blobId, prior.format().id(), contentType, size, lastUpdatedAt, attributes);
    return new AssetRecord(
        prior.id(), prior.repositoryId(), componentId, blobId, prior.format(), prior.path(),
        prior.pathHash(), prior.name(), prior.format().id(), contentType, size, prior.lastDownloadedAt(),
        lastUpdatedAt, attributes);
  }

  private long upsertComponent(RepositoryRuntime runtime, String path, Instant now) {
    String group = rawGroup(path);
    byte[] coordinate = PersistenceHashes.componentCoordinateHash(group, path, null);
    ComponentRecord rec = new ComponentRecord(
        null,
        runtime.id(),
        runtime.format(),
        group,
        path,
        null,
        runtime.format().id(),
        coordinate,
        Map.of("path", path),
        now);
    return componentDao.upsertReturningId(rec);
  }

  private Long componentId(
      RepositoryRuntime runtime,
      String path,
      Instant now,
      ComponentBinding binding) {
    return switch (binding.mode()) {
      case PER_ASSET -> upsertComponent(runtime, path, now);
      case NONE -> null;
      case EXPLICIT -> {
        ComponentRecord component = binding.component();
        if (component.repositoryId() != runtime.id() || component.format() != runtime.format()) {
          throw new IllegalArgumentException(
              "Explicit component must belong to the raw asset repository and format");
        }
        yield componentDao.upsertReturningId(component);
      }
    };
  }

  private enum ComponentMode {
    PER_ASSET,
    NONE,
    EXPLICIT
  }

  private record ComponentBinding(ComponentMode mode, ComponentRecord component) {
    private static ComponentBinding perAsset() {
      return new ComponentBinding(ComponentMode.PER_ASSET, null);
    }

    private static ComponentBinding none() {
      return new ComponentBinding(ComponentMode.NONE, null);
    }

    private static ComponentBinding explicit(ComponentRecord component) {
      if (component == null) {
        throw new IllegalArgumentException("Explicit component is required");
      }
      return new ComponentBinding(ComponentMode.EXPLICIT, component);
    }
  }

  private record DigestedUpload(
      BlobReference reference,
      Digests digests,
      Path tempFile,
      boolean uploaded) {
  }

  private DigestedUpload uploadWithDigests(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      Map<String, ?> extraBlobAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-raw-", ".tmp");
      MessageDigest md5 = digest("MD5");
      MessageDigest sha1 = digest("SHA-1");
      MessageDigest sha256 = digest("SHA-256");
      MessageDigest sha512 = digest("SHA-512");
      long size;
      try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
        size = streamWithDigests(UpstreamBodyReadException.wrap(body), out, md5, sha1, sha256, sha512);
      }
      String md5Hex = hex(md5.digest());
      String sha1Hex = hex(sha1.digest());
      String sha256Hex = hex(sha256.digest());
      String sha512Hex = hex(sha512.digest());
      Digests digests = new Digests(md5Hex, sha1Hex, sha256Hex, sha512Hex, size);
      Optional<AssetBlobRecord> reusable = precheckedReusableBlob(blobStoreId, sha256Hex, size, extraBlobAttributes);
      if (reusable.isPresent()) {
        AssetBlobRecord blob = reusable.get();
        BlobReference ref = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
        return new DigestedUpload(ref, digests, tmp, false);
      }
      BlobReference ref = storage.putFile(runtime.name(), path, tmp, sha256Hex);
      return new DigestedUpload(ref, digests, tmp, true);
    } catch (RuntimeException | IOException e) {
      TempBlobFiles.deleteQuietly(tmp);
      if (e instanceof IOException io) {
        throw new IllegalStateException("Failed to buffer raw content for " + path, io);
      }
      throw (RuntimeException) e;
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "raw metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "raw metadata reuse");
  }

  private Optional<AssetBlobRecord> reusableBlob(
      long blobStoreId,
      String sha256,
      long size,
      Map<String, ?> extraBlobAttributes) {
    if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()) {
      return assetDao.recoverDeletedBlobBySha256(blobStoreId, sha256, size);
    }
    return assetDao.findReusableBlobBySha256(blobStoreId, sha256, size);
  }

  private Optional<AssetBlobRecord> precheckedReusableBlob(
      long blobStoreId,
      String sha256,
      long size,
      Map<String, ?> extraBlobAttributes) {
    if (extraBlobAttributes != null && !extraBlobAttributes.isEmpty()) {
      return Optional.empty();
    }
    return assetDao.findReusableBlobBySha256(blobStoreId, sha256, size);
  }

  private void deleteOne(BlobStorage storage, AssetRecord asset) {
    Long blobId = asset.assetBlobId();
    browseNodeDao.deleteByAssetId(asset.id());
    assetDao.deleteAssetById(asset.id());
    if (blobId != null) {
      assetDao.markBlobDeletedIfUnreferenced(blobId, "asset unlinked");
    }
    assetMetadataCache.evictAfterCommit(asset.repositoryId(), asset.path());
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

  private static String rawGroup(String path) {
    int slash = path.lastIndexOf('/');
    if (slash <= 0) return "/";
    return "/" + path.substring(0, slash);
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
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
