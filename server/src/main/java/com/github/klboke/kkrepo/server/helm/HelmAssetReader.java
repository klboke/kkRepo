package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class HelmAssetReader {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final AssetMetadataCache assetMetadataCache;

  HelmAssetReader(AssetDao assetDao, BlobStorageRegistry blobStorageRegistry,
      AssetMetadataCache assetMetadataCache) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.assetMetadataCache = assetMetadataCache;
  }

  MavenResponse serve(AssetRecord asset, boolean headOnly, String path) {
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    String etag = blob.sha1();
    Instant lastModified = asset.lastUpdatedAt();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), asset.contentType(), etag, lastModified);
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path)),
        blob.size(), asset.contentType(), etag, lastModified);
  }

  MavenResponse serveSnapshot(CachedAssetMetadata snapshot, boolean headOnly, String path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    String etag = blob.sha1();
    Instant lastModified = snapshot.lastUpdatedAt();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), snapshot.contentType(), etag, lastModified);
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path)),
        blob.size(), snapshot.contentType(), etag, lastModified);
  }

}
