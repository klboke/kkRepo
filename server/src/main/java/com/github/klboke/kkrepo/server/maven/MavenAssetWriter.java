package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.maven.MavenConstants;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.path.ChecksumPayload;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.HashType;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a Maven path + bytes into the blob store and the asset/component tables. Shared by
 * hosted PUT, proxy cache writes, and metadata rebuild. Callers decide whether to emit checksum
 * siblings: ordinary repository PUTs store only the requested path, while generated metadata and
 * component-upload flows write the sidecars Nexus creates internally.
 */
@Component
public class MavenAssetWriter {
  private static final Logger log = LoggerFactory.getLogger(MavenAssetWriter.class);

  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;
  private final ObjectProvider<MavenGroupMetadataInvalidator> groupMetadataInvalidator;
  private final ObjectProvider<NexusLikeCacheController> cacheController;
  private final TransientTransactionRetry transactionRetry;

  @Autowired
  public MavenAssetWriter(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache,
      ObjectProvider<MavenGroupMetadataInvalidator> groupMetadataInvalidator,
      ObjectProvider<NexusLikeCacheController> cacheController,
      TransientTransactionRetry transactionRetry) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.browseNodeDao = browseNodeDao;
    this.assetMetadataCache = assetMetadataCache;
    this.groupMetadataInvalidator = groupMetadataInvalidator;
    this.cacheController = cacheController;
    this.transactionRetry = transactionRetry;
  }

  public MavenAssetWriter(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache,
      ObjectProvider<MavenGroupMetadataInvalidator> groupMetadataInvalidator) {
    this(assetDao, componentDao, browseNodeDao, assetMetadataCache, groupMetadataInvalidator, null,
        null);
  }

  public MavenAssetWriter(
      AssetDao assetDao,
      ComponentDao componentDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache,
      ObjectProvider<MavenGroupMetadataInvalidator> groupMetadataInvalidator,
      TransientTransactionRetry transactionRetry) {
    this(assetDao, componentDao, browseNodeDao, assetMetadataCache, groupMetadataInvalidator, null,
        transactionRetry);
  }

  public record Stored(
      AssetRecord asset,
      AssetBlobRecord blob,
      Digests digests,
      boolean created,
      Path responseFile) {
    public InputStream openBody() {
      return TempBlobFiles.openDeleteOnClose(responseFile);
    }

    public void discardBody() {
      TempBlobFiles.deleteQuietly(responseFile);
    }
  }

  public record Digests(String md5, String sha1, String sha256, String sha512, long size) {}

  /**
   * Stream {@code body} into blob storage while digesting in flight; replace or insert the asset
   * row; if the path has coordinates and is not a subordinate, upsert a component; finally write
   * four checksum siblings for primary artifacts and metadata files when the caller requests it
   * (not for the siblings themselves — we don't recurse hashes-of-hashes).
   */
  public Stored writePrimary(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath path,
      InputStream body,
      String contentTypeHint,
      String createdBy,
      String createdByIp) {
    return writePrimary(runtime, storage, blobStoreId, path, body, contentTypeHint,
        createdBy, createdByIp, Map.of(), true);
  }

  /**
   * Same as the basic form but lets the caller stash extra string attributes on the blob row
   * (e.g. remote ETag / Last-Modified for proxy-cached entries) and opt out of generating
   * checksum siblings (proxy fetches them as separate URLs so we don't preempt that path).
   */
  public Stored writePrimary(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath path,
      InputStream body,
      String contentTypeHint,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      boolean writeChecksumSiblings) {
    return writePrimary(runtime, storage, blobStoreId, path, body, contentTypeHint,
        createdBy, createdByIp, extraBlobAttributes, writeChecksumSiblings, false);
  }

  public Stored writePrimary(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath path,
      InputStream body,
      String contentTypeHint,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      boolean writeChecksumSiblings,
      boolean keepResponseFile) {
    DigestedUpload upload = uploadWithDigests(runtime, storage, blobStoreId, path, body, extraBlobAttributes);
    Map<MavenPath, DigestedUpload> checksumUploads = new LinkedHashMap<>();
    Map<MavenPath, Stored> checksumStored = new LinkedHashMap<>();
    try {
      if (writeChecksumSiblings && !path.isSubordinate()) {
        checksumUploads.putAll(checksumUploads(runtime, storage, blobStoreId, path, upload.digests()));
      }
      Stored stored = executePersist(
          "Persist Maven asset " + runtime.name() + "/" + path.path(),
          () -> {
            Stored persisted = persistAsset(runtime, storage, blobStoreId, path, upload, contentTypeHint,
                createdBy, createdByIp, extraBlobAttributes, keepResponseFile ? upload.tempFile() : null);
            for (Map.Entry<MavenPath, DigestedUpload> entry : checksumUploads.entrySet()) {
              Stored persistedChecksum = persistAsset(runtime, storage, blobStoreId, entry.getKey(), entry.getValue(),
                  MavenContentType.CHECKSUM, createdBy, createdByIp, Map.of(), null);
              checksumStored.put(entry.getKey(), persistedChecksum);
            }
            notifyAssetStored(runtime, path);
            return persisted;
          });
      cleanupUnusedUploadedBlob(storage, blobStoreId, upload, stored.blob());
      for (Map.Entry<MavenPath, DigestedUpload> entry : checksumUploads.entrySet()) {
        Stored persistedChecksum = checksumStored.get(entry.getKey());
        cleanupUnusedUploadedBlob(
            storage, blobStoreId, entry.getValue(),
            persistedChecksum == null ? null : persistedChecksum.blob());
      }
      if (!keepResponseFile) {
        TempBlobFiles.deleteQuietly(upload.tempFile());
      }
      checksumUploads.values().forEach(this::deleteTemp);
      return stored;
    } catch (RuntimeException e) {
      cleanupUploadedBlob(storage, blobStoreId, upload);
      checksumUploads.values().forEach(checksumUpload -> cleanupUploadedBlob(storage, blobStoreId, checksumUpload));
      TempBlobFiles.deleteQuietly(upload.tempFile());
      checksumUploads.values().forEach(this::deleteTemp);
      throw e;
    }
  }

  private <T> T executePersist(String operation, java.util.function.Supplier<T> callback) {
    if (transactionRetry == null) {
      return callback.get();
    }
    return transactionRetry.executeIfNoTransaction(operation, callback);
  }

  /**
   * Same as {@link #writePrimary} but takes the bytes directly — used by metadata rebuild where
   * the payload is small and already in memory.
   */
  public Stored writeBytes(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath path,
      byte[] body,
      String contentTypeHint,
      String createdBy,
      String createdByIp) {
    return writePrimary(runtime, storage, blobStoreId, path,
        new ByteArrayInputStream(body), contentTypeHint, createdBy, createdByIp);
  }

  /** Deletes an asset and soft-deletes its blob; cascades to checksum siblings and browse-node leaf rows. */
  @Transactional
  public int deleteAsset(RepositoryRuntime runtime, BlobStorage storage, MavenPath path) {
    return deleteAsset(runtime, storage, path, true);
  }

  @Transactional
  int deleteAsset(RepositoryRuntime runtime, BlobStorage storage, MavenPath path, boolean notifyGroupMetadata) {
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path.path());
    if (existing.isEmpty()) {
      return 0;
    }
    AssetRecord asset = existing.get();
    deleteOne(storage, asset);
    if (!path.isSubordinate()) {
      for (HashType ht : HashType.values()) {
        MavenPath sibling = path.hash(ht);
        assetDao.findAssetByPath(runtime.id(), sibling.path())
            .ifPresent(a -> deleteOne(storage, a));
      }
    }
    if (asset.componentId() != null) {
      componentDao.deleteIfNoAssets(asset.componentId());
    }
    if (notifyGroupMetadata) {
      notifyAssetDeleted(runtime, path);
    }
    return 1;
  }

  @Transactional
  public CachedAssetMetadata referenceCachedAsset(
      RepositoryRuntime runtime,
      MavenPath path,
      CachedAssetMetadata source,
      String createdBy,
      String createdByIp) {
    if (source == null || source.blob() == null) {
      return null;
    }
    Instant now = Instant.now();
    Long componentId = null;
    Coordinates coords = path.main().coordinates();
    if (coords != null && !path.isSubordinate()) {
      componentId = upsertComponent(runtime, coords, now);
    } else if (coords != null) {
      componentId = componentDao.findByGav(runtime.id(), coords.groupId(), coords.artifactId(), coords.baseVersion())
          .map(ComponentRecord::id).orElse(null);
    }

    Map<String, Object> attrs = cacheAttributes(runtime, path, now, Map.of(
        "sourceRepositoryId", source.repositoryId(),
        "sourcePath", source.path()));
    AssetBlobRecord blob = source.toBlobRecord();
    AssetRecord persistedAsset;
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path.path());
    String kind = classifyKind(path);
    String contentType = source.contentType();
    Long size = source.size();
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blob.id(), kind, contentType,
          size, now, attrs);
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blob.id(),
          RepositoryFormat.MAVEN2,
          path.path(),
          PersistenceHashes.pathHash(path.path()),
          path.fileName(),
          kind,
          contentType,
          size,
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
                "Concurrent Maven asset insert won but row is not visible for " + runtime.name() + "/" + path.path()));
        persistedAsset = updateExistingAsset(prior, componentId, blob.id(), kind, contentType,
            size, now, attrs);
      }
    }
    browseNodeDao.upsertPathAncestors(runtime.id(), path.path(), persistedAsset.id(), componentId);
    assetMetadataCache.evictAfterCommit(runtime.id(), path.path());
    return CachedAssetMetadata.of(persistedAsset, blob);
  }

  private void notifyAssetStored(RepositoryRuntime runtime, MavenPath path) {
    MavenGroupMetadataInvalidator invalidator = groupMetadataInvalidator == null
        ? null
        : groupMetadataInvalidator.getIfAvailable();
    if (invalidator == null) return;
    try {
      invalidator.memberAssetStored(runtime, path);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating Maven group cache after storing {} in {}",
          path.path(), runtime.name(), e);
    }
  }

  private void notifyAssetDeleted(RepositoryRuntime runtime, MavenPath path) {
    MavenGroupMetadataInvalidator invalidator = groupMetadataInvalidator == null
        ? null
        : groupMetadataInvalidator.getIfAvailable();
    if (invalidator == null) return;
    try {
      invalidator.memberAssetDeleted(runtime, path);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating Maven group cache after deleting {} in {}",
          path.path(), runtime.name(), e);
    }
  }

  private static boolean isMainRepositoryMetadata(MavenPath path) {
    return !path.isSubordinate() && MavenConstants.METADATA_FILENAME.equals(path.fileName());
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

  private record DigestedUpload(
      BlobReference reference,
      Digests digests,
      Path tempFile,
      boolean uploaded) {}

  private DigestedUpload uploadWithDigests(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath path,
      InputStream body,
      Map<String, String> extraBlobAttributes) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-maven-", ".tmp");
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
      BlobReference ref = storage.putFile(runtime.name(), path.path(), tmp, sha256Hex);
      return new DigestedUpload(ref, digests, tmp, true);
    } catch (RuntimeException | IOException e) {
      TempBlobFiles.deleteQuietly(tmp);
      if (e instanceof IOException io) {
        throw new IllegalStateException("Failed to buffer upload for " + path.path(), io);
      }
      throw (RuntimeException) e;
    }
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

  private Stored persistAsset(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath path,
      DigestedUpload upload,
      String contentTypeHint,
      String createdBy,
      String createdByIp,
      Map<String, String> extraBlobAttributes,
      Path responseFile) {
    Instant now = Instant.now();
    String contentType = contentTypeHint != null
        ? contentTypeHint
        : MavenContentType.forFileName(path.fileName());
    BlobReference ref = upload.reference();
    Digests digests = upload.digests();
    String blobRef = BlobReferenceCodec.format(ref);

    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path.path());
    Long previousBlobId = existing.map(AssetRecord::assetBlobId).orElse(null);
    AssetBlobRecord previousBlob = previousBlobId == null
        ? null
        : assetDao.findBlobById(previousBlobId).orElse(null);

    Map<String, Object> blobAttrs = new java.util.LinkedHashMap<>();
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

    Long componentId = null;
    Coordinates coords = path.main().coordinates();
    if (coords != null && !path.isSubordinate()) {
      componentId = upsertComponent(runtime, coords, now);
    } else if (coords != null) {
      // checksum siblings stay attached to the same component as their main artifact.
      // For snapshots the component is keyed by baseVersion (e.g. 1.0.0-SNAPSHOT), not the
      // timestamped filename version, so /a/b/1.0-SNAPSHOT/a-1.0-20260101.120000-3.jar.sha1
      // resolves to the same component as its jar.
      componentId = componentDao.findByGav(runtime.id(), coords.groupId(), coords.artifactId(), coords.baseVersion())
          .map(ComponentRecord::id).orElse(null);
    }

    AssetRecord persistedAsset;
    boolean created;
    String kind = classifyKind(path);
    Map<String, Object> attributes = cacheAttributes(runtime, path, now, existing
        .map(AssetRecord::attributes)
        .orElseGet(Map::of));
    if (existing.isPresent()) {
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
          digests.size(), now, attributes);
      created = false;
    } else {
      AssetRecord record = new AssetRecord(
          null,
          runtime.id(),
          componentId,
          blobId,
          RepositoryFormat.MAVEN2,
          path.path(),
          PersistenceHashes.pathHash(path.path()),
          path.fileName(),
          kind,
          contentType,
          digests.size(),
          null,
          now,
          attributes);
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
        AssetRecord prior = assetDao.findAssetByPath(runtime.id(), path.path())
            .orElseThrow(() -> new IllegalStateException(
                "Concurrent Maven asset insert won but row is not visible for " + runtime.name() + "/" + path.path()));
        previousBlob = prior.assetBlobId() == null ? null : assetDao.findBlobById(prior.assetBlobId()).orElse(null);
        Map<String, Object> duplicateAttributes = cacheAttributes(runtime, path, now, prior.attributes());
        persistedAsset = updateExistingAsset(prior, componentId, blobId, kind, contentType,
            digests.size(), now, duplicateAttributes);
        created = false;
      }
    }

    if (previousBlob != null && previousBlob.id() != null && previousBlob.id() != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlob.id(), "asset replaced");
    }
    browseNodeDao.upsertPathAncestors(runtime.id(), path.path(), persistedAsset.id(), componentId);
    assetMetadataCache.evictAfterCommit(runtime.id(), path.path());
    return new Stored(persistedAsset, persistedBlob, digests, created, responseFile);
  }

  private AssetRecord updateExistingAsset(
      AssetRecord prior,
      Long componentId,
      long blobId,
      String kind,
      String contentType,
      Long size,
      Instant lastUpdatedAt,
      Map<String, Object> attributes) {
    Long effectiveComponentId = componentId != null ? componentId : prior.componentId();
    assetDao.updateAssetBlobBindingAndMetadata(
        prior.id(),
        effectiveComponentId,
        blobId,
        kind,
        contentType,
        size == null ? 0L : size,
        lastUpdatedAt,
        attributes);
    return new AssetRecord(
        prior.id(), prior.repositoryId(), effectiveComponentId,
        blobId, prior.format(), prior.path(), prior.pathHash(), prior.name(),
        kind, contentType, size, prior.lastDownloadedAt(), lastUpdatedAt, attributes);
  }

  private long upsertComponent(RepositoryRuntime runtime, Coordinates coords, Instant now) {
    // Snapshot components share a single row across all timestamped uploads; baseVersion is the
    // grouping key (1.0.0-SNAPSHOT) and individual timestamped versions live as separate assets.
    String componentVersion = coords.baseVersion();
    ComponentRecord rec = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.MAVEN2,
        coords.groupId(),
        coords.artifactId(),
        componentVersion,
        coords.snapshot() ? "snapshot" : "release",
        PersistenceHashes.componentCoordinateHash(coords.groupId(), coords.artifactId(), componentVersion),
        Map.of(),
        now);
    return componentDao.upsertReturningId(rec);
  }

  private Map<MavenPath, DigestedUpload> checksumUploads(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath mainPath,
      Digests digests) {
    Map<MavenPath, DigestedUpload> uploads = new LinkedHashMap<>();
    try {
      MavenPath md5 = mainPath.hash(HashType.MD5);
      uploads.put(md5, checksumUpload(runtime, storage, blobStoreId, md5, digests.md5()));
      MavenPath sha1 = mainPath.hash(HashType.SHA1);
      uploads.put(sha1, checksumUpload(runtime, storage, blobStoreId, sha1, digests.sha1()));
      MavenPath sha256 = mainPath.hash(HashType.SHA256);
      uploads.put(sha256, checksumUpload(runtime, storage, blobStoreId, sha256, digests.sha256()));
      MavenPath sha512 = mainPath.hash(HashType.SHA512);
      uploads.put(sha512, checksumUpload(runtime, storage, blobStoreId, sha512, digests.sha512()));
      return uploads;
    } catch (RuntimeException e) {
      uploads.values().forEach(upload -> cleanupUploadedBlob(storage, blobStoreId, upload));
      uploads.values().forEach(this::deleteTemp);
      throw e;
    }
  }

  private DigestedUpload checksumUpload(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      MavenPath hashPath,
      String hex) {
    byte[] payload = ChecksumPayload.format(hex);
    return uploadWithDigests(runtime, storage, blobStoreId, hashPath, new ByteArrayInputStream(payload), Map.of());
  }

  private void cleanupUploadedBlob(BlobStorage storage, long blobStoreId, DigestedUpload upload) {
    if (upload == null || !upload.uploaded()) {
      return;
    }
    BlobTransactionCleanup.deleteIfUnreferenced(
        assetDao, storage, blobStoreId, upload.reference(), "Maven metadata persist failure");
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
        persistedBlob == null ? null : persistedBlob.objectKey(), "Maven metadata reuse");
  }

  private void deleteTemp(DigestedUpload upload) {
    if (upload != null) {
      TempBlobFiles.deleteQuietly(upload.tempFile());
    }
  }

  private String classifyKind(MavenPath path) {
    if (path.isHash()) return "checksum";
    if (path.isSignature()) return "signature";
    if (path.coordinates() == null) {
      return path.fileName().startsWith("maven-metadata.xml") ? "metadata" : "other";
    }
    return path.isPom() ? "pom" : "artifact";
  }

  private Map<String, Object> cacheAttributes(
      RepositoryRuntime runtime,
      MavenPath path,
      Instant now,
      Map<String, Object> base) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    if (base != null) {
      attrs.putAll(base);
    }
    NexusLikeCacheController controller = cacheController == null ? null : cacheController.getIfAvailable();
    if (controller == null) {
      return attrs;
    }
    return NexusLikeCacheInfo.applyToAttributes(attrs, controller.current(runtime.id(), cacheType(path), now));
  }

  private static NexusCacheType cacheType(MavenPath path) {
    return isMainRepositoryMetadata(path.main()) ? NexusCacheType.METADATA : NexusCacheType.CONTENT;
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
