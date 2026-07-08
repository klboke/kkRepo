package com.github.klboke.kkrepo.server.pub;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
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
class PubAssetWriter {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final TransientTransactionRetry transactionRetry;

  PubAssetWriter(
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

  record Digests(String md5, String sha1, String sha256, String sha512, long size) {
  }

  record StagedArchive(BlobReference reference, Digests digests, PubPackageMetadata metadata) {
  }

  record Stored(AssetRecord asset, AssetBlobRecord blob, Digests digests, boolean created, Path responseFile) {
    InputStream openBody() {
      return TempBlobFiles.openDeleteOnClose(responseFile);
    }

    void discardBody() {
      TempBlobFiles.deleteQuietly(responseFile);
    }
  }

  StagedArchive stageArchive(
      RepositoryRuntime runtime,
      BlobStorage storage,
      String sessionId,
      InputStream body,
      String expectedPackageName,
      String expectedVersion) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-pub-upload-", ".tar.gz");
      Digests digests = copyWithDigests(body, tmp);
      PubPackageMetadata metadata = PubArchiveInspector.inspect(tmp);
      validateExpectedCoordinate(metadata, expectedPackageName, expectedVersion);
      String path = "pub/uploads/" + sessionId + ".tar.gz";
      BlobReference ref = storage.putFile(runtime.name(), path, tmp, digests.sha256());
      return new StagedArchive(ref, digests, metadata);
    } catch (RuntimeException | IOException e) {
      if (e instanceof IOException io) {
        throw new PubExceptions.BadRequestException("Failed reading Pub archive upload", io);
      }
      throw (RuntimeException) e;
    } finally {
      TempBlobFiles.deleteQuietly(tmp);
    }
  }

  Stored writeArchive(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      InputStream body,
      String expectedPackageName,
      String expectedVersion,
      String expectedSha256,
      Map<String, Object> archiveAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      boolean allowReplace,
      boolean keepResponseFile) {
    return writeArchive(
        runtime,
        storage,
        blobStoreId,
        body,
        expectedPackageName,
        expectedVersion,
        expectedSha256,
        archiveAttributes,
        remoteAttributes,
        createdBy,
        createdByIp,
        null,
        null,
        allowReplace,
        keepResponseFile);
  }

  Stored writeArchive(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      InputStream body,
      String expectedPackageName,
      String expectedVersion,
      String expectedSha256,
      Map<String, Object> archiveAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      Long expectedSize,
      Instant publishedAt,
      boolean allowReplace,
      boolean keepResponseFile) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-pub-archive-", ".tar.gz");
      Digests digests = copyWithDigests(body, tmp);
      validateExpectedSize(expectedSize, digests.size(), expectedPackageName, expectedVersion);
      validateExpectedSha256(expectedSha256, digests.sha256(), expectedPackageName, expectedVersion);
      PubPackageMetadata metadata = PubArchiveInspector.inspect(tmp);
      validateExpectedCoordinate(metadata, expectedPackageName, expectedVersion);
      Optional<AssetBlobRecord> reusable = reusableBlob(blobStoreId, digests.sha256(), digests.size(), remoteAttributes);
      BlobReference ref = reusable
          .map(blob -> BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
          .orElse(null);
      if (ref == null) {
        ref = storage.putFile(runtime.name(), PubPaths.archivePath(metadata.packageName(), metadata.version()),
            tmp, digests.sha256());
      }
      Stored stored = persistArchive(runtime, storage, blobStoreId, ref, digests, metadata,
          archiveAttributes, remoteAttributes, createdBy, createdByIp, keepResponseFile ? tmp : null,
          publishedAt, allowReplace);
      if (!keepResponseFile) {
        TempBlobFiles.deleteQuietly(tmp);
      }
      return stored;
    } catch (RuntimeException | IOException e) {
      TempBlobFiles.deleteQuietly(tmp);
      if (e instanceof IOException io) {
        throw new PubExceptions.BadRequestException("Failed reading Pub archive", io);
      }
      throw (RuntimeException) e;
    }
  }

  Stored persistStagedArchive(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      BlobReference reference,
      Digests digests,
      PubPackageMetadata metadata,
      Map<String, Object> archiveAttributes,
      String createdBy,
      String createdByIp) {
    return persistArchive(runtime, storage, blobStoreId, reference, digests, metadata,
        archiveAttributes, Map.of(), createdBy, createdByIp, null, null, false);
  }

  Stored writeMetadata(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      byte[] body,
      Map<String, Object> assetAttributes,
      Map<String, String> remoteAttributes,
      boolean keepResponseFile) {
    return writeAsset(runtime, storage, blobStoreId, path, new ByteArrayInputStream(body),
        PubContentTypes.JSON, "metadata", null, assetAttributes, null, remoteAttributes,
        "proxy", runtime.proxyRemoteUrl(), keepResponseFile, true);
  }

  private Stored persistArchive(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      BlobReference reference,
      Digests digests,
      PubPackageMetadata metadata,
      Map<String, Object> archiveAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      Path responseFile,
      Instant publishedAt,
      boolean allowReplace) {
    try {
      return executePersist("Persist Pub archive " + runtime.name() + "/"
              + PubPaths.archivePath(metadata.packageName(), metadata.version()),
          () -> persistArchiveInternal(runtime, blobStoreId, reference, digests, metadata,
              archiveAttributes, remoteAttributes, createdBy, createdByIp, responseFile, publishedAt, allowReplace));
    } catch (RuntimeException e) {
      BlobTransactionCleanup.deleteIfUnreferenced(
          assetDao, storage, blobStoreId, reference, "Pub archive metadata persist failure");
      throw e;
    }
  }

  private Stored persistArchiveInternal(
      RepositoryRuntime runtime,
      long blobStoreId,
      BlobReference reference,
      Digests digests,
      PubPackageMetadata metadata,
      Map<String, Object> extraAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      Path responseFile,
      Instant publishedAt,
      boolean allowReplace) {
    Instant effectivePublishedAt = publishedAt == null ? Instant.now() : publishedAt;
    String path = PubPaths.archivePath(metadata.packageName(), metadata.version());
    Map<String, Object> attrs = componentAttributes(metadata, digests, path, effectivePublishedAt, extraAttributes);
    return persist(runtime, blobStoreId, path, reference, digests, PubContentTypes.ARCHIVE, "archive",
        metadata, attrs, attrs, remoteAttributes, createdBy, createdByIp, responseFile, allowReplace);
  }

  private Stored writeAsset(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      InputStream body,
      String contentType,
      String kind,
      PubPackageMetadata coordinate,
      Map<String, Object> assetAttributes,
      Map<String, Object> componentAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      boolean keepResponseFile,
      boolean allowReplace) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, remoteAttributes);
    try {
      Stored stored = executePersist("Persist Pub asset " + runtime.name() + "/" + path,
          () -> persist(runtime, blobStoreId, path, upload.reference(), upload.digests(), contentType,
              kind, coordinate, assetAttributes, componentAttributes, remoteAttributes, createdBy, createdByIp,
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

  private Stored persist(
      RepositoryRuntime runtime,
      long blobStoreId,
      String path,
      BlobReference reference,
      Digests digests,
      String contentType,
      String kind,
      PubPackageMetadata coordinate,
      Map<String, Object> assetAttributes,
      Map<String, Object> componentAttributes,
      Map<String, String> remoteAttributes,
      String createdBy,
      String createdByIp,
      Path responseFile,
      boolean allowReplace) {
    Instant now = Instant.now();
    String blobRef = BlobReferenceCodec.format(reference);
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
          HashColumns.blobRefHash(blobRef),
          reference.objectKey(),
          HashColumns.objectKeyHash(reference.objectKey()),
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
        : persistComponent(runtime, coordinate, componentAttributes, now, allowReplace);
    Map<String, Object> attrs = assetAttributes == null ? Map.of() : new LinkedHashMap<>(assetAttributes);
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);
    AssetRecord persistedAsset;
    boolean created;
    if (existing.isPresent()) {
      if (!allowReplace) {
        throw new PubExceptions.WritePolicyDenied("Pub asset already exists: " + path);
      }
      persistedAsset = updateExistingAsset(existing.get(), componentId, blobId, kind, contentType,
          digests.size(), now, attrs);
      created = false;
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blobId,
          RepositoryFormat.PUB,
          path,
          HashColumns.pathHash(path),
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
                "Concurrent Pub asset insert won but row is not visible for " + runtime.name() + "/" + path));
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
            digests.size(), now, attrs);
        created = false;
      } else {
        throw new PubExceptions.WritePolicyDenied("Pub asset already exists: " + path);
      }
    }
    if (previousBlobId != null && previousBlobId != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlobId, "Pub asset replaced");
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
      PubPackageMetadata metadata,
      Map<String, Object> attributes,
      Instant now,
      boolean allowReplace) {
    Optional<ComponentRecord> existing =
        componentDao.findByNameAndVersion(runtime.id(), metadata.packageName(), metadata.version());
    Map<String, Object> effectiveAttributes = attributes == null ? Map.of() : attributes;
    if (existing.isPresent()) {
      if (!allowReplace) {
        throw new PubExceptions.WritePolicyDenied(
            "Pub package version already exists: " + metadata.packageName() + " " + metadata.version());
      }
      componentDao.updateAttributes(existing.get().id(), effectiveAttributes, now);
      return existing.get().id();
    }
    ComponentRecord record = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.PUB,
        null,
        metadata.packageName(),
        metadata.version(),
        "pub-package",
        HashColumns.componentCoordinateHash(null, metadata.packageName(), metadata.version()),
        effectiveAttributes,
        now);
    try {
      return componentDao.insert(record);
    } catch (DuplicateKeyException e) {
      if (!allowReplace) {
        throw new PubExceptions.WritePolicyDenied(
            "Pub package version already exists: " + metadata.packageName() + " " + metadata.version());
      }
      ComponentRecord concurrent = componentDao.findByNameAndVersion(
          runtime.id(), metadata.packageName(), metadata.version()).orElseThrow(() -> e);
      componentDao.updateAttributes(concurrent.id(), effectiveAttributes, now);
      return concurrent.id();
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
      String path,
      InputStream body,
      Map<String, String> remoteAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-pub-", ".tmp");
      Digests digests = copyWithDigests(body, tmp);
      Optional<AssetBlobRecord> reusable = reusableBlob(blobStoreId, digests.sha256(), digests.size(), remoteAttributes);
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
        throw new IllegalStateException("Failed to buffer Pub upload for " + path, io);
      }
      throw (RuntimeException) e;
    }
  }

  private Digests copyWithDigests(InputStream body, Path target) throws IOException {
    MessageDigest md5 = digest("MD5");
    MessageDigest sha1 = digest("SHA-1");
    MessageDigest sha256 = digest("SHA-256");
    MessageDigest sha512 = digest("SHA-512");
    long size;
    try (InputStream in = UpstreamBodyReadException.wrap(body);
        OutputStream out = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
      size = streamWithDigests(in, out, md5, sha1, sha256, sha512);
    }
    return new Digests(hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()),
        hex(sha512.digest()), size);
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "Pub metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "Pub metadata reuse");
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

  static Map<String, Object> archiveAttributes(
      PubPackageMetadata metadata,
      Digests digests,
      Map<String, Object> extraAttributes) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("packageName", metadata.packageName());
    attrs.put("version", metadata.version());
    attrs.put("archiveSha256", digests.sha256());
    attrs.put("archiveSize", digests.size());
    attrs.put("pubspec", metadata.pubspec());
    if (extraAttributes != null) {
      extraAttributes.forEach((key, value) -> {
        if (key != null && value != null) {
          attrs.put(key, value);
        }
      });
    }
    return attrs;
  }

  static Map<String, Object> componentAttributes(
      PubPackageMetadata metadata,
      Digests digests,
      String archivePath,
      Instant publishedAt,
      Map<String, Object> extraAttributes) {
    Map<String, Object> attrs = archiveAttributes(metadata, digests, extraAttributes);
    attrs.put("archivePath", archivePath);
    attrs.put("publishedAt", publishedAt.toString());
    return attrs;
  }

  private static void validateExpectedCoordinate(
      PubPackageMetadata metadata,
      String expectedPackageName,
      String expectedVersion) {
    if (expectedPackageName != null && !expectedPackageName.isBlank()
        && !metadata.packageName().equals(com.github.klboke.kkrepo.protocol.pub.PubPackageName.require(expectedPackageName))) {
      throw new PubExceptions.BadRequestException(
          "Archive package name does not match requested package: " + expectedPackageName);
    }
    if (expectedVersion != null && !expectedVersion.isBlank()
        && !metadata.version().equals(com.github.klboke.kkrepo.protocol.pub.PubVersions.require(expectedVersion))) {
      throw new PubExceptions.BadRequestException(
          "Archive version does not match requested version: " + expectedVersion);
    }
  }

  private static void validateExpectedSha256(
      String expectedSha256,
      String actualSha256,
      String packageName,
      String version) {
    if (expectedSha256 == null || expectedSha256.isBlank()) {
      return;
    }
    String normalized = expectedSha256.trim().toLowerCase(java.util.Locale.ROOT);
    if (normalized.length() != 64 || !normalized.chars().allMatch(PubAssetWriter::isHexDigit)) {
      throw new PubExceptions.BadUpstreamException(
          "Pub metadata archive_sha256 is invalid for " + packageName + " " + version);
    }
    if (!normalized.equals(actualSha256)) {
      throw new PubExceptions.BadUpstreamException(
          "Pub archive checksum mismatch for " + packageName + " " + version);
    }
  }

  private static void validateExpectedSize(
      Long expectedSize,
      long actualSize,
      String packageName,
      String version) {
    if (expectedSize == null || expectedSize < 0) {
      return;
    }
    if (expectedSize != actualSize) {
      throw new PubExceptions.BadUpstreamException(
          "Pub archive size mismatch for " + packageName + " " + version
              + ": expected " + expectedSize + ", actual " + actualSize);
    }
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
}
