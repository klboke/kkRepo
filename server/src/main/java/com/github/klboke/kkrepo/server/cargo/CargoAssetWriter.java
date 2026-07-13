package com.github.klboke.kkrepo.server.cargo;

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
import com.github.klboke.kkrepo.protocol.cargo.CargoCrateName;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.io.ByteArrayInputStream;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
class CargoAssetWriter {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;

  CargoAssetWriter(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache,
      TransientTransactionRetry transactionRetry) {
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

  Stored writeHostedCrate(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      CargoPackageMetadata metadata,
      Path crateFile,
      String createdBy,
      String createdByIp) {
    return writeCrate(runtime, storage, blobStoreId, metadata, null, cratePath(metadata.name(), metadata.version()),
        fileInput(crateFile), "application/x-tar", Map.of(), createdBy, createdByIp, false, false, null);
  }

  Stored writeProxyCrate(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      CargoPackageMetadata metadata,
      Map<String, Object> indexEntry,
      String path,
      InputStream body,
      String contentType,
      Map<String, String> remoteAttributes,
      String expectedSha256) {
    return writeCrate(runtime, storage, blobStoreId, metadata, indexEntry, path, body, contentType,
        remoteAttributes, "proxy", runtime.proxyRemoteUrl(), true, true, expectedSha256);
  }

  Stored writeMetadata(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      byte[] body,
      String contentType,
      String kind,
      Map<String, Object> attributes,
      Map<String, String> remoteAttributes,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, path, new ByteArrayInputStream(body), contentType,
        kind, null, attributes, remoteAttributes, "proxy", runtime.proxyRemoteUrl(), keepResponseFile, true, null);
  }

  private Stored writeCrate(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      CargoPackageMetadata metadata,
      Map<String, Object> indexEntry,
      String path,
      InputStream body,
      String contentType,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile,
      boolean allowReplace,
      String expectedSha256) {
    Map<String, Object> assetAttrs = new LinkedHashMap<>();
    assetAttrs.put("crateName", metadata.name());
    assetAttrs.put("normalizedName", metadata.normalizedName());
    assetAttrs.put("version", metadata.version());
    assetAttrs.put("versionKey", metadata.versionKey());
    if (metadata.description() != null) {
      assetAttrs.put("description", metadata.description());
    }
    putText(assetAttrs, "homepage", metadata.publishJson().get("homepage"));
    putText(assetAttrs, "documentation", metadata.publishJson().get("documentation"));
    putText(assetAttrs, "repository", metadata.publishJson().get("repository"));
    return write(runtime, storage, blobStoreId, path, body, contentType, "crate",
        new CargoCoordinate(metadata, indexEntry), assetAttrs, remoteAttributes,
        createdBy, createdByIp, keepResponseFile, allowReplace, expectedSha256);
  }

