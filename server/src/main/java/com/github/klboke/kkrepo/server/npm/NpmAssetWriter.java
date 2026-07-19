package com.github.klboke.kkrepo.server.npm;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
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
import org.springframework.transaction.annotation.Transactional;

@Component
public class NpmAssetWriter {
  private static final String PACKAGE_ROOT = "package-root";
  private static final String TARBALL = "tarball";

  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final TransientTransactionRetry transactionRetry;
  private final AssetMetadataCache assetMetadataCache;
  private final NpmGroupPackumentCache packumentCache;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final NpmReleaseIndexDao releaseIndexDao;

  public NpmAssetWriter(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      TransientTransactionRetry transactionRetry,
      AssetMetadataCache assetMetadataCache,
      NpmGroupPackumentCache packumentCache,
      GroupMemberAssetCache groupMemberAssetCache) {
    this(assetDao, componentDao, browseNodeDao, transactionRetry, assetMetadataCache,
        packumentCache, groupMemberAssetCache, null);
  }

  @Autowired
  public NpmAssetWriter(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      TransientTransactionRetry transactionRetry,
      AssetMetadataCache assetMetadataCache,
      NpmGroupPackumentCache packumentCache,
      GroupMemberAssetCache groupMemberAssetCache,
      NpmReleaseIndexDao releaseIndexDao) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.browseNodeDao = browseNodeDao;
    this.transactionRetry = transactionRetry;
    this.assetMetadataCache = assetMetadataCache;
    this.packumentCache = packumentCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.releaseIndexDao = releaseIndexDao;
  }

  public record Stored(AssetRecord asset, AssetBlobRecord blob, Digests digests, boolean created, Path responseFile) {
    public InputStream openBody() {
      return TempBlobFiles.openDeleteOnClose(responseFile);
    }

    public void discardBody() {
      TempBlobFiles.deleteQuietly(responseFile);
    }
  }

  public record Digests(String md5, String sha1, String sha256, String sha512, long size) {}

  public Stored writePackageRoot(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      byte[] json,
      String createdBy,
      String createdByIp) {
    return writePackageRoot(runtime, storage, blobStoreId, packageId, json,
        createdBy, createdByIp, Map.of());
  }

  public Stored writePackageRoot(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      byte[] json,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes) {
    return writePackageRoot(runtime, storage, blobStoreId, packageId, json,
        createdBy, createdByIp, extraBlobAttributes, Map.of(), true, null);
  }

  public Stored writePackageRoot(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      byte[] json,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    return writePackageRoot(runtime, storage, blobStoreId, packageId, json,
        createdBy, createdByIp, extraBlobAttributes, Map.of(), true, releaseIndex);
  }

  public Stored writePackageRoot(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      byte[] json,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Map<String, Object> extraAssetAttributes,
      boolean notifyGroupCaches) {
    return writePackageRoot(runtime, storage, blobStoreId, packageId, json, createdBy,
        createdByIp, extraBlobAttributes, extraAssetAttributes, notifyGroupCaches, null);
  }

  private Stored writePackageRoot(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      byte[] json,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Map<String, Object> extraAssetAttributes,
      boolean notifyGroupCaches,
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    return write(runtime, storage, blobStoreId, packageId.id(), packageId, null,
        new ByteArrayInputStream(json), NpmResponseSupport.JSON, PACKAGE_ROOT,
        createdBy, createdByIp, extraBlobAttributes, extraAssetAttributes,
        false, notifyGroupCaches, true, releaseIndex);
  }

  public Stored writePackageRootCache(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      NpmPackumentVariant variant,
      byte[] json,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Map<String, Object> extraAssetAttributes,
      boolean notifyGroupCaches) {
    return write(runtime, storage, blobStoreId, variant.cachePath(packageId), packageId, null,
        new ByteArrayInputStream(json), NpmResponseSupport.JSON, variant.assetKind(),
        createdBy, createdByIp, extraBlobAttributes, extraAssetAttributes,
        false, notifyGroupCaches, variant == NpmPackumentVariant.FULL, null);
  }

  public Stored writeTarball(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      String version,
      String tarballName,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes) {
    return write(runtime, storage, blobStoreId, packageId.tarballPath(tarballName), packageId, version,
        body, npmTarballContentType(contentType), TARBALL,
        createdBy, createdByIp, extraBlobAttributes, Map.of(), false, true, true);
  }

  public Stored writeTarball(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      String version,
      String tarballName,
      InputStream body,
      String contentType,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      boolean keepResponseFile) {
    return write(runtime, storage, blobStoreId, packageId.tarballPath(tarballName), packageId, version,
        body, npmTarballContentType(contentType), TARBALL,
        createdBy, createdByIp, extraBlobAttributes, Map.of(), keepResponseFile, true, true);
  }

  private static String npmTarballContentType(String ignored) {
    return NpmResponseSupport.TARBALL;
  }

  @Transactional
  public int deletePath(RepositoryRuntime runtime, BlobStorage storage, String path) {
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path);
    if (existing.isEmpty()) {
      return 0;
    }
    deleteOne(storage, existing.get());
    return 1;
  }

  @Transactional
  public int deletePackage(RepositoryRuntime runtime, BlobStorage storage, NpmPackageId packageId) {
    int deleted = 0;
    for (AssetRecord asset : assetDao.listAssetsByPrefix(runtime.id(), packageId.id())) {
      if (asset.path().equals(packageId.id()) || asset.path().startsWith(packageId.id() + "/")) {
        deleteOne(storage, asset);
        deleted++;
      }
    }
    return deleted;
  }

  private Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      NpmPackageId packageId,
      String version,
      InputStream body,
      String contentType,
      String kind,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Map<String, Object> extraAssetAttributes,
      boolean keepResponseFile,
      boolean notifyGroupCaches,
      boolean indexBrowseNode) {
    return write(runtime, storage, blobStoreId, path, packageId, version, body, contentType,
        kind, createdBy, createdByIp, extraBlobAttributes, extraAssetAttributes,
        keepResponseFile, notifyGroupCaches, indexBrowseNode, null);
  }

  private Stored write(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      NpmPackageId packageId,
      String version,
      InputStream body,
      String contentType,
      String kind,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Map<String, Object> extraAssetAttributes,
      boolean keepResponseFile,
      boolean notifyGroupCaches,
      boolean indexBrowseNode,
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, extraBlobAttributes);
    try {
      Stored stored = executePersist(
          "Persist npm asset " + runtime.name() + "/" + path,
          () -> persist(runtime, blobStoreId, path, packageId, version, contentType, kind,
              createdBy, createdByIp, extraBlobAttributes, extraAssetAttributes, notifyGroupCaches,
              upload, keepResponseFile ? upload.tempFile() : null, indexBrowseNode, releaseIndex));
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
      NpmPackageId packageId,
      String version,
      String contentType,
      String kind,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Map<String, Object> extraAssetAttributes,
      boolean notifyGroupCaches,
      DigestedUpload upload,
      Path responseFile,
      boolean indexBrowseNode,
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    Instant now = Instant.now();
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

    Long componentId = TARBALL.equals(kind) ? upsertComponent(runtime, packageId, version, now) : null;
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("packageId", packageId.id());
    if (version != null) attrs.put("version", version);
    if (extraAssetAttributes != null) {
      extraAssetAttributes.forEach((key, value) -> {
        if (value != null) attrs.put(key, value);
      });
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
          RepositoryFormat.NPM,
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
      } else {
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path)
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent npm asset insert won but row is not visible for " + runtime.name() + "/" + path));
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
    if (releaseIndex != null && releaseIndexDao != null && PACKAGE_ROOT.equals(kind)) {
      boolean indexed = releaseIndexDao.replaceIfCurrent(
          persistedAsset.id(),
          blobId,
          releaseIndex.releases().stream()
              .allMatch(release -> release.invalidReason() == null),
          toReleaseRows(releaseIndex),
          now);
      if (!indexed) {
        throw new IllegalStateException(
            "npm release index revision no longer matches package root "
                + runtime.name() + "/" + path);
      }
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), path);
    if (notifyGroupCaches && packumentCache != null) {
      packumentCache.invalidateMemberPackageAfterCommit(runtime.id(), packageId.id());
    }
    if (notifyGroupCaches && groupMemberAssetCache != null) {
      groupMemberAssetCache.invalidateMemberAfterCommit(runtime.id());
    }
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

  private long upsertComponent(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String version,
      Instant now) {
    String componentVersion = version == null ? "" : version;
    byte[] hash = PersistenceHashes.componentCoordinateHash(packageId.scope(), packageId.name(), componentVersion);
    ComponentRecord rec = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.NPM,
        packageId.scope(),
        packageId.name(),
        componentVersion,
        "tarball",
        hash,
        Map.of("packageId", packageId.id()),
        now);
    return componentDao.upsertReturningId(rec);
  }

  private void deleteOne(BlobStorage storage, AssetRecord asset) {
    Long blobId = asset.assetBlobId();
    browseNodeDao.deleteByAssetId(asset.id());
    assetDao.deleteAssetById(asset.id());
    if (blobId != null) {
      assetDao.markBlobDeletedIfUnreferenced(blobId, "asset unlinked");
    }
    if (asset.componentId() != null) {
      componentDao.deleteIfNoAssets(asset.componentId());
    }
    assetMetadataCache.evictAfterCommit(asset.repositoryId(), asset.path());
    if (packumentCache != null) {
      String packageId = packageIdForInvalidation(asset);
      if (packageId == null) {
        packumentCache.invalidateMemberAfterCommit(asset.repositoryId());
      } else {
        packumentCache.invalidateMemberPackageAfterCommit(asset.repositoryId(), packageId);
      }
    }
    if (groupMemberAssetCache != null) {
      groupMemberAssetCache.invalidateMemberAfterCommit(asset.repositoryId());
    }
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
      tmp = Files.createTempFile("kkrepo-npm-", ".tmp");
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
        throw new IllegalStateException("Failed to buffer npm asset " + path, io);
      }
      throw (RuntimeException) e;
    }
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "npm metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "npm metadata reuse");
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

  private static String packageIdForInvalidation(AssetRecord asset) {
    if (asset == null) {
      return null;
    }
    Object attr = asset.attributes() == null ? null : asset.attributes().get("packageId");
    if (attr != null && !attr.toString().isBlank()) {
      return attr.toString();
    }
    if (PACKAGE_ROOT.equals(asset.kind())) {
      return asset.path();
    }
    String path = asset.path();
    int tarballSegment = path == null ? -1 : path.indexOf("/-/");
    return tarballSegment <= 0 ? null : path.substring(0, tarballSegment);
  }

  private static java.util.List<NpmReleaseIndexDao.Release> toReleaseRows(
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    java.util.List<NpmReleaseIndexDao.Release> rows = new java.util.ArrayList<>();
    int ordinal = 0;
    for (NpmMinimumReleaseAge.IndexedRelease release : releaseIndex.releases()) {
      rows.add(new NpmReleaseIndexDao.Release(
          ordinal++,
          release.version(),
          release.publishedAt(),
          release.invalidReason(),
          release.tarballName()));
    }
    return java.util.List.copyOf(rows);
  }

}
