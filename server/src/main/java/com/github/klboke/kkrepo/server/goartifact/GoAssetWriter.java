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
import com.github.klboke.kkrepo.server.blob.BlobTransactionCleanup;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import com.github.klboke.kkrepo.server.proxy.ProxyRequestAudit;
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

  public record ReleaseStored(Stored module, Stored info, Stored archive) {
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
              keepResponseFile ? upload.tempFile() : null,
              "proxy", ProxyRequestAudit.currentClientIp(), Instant.now(), true));
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

  /**
   * Uploads all three immutable Go release assets before publishing their bindings in one
   * database transaction. Readers on every replica therefore observe either the prior release or
   * the complete new release; no process-local publish state participates in correctness.
   */
  public ReleaseStored writeHostedRelease(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      GoPath modulePath,
      byte[] moduleBody,
      GoPath infoPath,
      byte[] infoBody,
      GoPath archivePath,
      Path archiveFile,
      String createdBy,
      String createdByIp,
      boolean allowReplace) {
    requireReleasePaths(modulePath, infoPath, archivePath);
    DigestedUpload moduleUpload = null;
    DigestedUpload infoUpload = null;
    DigestedUpload archiveUpload = null;
    try {
      moduleUpload = uploadWithDigests(
          runtime, storage, blobStoreId, modulePath,
          new ByteArrayInputStream(moduleBody), Map.of());
      infoUpload = uploadWithDigests(
          runtime, storage, blobStoreId, infoPath,
          new ByteArrayInputStream(infoBody), Map.of());
      archiveUpload = uploadFileWithDigests(
          runtime, storage, blobStoreId, archivePath, archiveFile, Map.of());
      DigestedUpload finalModuleUpload = moduleUpload;
      DigestedUpload finalInfoUpload = infoUpload;
      DigestedUpload finalArchiveUpload = archiveUpload;
      Instant publishedAt = Instant.now();
      ReleaseStored stored = executePersist(
          "Persist Go release " + runtime.name() + "/" + archivePath.module()
              + "@" + archivePath.version(),
          () -> {
            if (!allowReplace) {
              rejectExisting(runtime, modulePath, infoPath, archivePath);
            }
            Stored module = persist(
                runtime, blobStoreId, modulePath, finalModuleUpload, Map.of(), null,
                createdBy, createdByIp, publishedAt, allowReplace);
            Stored info = persist(
                runtime, blobStoreId, infoPath, finalInfoUpload, Map.of(), null,
                createdBy, createdByIp, publishedAt, allowReplace);
            Stored archive = persist(
                runtime, blobStoreId, archivePath, finalArchiveUpload, Map.of(), null,
                createdBy, createdByIp, publishedAt, allowReplace);
            return new ReleaseStored(module, info, archive);
          });
      cleanupUnusedUploadedBlob(storage, blobStoreId, moduleUpload, stored.module().blob());
      cleanupUnusedUploadedBlob(storage, blobStoreId, infoUpload, stored.info().blob());
      cleanupUnusedUploadedBlob(storage, blobStoreId, archiveUpload, stored.archive().blob());
      deleteUploadTemps(moduleUpload, infoUpload, archiveUpload);
      return stored;
    } catch (RuntimeException error) {
      cleanupUploadedBlob(storage, blobStoreId, moduleUpload);
      cleanupUploadedBlob(storage, blobStoreId, infoUpload);
      cleanupUploadedBlob(storage, blobStoreId, archiveUpload);
      deleteUploadTemps(moduleUpload, infoUpload, archiveUpload);
      throw error;
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

  private DigestedUpload uploadFileWithDigests(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      GoPath path,
      Path file,
      Map<String, String> extraBlobAttributes) {
    try {
      MessageDigest md5 = digest("MD5");
      MessageDigest sha1 = digest("SHA-1");
      MessageDigest sha256 = digest("SHA-256");
      long size = 0;
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
        for (int read; (read = input.read(buffer)) >= 0;) {
          if (read == 0) continue;
          size += read;
          md5.update(buffer, 0, read);
          sha1.update(buffer, 0, read);
          sha256.update(buffer, 0, read);
        }
      }
      String sha256Hex = hex(sha256.digest());
      Digests digests = new Digests(hex(md5.digest()), hex(sha1.digest()), sha256Hex, size);
      Optional<AssetBlobRecord> reusable = precheckedReusableBlob(
          blobStoreId, sha256Hex, size, extraBlobAttributes);
      if (reusable.isPresent()) {
        AssetBlobRecord blob = reusable.orElseThrow();
        return new DigestedUpload(
            BlobReferenceCodec.reference(
                blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()),
            digests,
            null,
            false);
      }
      BlobReference reference = storage.putFile(runtime.name(), path.path(), file, sha256Hex);
      return new DigestedUpload(reference, digests, null, true);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to upload Go content for " + path.path(), error);
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
      Path responseFile,
      String createdBy,
      String createdByIp,
      Instant now,
      boolean replaceExisting) {
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
          contentType(runtime, path),
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

    Long componentId = path.hasComponent() ? upsertComponent(runtime, path, now) : null;
    Map<String, Object> attrs = assetAttributes(path);
    AssetRecord persistedAsset;
    if (existing.isPresent()) {
      if (!replaceExisting) {
        throw new MavenExceptions.WritePolicyDenied("Go module version already exists: "
            + path.module() + " " + path.version());
      }
      AssetRecord prior = existing.get();
      persistedAsset = updateExistingAsset(prior, componentId, blobId, path.kind().name(),
          contentType(runtime, path), digests.size(), now, attrs);
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
          contentType(runtime, path),
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
        if (!replaceExisting) {
          throw new MavenExceptions.WritePolicyDenied("Go module version already exists: "
              + path.module() + " " + path.version());
        }
        previousBlobId = prior.assetBlobId();
        persistedAsset = updateExistingAsset(prior, componentId, blobId, path.kind().name(),
            contentType(runtime, path), digests.size(), now, attrs);
      }
    }

    if (previousBlobId != null && previousBlobId != blobId) {
      assetDao.markBlobDeletedIfUnreferenced(previousBlobId, "asset replaced");
    }

    // A MySQL deadlock rolls back the complete transaction before Spring translates it to
    // CannotAcquireLockException. Do not swallow that exception here: the surrounding
    // TransientTransactionRetry must replay the complete release, otherwise later statements
    // could commit only a suffix of the .mod/.info/.zip set.
    browseNodeDao.upsertPathAncestors(
        runtime.id(), path.path(), persistedAsset.id(), componentId);
    assetMetadataCache.evictAfterCommit(runtime.id(), path.path());
    return new Stored(persistedAsset, persistedBlob, responseFile);
  }

  private void rejectExisting(RepositoryRuntime runtime, GoPath... paths) {
    for (GoPath path : paths) {
      if (assetDao.findAssetByPath(runtime.id(), path.path()).isPresent()) {
        throw new MavenExceptions.WritePolicyDenied("Go module version already exists: "
            + path.module() + " " + path.version());
      }
    }
  }

  private static void requireReleasePaths(GoPath module, GoPath info, GoPath archive) {
    if (module.kind() != GoAssetKind.MODULE
        || info.kind() != GoAssetKind.INFO
        || archive.kind() != GoAssetKind.PACKAGE
        || !module.module().equals(info.module())
        || !module.module().equals(archive.module())
        || !module.version().equals(info.version())
        || !module.version().equals(archive.version())) {
      throw new IllegalArgumentException("Go hosted release paths do not describe one coordinate");
    }
  }

  private static void deleteUploadTemps(DigestedUpload... uploads) {
    if (uploads == null) return;
    for (DigestedUpload upload : uploads) {
      if (upload != null) TempBlobFiles.deleteQuietly(upload.tempFile());
    }
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

  private static String contentType(RepositoryRuntime runtime, GoPath path) {
    return runtime.isProxy() ? path.proxyContentType() : path.contentType();
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
