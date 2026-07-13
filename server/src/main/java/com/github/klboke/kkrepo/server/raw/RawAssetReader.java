package com.github.klboke.kkrepo.server.raw;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.time.Instant;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
class RawAssetReader {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;

  RawAssetReader(AssetDao assetDao, BlobStorageRegistry blobStorageRegistry) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
  }

  MavenResponse serve(AssetRecord asset, boolean headOnly, String path, String contentDisposition) {
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    return serveBlob(blob, asset.contentType(), asset.lastUpdatedAt(), headOnly, path, contentDisposition);
  }

  MavenResponse serveSnapshot(CachedAssetMetadata snapshot, boolean headOnly, String path, String contentDisposition) {
    return serveBlob(snapshot.toBlobRecord(), snapshot.contentType(), snapshot.lastUpdatedAt(),
        headOnly, path, contentDisposition);
  }

  private MavenResponse serveBlob(AssetBlobRecord blob, String contentType, Instant lastModified,
      boolean headOnly, String path, String contentDisposition) {
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    String etag = blob.sha1();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, etag, lastModified);
    }
    MavenResponse response = MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path)),
        blob.size(), contentType, etag, lastModified);
    return response.withHeader("Content-Disposition", dispositionHeader(contentDisposition));
  }

  private static String dispositionHeader(String contentDisposition) {
    String value = contentDisposition == null || contentDisposition.isBlank()
        ? "ATTACHMENT"
        : contentDisposition;
    return value.toLowerCase(Locale.ROOT);
  }

}