  private Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentTypeHint,
      String kind,
      CargoCoordinate coordinate,
      Map<String, Object> assetAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile,
      boolean allowReplace,
      String expectedSha256) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, remoteAttributes);
    try {
      validateExpectedSha256(expectedSha256, upload.digests().sha256(), path);
      Stored stored = executePersist("Persist Cargo asset " + runtime.name() + "/" + path,
          () -> persist(runtime, blobStoreId, path, upload, contentTypeHint, kind, coordinate,
              assetAttributes, remoteAttributes, createdBy, createdByIp,
              keepResponseFile ? upload.tempFile() : null, allowReplace));
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

  private Stored persist(
      RepositoryRuntime runtime,
      long blobStoreId,
      String path,
      DigestedUpload upload,
      String contentTypeHint,
      String kind,
      CargoCoordinate coordinate,
      Map<String, Object> assetAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      Path responseFile,
      boolean allowReplace) {
    Instant now = Instant.now();
    String contentType = contentTypeHint == null || contentTypeHint.isBlank()
        ? contentTypeForPath(path)
        : contentTypeHint;
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);

    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    if (existing.isPresent() && !allowReplace) {
      throw new CargoExceptions.WritePolicyDenied("Cargo asset already exists: " + path);
    }
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);

    Map<String, Object> blobAttrs = new LinkedHashMap<>();
    blobAttrs.put("sha512", digests.sha512());
    if (remoteAttributes != null) {
      remoteAttributes.forEach((key, value) -> { if (value != null) blobAttrs.put(key, value); });
    }
    AssetBlobRecord persistedBlob = reusableBlob(blobStoreId, digests.sha256(), digests.size(), remoteAttributes)
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
      if (remoteAttributes != null && !remoteAttributes.isEmpty()) {
        assetDao.updateBlobAttributes(blobId, blobAttrs);
        persistedBlob = persistedBlob.withAttributes(blobAttrs);
      }
    }

    Long componentId = coordinate == null
        ? null
        : persistComponent(runtime, coordinate.withChecksum(digests.sha256(), path), now, allowReplace);
    Map<String, Object> attrs = assetAttributes == null ? Map.of() : new LinkedHashMap<>(assetAttributes);
    if (coordinate != null) {
      attrs.put("cratePath", path);
    }
    AssetRecord persistedAsset;
    boolean created;
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
          digests.size(), now, attrs);
      created = false;
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blobId,
          RepositoryFormat.CARGO,
          path,
          PersistenceHashes.pathHash(path),
          fileName(path),
          kind,
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
      } else if (allowReplace) {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path)
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent Cargo asset insert won but row is not visible for " + runtime.name() + "/" + path));
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
            digests.size(), now, attrs);
        created = false;
      } else {
        throw new CargoExceptions.WritePolicyDenied("Cargo asset already exists: " + path);
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
    Long effectiveComponentId = componentId != null ? componentId : prior.componentId();
    assetDao.updateAssetBlobBindingAndMetadata(
        prior.id(), effectiveComponentId, blobId, kind, contentType, size, lastUpdatedAt, attributes);
    return new AssetRecord(
        prior.id(), prior.repositoryId(), effectiveComponentId, blobId, prior.format(), prior.path(),
        prior.pathHash(), prior.name(), kind, contentType, size, prior.lastDownloadedAt(),
        lastUpdatedAt, attributes);
  }

  private long persistComponent(
      RepositoryRuntime runtime,
      CargoCoordinate coordinate,
      Instant now,
      boolean allowReplace) {
    String componentName = coordinate.metadata().normalizedName();
    String versionKey = coordinate.metadata().versionKey();
    Optional<ComponentRecord> existing = componentDao.findByNameAndVersion(runtime.id(), componentName, versionKey);
    if (existing.isPresent()) {
      if (!allowReplace) {
        throw new CargoExceptions.WritePolicyDenied(
            "Cargo crate version already exists: " + coordinate.metadata().name() + " " + coordinate.metadata().version());
      }
      componentDao.updateAttributes(existing.get().id(), coordinate.attributes(), now);
      return existing.get().id();
    }
    ComponentRecord record = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.CARGO,
        null,
        componentName,
        versionKey,
        "crate",
        PersistenceHashes.componentCoordinateHash(null, componentName, versionKey),
        coordinate.attributes(),
        now);
    try {
      return componentDao.insert(record);
    } catch (DuplicateKeyException e) {
      if (!allowReplace) {
        throw new CargoExceptions.WritePolicyDenied(
            "Cargo crate version already exists: " + coordinate.metadata().name() + " " + coordinate.metadata().version());
      }
      ComponentRecord concurrent = componentDao.findByNameAndVersion(runtime.id(), componentName, versionKey)
          .orElseThrow(() -> e);
      componentDao.updateAttributes(concurrent.id(), coordinate.attributes(), now);
      return concurrent.id();
    }
  }

  private DigestedUpload uploadWithDigests(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      Map<String, String> remoteAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-cargo-", ".tmp");
      MessageDigest md5 = digest("MD5");
      MessageDigest sha1 = digest("SHA-1");
      MessageDigest sha256 = digest("SHA-256");
      MessageDigest sha512 = digest("SHA-512");
      long size;
      try (InputStream in = UpstreamBodyReadException.wrap(body);
          OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
        size = streamWithDigests(in, out, md5, sha1, sha256, sha512);
      }
      Digests digests = new Digests(hex(md5.digest()), hex(sha1.digest()),
          hex(sha256.digest()), hex(sha512.digest()), size);
      Optional<AssetBlobRecord> reusable = reusableBlob(blobStoreId, digests.sha256(), size, remoteAttributes);
      if (reusable.isPresent()) {
        AssetBlobRecord blob = reusable.get();
        BlobReference ref = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
        return new DigestedUpload(ref, digests, tmp, false);
      }
      BlobReference ref = storage.putFile(runtime.name(), path, tmp, digests.sha256());
      return new DigestedUpload(ref, digests, tmp, true);
    } catch (RuntimeException | IOException e) {
      TempBlobFiles.deleteQuietly(tmp);
      if (e instanceof IOException io) {
        throw new IllegalStateException("Failed to buffer Cargo upload for " + path, io);
      }
      throw (RuntimeException) e;
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "Cargo metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "Cargo metadata reuse");
  }

  private Optional<AssetBlobRecord> reusableBlob(
      long blobStoreId,
      String sha256,
      long size,
      Map<String, String> remoteAttributes) {
    if (remoteAttributes != null && !remoteAttributes.isEmpty()) {
      return Optional.empty();
    }
    return assetDao.findReusableBlobBySha256(blobStoreId, sha256, size);
  }

  private long streamWithDigests(InputStream in, OutputStream out, MessageDigest... digests) throws IOException {
    byte[] buf = new byte[TempBlobFiles.responseBufferSize()];
    long total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      for (MessageDigest d : digests) {
        d.update(buf, 0, n);
      }
      out.write(buf, 0, n);
      total += n;
    }
    return total;
  }

  private static InputStream fileInput(Path file) {
    try {
      return Files.newInputStream(file);
    } catch (IOException e) {
      throw new CargoExceptions.BadRequestException("Failed reading staged Cargo crate", e);
    }
  }

  static String cratePath(String crateName, String version) {
    String lower = CargoCrateName.parse(crateName).lower();
    String safeVersion = CargoVersions.requireVersion(version);
    return "crates/" + lower + "/" + safeVersion + "/" + lower + "-" + safeVersion + ".crate";
  }

  static String contentTypeForPath(String path) {
    if (path != null && path.endsWith(".crate")) {
      return "application/x-tar";
    }
    if (path != null && path.endsWith(".json")) {
      return "application/json";
    }
    return "text/plain";
  }

  private static void validateExpectedSha256(String expectedSha256, String actualSha256, String path) {
    String normalized = normalizeExpectedSha256(expectedSha256, path);
    if (normalized == null) {
      return;
    }
    if (!normalized.equals(actualSha256)) {
      throw new CargoExceptions.BadUpstreamException("Cargo crate checksum mismatch for " + path);
    }
  }

  private static String normalizeExpectedSha256(String value, String path) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.length() != 64 || !normalized.chars().allMatch(CargoAssetWriter::isHexDigit)) {
      throw new CargoExceptions.BadUpstreamException("Cargo index cksum is invalid for " + path);
    }
    return normalized;
  }

  private static boolean isHexDigit(int ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
  }

  private static String fileName(String path) {
    int slash = path == null ? -1 : path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " digest is not available", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }

  private record DigestedUpload(BlobReference reference, Digests digests, Path tempFile, boolean uploaded) {
  }

  private record CargoCoordinate(
      CargoPackageMetadata metadata,
      Map<String, Object> indexEntry) {

    CargoCoordinate withChecksum(String checksum, String cratePath) {
      Map<String, Object> entry = indexEntry == null
          ? metadata.indexEntry(checksum, false)
          : new LinkedHashMap<>(indexEntry);
      entry.put("cksum", checksum);
      Map<String, Object> attrs = attributes(entry, cratePath);
      return new CargoCoordinate(metadata, attrs);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> attributes() {
      if (indexEntry != null && indexEntry.containsKey("indexEntry")) {
        return indexEntry;
      }
      return (Map<String, Object>) indexEntry;
    }

    private Map<String, Object> attributes(Map<String, Object> entry, String cratePath) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("crateName", metadata.name());
      attrs.put("normalizedName", metadata.normalizedName());
      attrs.put("version", metadata.version());
      attrs.put("versionKey", metadata.versionKey());
      attrs.put("cratePath", cratePath);
      if (metadata.description() != null) {
        attrs.put("description", metadata.description());
      }
      putText(attrs, "homepage", metadata.publishJson().get("homepage"));
      putText(attrs, "documentation", metadata.publishJson().get("documentation"));
      putText(attrs, "repository", metadata.publishJson().get("repository"));
      attrs.put("indexEntry", entry);
      return attrs;
    }
  }

  private static void putText(Map<String, Object> target, String key, Object value) {
    if (value == null) {
      return;
    }
    String text = String.valueOf(value).trim();
    if (!text.isBlank()) {
      target.put(key, text);
    }
  }
}
