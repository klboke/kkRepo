package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class PypiAssetReader {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;

  PypiAssetReader(AssetDao assetDao, BlobStorageRegistry blobStorageRegistry) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
  }

  PypiResponse serve(AssetRecord asset, boolean headOnly, String path) {
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    return serveBlob(blob, asset.contentType(), asset.lastUpdatedAt(), headOnly, path);
  }

  PypiResponse serveSnapshot(CachedAssetMetadata snapshot, boolean headOnly, String path) {
    return serveBlob(snapshot.toBlobRecord(), snapshot.contentType(), snapshot.lastUpdatedAt(),
        headOnly, path);
  }

  private PypiResponse serveBlob(AssetBlobRecord blob, String contentType, Instant lastModified,
      boolean headOnly, String path) {
    if (blob == null) {
      throw new PypiExceptions.PypiNotFoundException(path);
    }
    String etag = blob.sha1();
    if (headOnly) {
      return PypiResponse.noBody(200, blob.size(), contentType, etag, lastModified);
    }
    return PypiResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new PypiExceptions.PypiNotFoundException(path)),
        blob.size(), contentType, etag, lastModified);
  }

}
