package com.github.klboke.kkrepo.server.pypi;

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
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class PypiAssetWriter {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final PypiGroupSimpleIndexCache simpleIndexCache;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final TransientTransactionRetry transactionRetry;

  PypiAssetWriter(AssetDao assetDao, ComponentDao componentDao, BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache,
      PypiGroupSimpleIndexCache simpleIndexCache,
      GroupMemberAssetCache groupMemberAssetCache) {
    this(assetDao, componentDao, browseNodeDao, assetMetadataCache, simpleIndexCache,
        groupMemberAssetCache, null);
  }

  @Autowired
  PypiAssetWriter(AssetDao assetDao, ComponentDao componentDao, BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache,
      PypiGroupSimpleIndexCache simpleIndexCache,
      GroupMemberAssetCache groupMemberAssetCache,
      TransientTransactionRetry transactionRetry) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.browseNodeDao = browseNodeDao;
    this.assetMetadataCache = assetMetadataCache;
    this.simpleIndexCache = simpleIndexCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
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

  record Digests(String md5, String sha1, String sha256, String sha512, long size) {}

  Stored writeBytes(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      byte[] body,
      String contentType,
      String kind,
      PackageCoordinate coordinate,
      Map<String, Object> assetAttributes,
      String createdBy,
      String createdByIp) {
    return write(runtime, storage, blobStoreId, path, new ByteArrayInputStream(body), contentType,
        kind, coordinate, assetAttributes, Map.of(), createdBy, createdByIp, false);
  }

  Stored writeSimpleIndexCache(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      byte[] body,
      String kind,
      String projectName,
      Map<String, Object> assetAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile) {
    Map<String, Object> attrs = new LinkedHashMap<>(assetAttributes == null ? Map.of() : assetAttributes);
    if (projectName != null && !projectName.isBlank()) {
      attrs.put("name", PypiPaths.normalizeName(projectName));
    }
    return write(runtime, storage, blobStoreId, path, new ByteArrayInputStream(body), "text/html",
        kind, null, attrs, Map.of(), createdBy, createdByIp, keepResponseFile,
        false, false, false);
  }

  Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentType,
      String kind,
      PackageCoordinate coordinate,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp) {
    return write(runtime, storage, blobStoreId, path, body, contentType, kind, coordinate,
        assetAttributes, extraBlobAttributes, createdBy, createdByIp, false);
  }

  Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentType,
      String kind,
      PackageCoordinate coordinate,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, path, body, contentType, kind, coordinate,
        assetAttributes, extraBlobAttributes, createdBy, createdByIp, keepResponseFile,
        true, true, true);
  }

  private Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentType,
      String kind,
      PackageCoordinate coordinate,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile,
      boolean notifySimpleIndexCaches,
      boolean notifyGroupMemberCache,
      boolean indexBrowseNode) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, extraBlobAttributes);
    try {
      Stored stored = executePersist(
          "Persist PyPI asset " + runtime.name() + "/" + path,
          () -> persistAsset(runtime, blobStoreId, path, upload, contentType, kind,
              coordinate, assetAttributes, extraBlobAttributes, createdBy, createdByIp,
              keepResponseFile ? upload.tempFile() : null, notifySimpleIndexCaches,
              notifyGroupMemberCache, indexBrowseNode));
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

  private Stored persistAsset(
      RepositoryRuntime runtime,
      long blobStoreId,
      String path,
      DigestedUpload upload,
      String contentTypeHint,
      String kind,
      PackageCoordinate coordinate,
      Map<String, Object> assetAttributes,
      Map<String, String> extraBlobAttributes,
      String createdBy,
      String createdByIp,
      Path responseFile,
      boolean notifySimpleIndexCaches,
      boolean notifyGroupMemberCache,
      boolean indexBrowseNode) {
    Instant now = Instant.now();
    String contentType = contentTypeHint == null || contentTypeHint.isBlank()
        ? contentTypeForPath(path)
        : contentTypeHint;
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);

    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);

    Map<String, Object> blobAttrs = new LinkedHashMap<>();
    blobAttrs.put("sha512", digests.sha512());
    if (extraBlobAttributes != null) {
      extraBlobAttributes.forEach((k, v) -> { if (v != null) blobAttrs.put(k, v); });
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

    Long componentId = coordinate == null ? null : upsertComponent(runtime, coordinate, now);
    Map<String, Object> attrs = assetAttributes == null
        ? Map.of()
        : new LinkedHashMap<>(assetAttributes);
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
          RepositoryFormat.PYPI,
          path,
          PersistenceHashes.pathHash(path),
          PypiPaths.fileName(path),
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
      } else {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path)
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent PyPI asset insert won but row is not visible for " + runtime.name() + "/" + path));
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
            digests.size(), now, attrs);
        created = false;
      }
    }

    if (previousBlobId != null && previousBlobId != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlobId, "asset replaced");
    }
    if (indexBrowseNode) {
      browseNodeDao.upsertPathAncestors(runtime.id(), path, persistedAsset.id(), componentId);
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), path);
    if (notifySimpleIndexCaches && simpleIndexCache != null) {
      invalidateSimpleIndexCaches(runtime.id(), path, kind, coordinate, attrs);
    }
    if (notifyGroupMemberCache && groupMemberAssetCache != null) {
      groupMemberAssetCache.invalidateMemberAfterCommit(runtime.id());
    }
    return new Stored(persistedAsset, persistedBlob, digests, created, responseFile);
  }

  private void invalidateSimpleIndexCaches(
      long repositoryId,
      String path,
      String kind,
      PackageCoordinate coordinate,
      Map<String, Object> attrs) {
    if ("root-index".equals(kind)) {
      simpleIndexCache.invalidateMemberRootAfterCommit(repositoryId);
      return;
    }
    String project = projectForInvalidation(path, coordinate, attrs);
    if (project == null || project.isBlank()) {
      simpleIndexCache.invalidateMemberAfterCommit(repositoryId);
    } else {
      simpleIndexCache.invalidateMemberProjectAfterCommit(repositoryId, project);
    }
  }

  private static String projectForInvalidation(
      String path,
      PackageCoordinate coordinate,
      Map<String, Object> attrs) {
    if (coordinate != null && coordinate.normalizedName() != null && !coordinate.normalizedName().isBlank()) {
      return coordinate.normalizedName();
    }
    Object normalized = attrs == null ? null : attrs.get("normalizedName");
    if (normalized != null && !normalized.toString().isBlank()) {
      return normalized.toString();
    }
    Object name = attrs == null ? null : attrs.get("name");
    if (name != null && !name.toString().isBlank()) {
      return PypiPaths.normalizeName(name.toString());
    }
    String fromPackagePath = PypiPaths.packageNameFromPath(path);
    if (!fromPackagePath.isBlank()) {
      return fromPackagePath;
    }
    if (path != null && path.startsWith(PypiPaths.INDEX_PREFIX)) {
      String rest = path.substring(PypiPaths.INDEX_PREFIX.length());
      int slash = rest.indexOf('/');
      return slash < 0 ? rest : rest.substring(0, slash);
    }
    return null;
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

  private long upsertComponent(RepositoryRuntime runtime, PackageCoordinate coordinate, Instant now) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("name", coordinate.originalName());
    attrs.put("version", coordinate.version());
    if (coordinate.summary() != null && !coordinate.summary().isBlank()) {
      attrs.put("summary", coordinate.summary());
    }
    ComponentRecord rec = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.PYPI,
        null,
        coordinate.normalizedName(),
        coordinate.version(),
        "package",
        PersistenceHashes.componentCoordinateHash(null, coordinate.normalizedName(), coordinate.version()),
        attrs,
        now);
    return componentDao.upsertReturningId(rec);
  }

  private record DigestedUpload(
      BlobReference reference,
      Digests digests,
      Path tempFile,
      boolean uploaded) {}

  private DigestedUpload uploadWithDigests(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      Map<String, String> extraBlobAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-pypi-", ".tmp");
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
        throw new IllegalStateException("Failed to buffer PyPI upload for " + path, io);
      }
      throw (RuntimeException) e;
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "PyPI metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "PyPI metadata reuse");
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

  private long streamWithDigests(InputStream in, OutputStream out, MessageDigest... digests) throws IOException {
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

  static String contentTypeForPath(String path) {
    String lower = path == null ? "" : path.toLowerCase();
    if (lower.endsWith(".html") || lower.startsWith(PypiPaths.INDEX_PREFIX)) return "text/html";
    if (lower.endsWith(".asc")) return "application/pgp-signature";
    if (lower.endsWith(".whl") || lower.endsWith(".zip") || lower.endsWith(".egg")) return "application/zip";
    if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return "application/gzip";
    if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz")) return "application/x-bzip2";
    return "application/octet-stream";
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

  record PackageCoordinate(String originalName, String normalizedName, String version, String summary) {}
}
