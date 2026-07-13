package com.github.klboke.kkrepo.server.helm;

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
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
import com.github.klboke.kkrepo.protocol.helm.HelmChartMetadata;
import com.github.klboke.kkrepo.protocol.helm.HelmChartPackageParser;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class HelmAssetWriter {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;
  private final HelmChartPackageParser chartParser = new HelmChartPackageParser();

  HelmAssetWriter(AssetDao assetDao, ComponentDao componentDao, BrowseNodeDao browseNodeDao,
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
      HelmAssetKind kind,
      HelmChartMetadata metadata,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp) {
    return write(runtime, storage, blobStoreId, path, body, contentTypeHint, kind, metadata,
        assetAttributes, extraBlobAttributes, createdBy, createdByIp, false);
  }

  Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      HelmAssetKind kind,
      HelmChartMetadata metadata,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, kind, extraBlobAttributes);
    try {
      HelmChartMetadata effectiveMetadata = metadata == null
          ? metadataFromBufferedUpload(kind, upload.tempFile())
          : metadata;
      Stored stored = executePersist(
          "Persist Helm asset " + runtime.name() + "/" + path,
          () -> persist(runtime, blobStoreId, path, upload, contentTypeHint, kind,
              effectiveMetadata, assetAttributes, extraBlobAttributes, createdBy, createdByIp,
              keepResponseFile ? upload.tempFile() : null));
      cleanupUnusedUploadedBlob(storage, blobStoreId, upload, stored.blob());
      if (!keepResponseFile) {
        deleteTemp(upload.tempFile());
      }
      return stored;
    } catch (RuntimeException e) {
      cleanupUploadedBlob(storage, blobStoreId, upload);
      deleteTemp(upload.tempFile());
      throw e;
    }
  }

  Stored writeBytes(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      byte[] body,
      String contentTypeHint,
      HelmAssetKind kind,
      HelmChartMetadata metadata,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp) {
    return write(runtime, storage, blobStoreId, path, new java.io.ByteArrayInputStream(body),
        contentTypeHint, kind, metadata, assetAttributes, extraBlobAttributes, createdBy, createdByIp);
  }

  Stored writeBytes(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      byte[] body,
      String contentTypeHint,
      HelmAssetKind kind,
      HelmChartMetadata metadata,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, path, new java.io.ByteArrayInputStream(body),
        contentTypeHint, kind, metadata, assetAttributes, extraBlobAttributes, createdBy, createdByIp,
        keepResponseFile);
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
      long blobStoreId,
      String path,
      DigestedUpload upload,
      String contentTypeHint,
      HelmAssetKind kind,
      HelmChartMetadata metadata,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      Path responseFile) {
    Instant now = Instant.now();
    String contentType = contentTypeHint == null || contentTypeHint.isBlank()
        ? kind.contentType()
        : contentTypeHint;
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);

    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);

    Map<String, Object> blobAttrs = new LinkedHashMap<>();
    blobAttrs.put("sha512", digests.sha512());
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
          contentType,
          createdBy,
          createdByIp,
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

    Long componentId = metadata == null || kind == HelmAssetKind.INDEX
        ? null
        : upsertComponent(runtime, metadata, now);
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("asset_kind", kind.name());
    if (metadata != null) attrs.putAll(metadata.attributes());
    if (assetAttributes != null) attrs.putAll(assetAttributes);

    AssetRecord persistedAsset;
    boolean created;
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blobId, kind.name(), contentType,
          digests.size(), now, attrs);
      created = false;
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blobId,
          RepositoryFormat.HELM,
          path,
          PersistenceHashes.pathHash(path),
          fileName(path),
          kind.name(),
          contentType,
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
        created = true;
      } else {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path)
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent Helm asset insert won but row is not visible for " + runtime.name() + "/" + path));
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, kind.name(), contentType,
            digests.size(), now, attrs);
        created = false;
      }
    }

    if (previousBlobId != null && previousBlobId != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlobId, "asset replaced");
    }
    browseNodeDao.upsertPathAncestors(runtime.id(), path, persistedAsset.id(), componentId);
    assetMetadataCache.evictAfterCommit(runtime.id(), path);
    return new Stored(persistedAsset, persistedBlob, digests, created, responseFile);
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
    assetDao.updateAssetBlobBindingAndMetadata(
        prior.id(), componentId, blobId, kind, contentType, size, lastUpdatedAt, attributes);
    return new AssetRecord(
        prior.id(), prior.repositoryId(), componentId, blobId, prior.format(), prior.path(),
        prior.pathHash(), prior.name(), kind, contentType, size, prior.lastDownloadedAt(),
        lastUpdatedAt, attributes);
  }

  private long upsertComponent(RepositoryRuntime runtime, HelmChartMetadata metadata, Instant now) {
    byte[] coordinate = PersistenceHashes.componentCoordinateHash(null, metadata.name(), metadata.version());
    ComponentRecord rec = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.HELM,
        null,
        metadata.name(),
        metadata.version(),
        "helm-chart",
        coordinate,
        metadata.attributes(),
        now);
    return componentDao.upsertReturningId(rec);
  }

  private HelmChartMetadata metadataFromBufferedUpload(HelmAssetKind kind, Path tempFile) {
    try {
      if (kind == HelmAssetKind.PACKAGE) {
        try (InputStream input = Files.newInputStream(tempFile)) {
          return chartParser.parse(input);
        }
      }
      if (kind == HelmAssetKind.PROVENANCE) {
        return parseProvenance(tempFile);
      }
      return null;
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to parse Helm metadata", e);
    }
  }

  private HelmChartMetadata parseProvenance(Path tempFile) throws IOException {
    Map<String, Object> values = new LinkedHashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(tempFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("-----BEGIN PGP SIGNATURE-----")) break;
        int idx = line.indexOf(':');
        if (idx <= 0) continue;
        String key = line.substring(0, idx).trim();
        String value = line.substring(idx + 1).trim();
        if (!key.isBlank() && !value.isBlank()) values.put(key, value);
      }
    }
    HelmChartMetadata metadata = HelmChartMetadata.fromYamlMap(values);
    metadata.requireNameAndVersion();
    return metadata;
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
      HelmAssetKind kind,
      Map<String, String> extraBlobAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-helm-", ".tmp");
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
      deleteTemp(tmp);
      if (e instanceof IOException io) {
        throw new IllegalStateException("Failed to buffer Helm " + kind + " content for " + path, io);
      }
      throw (RuntimeException) e;
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "Helm metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "Helm metadata reuse");
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

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static void deleteTemp(Path path) {
    if (path == null) return;
    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
  }
}
