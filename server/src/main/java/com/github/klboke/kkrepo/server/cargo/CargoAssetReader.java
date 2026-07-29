package com.github.klboke.kkrepo.server.cargo;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class CargoAssetReader {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final ArtifactDownloadPolicy downloadPolicy;

  CargoAssetReader(AssetDao assetDao, BlobStorageRegistry blobStorageRegistry) {
    this(assetDao, blobStorageRegistry, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  CargoAssetReader(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      ArtifactDownloadPolicy downloadPolicy) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.downloadPolicy = downloadPolicy;
  }

  MavenResponse serve(AssetRecord asset, boolean headOnly, String path) {
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    return serveBlob(
        asset.id(), blob, asset.contentType(), asset.lastUpdatedAt(), headOnly, path);
  }

  MavenResponse serveSnapshot(CachedAssetMetadata snapshot, boolean headOnly, String path) {
    return serveBlob(
        snapshot.assetId(),
        snapshot.toBlobRecord(),
        snapshot.contentType(),
        snapshot.lastUpdatedAt(),
        headOnly,
        path);
  }

  boolean exists(CachedAssetMetadata snapshot) {
    AssetBlobRecord blob = snapshot == null ? null : snapshot.toBlobRecord();
    if (blob == null) {
      return false;
    }
    return blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).exists(
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()));
  }

  String readText(CachedAssetMetadata snapshot, String path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new CargoExceptions.CargoNotFoundException(path);
    }
    beforeRead(snapshot.assetId(), blob.id());
    try (var in = blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> new CargoExceptions.CargoNotFoundException(path))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CargoExceptions.BadUpstreamException("Failed reading cached Cargo asset " + path, e);
    }
  }

  private MavenResponse serveBlob(
      long assetId,
      AssetBlobRecord blob,
      String contentType,
      Instant lastModified,
      boolean headOnly,
      String path) {
    if (blob == null) {
      throw new CargoExceptions.CargoNotFoundException(path);
    }
    beforeRead(assetId, blob.id());
    var storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    var reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    if (storage.stat(reference).isEmpty()) {
      throw new CargoExceptions.CargoNotFoundException(path);
    }
    String etag = blob.sha1();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, etag, lastModified);
    }
    return MavenResponse.ok(
        () -> storage.get(reference)
            .orElseThrow(() -> new CargoExceptions.CargoNotFoundException(path)),
        blob.size(), contentType, etag, lastModified);
  }

  void beforeRead(long assetId, long blobId) {
    if (downloadPolicy != null) downloadPolicy.beforeRead(assetId, blobId);
  }
}
