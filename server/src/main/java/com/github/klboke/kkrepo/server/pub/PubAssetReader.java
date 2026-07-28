package com.github.klboke.kkrepo.server.pub;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class PubAssetReader {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final ArtifactDownloadPolicy downloadPolicy;

  PubAssetReader(AssetDao assetDao, BlobStorageRegistry blobStorageRegistry) {
    this(assetDao, blobStorageRegistry, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  PubAssetReader(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      ArtifactDownloadPolicy downloadPolicy) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.downloadPolicy = downloadPolicy;
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

  String readText(CachedAssetMetadata snapshot, String path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new PubExceptions.PubNotFoundException(path);
    }
    beforeRead(snapshot.assetId(), blob.id());
    try (var in = blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(path))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PubExceptions.BadUpstreamException("Failed reading cached Pub asset " + path, e);
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
      throw new PubExceptions.PubNotFoundException(path);
    }
    beforeRead(assetId, blob.id());
    var storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    var reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    if (storage.stat(reference).isEmpty()) {
      throw new PubExceptions.PubNotFoundException(path);
    }
    String etag = blob.sha1();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, etag, lastModified);
    }
    return MavenResponse.ok(
        () -> storage.get(reference).orElseThrow(() -> new PubExceptions.PubNotFoundException(path)),
        blob.size(), contentType, etag, lastModified);
  }

  void beforeRead(long assetId, long blobId) {
    if (downloadPolicy != null) downloadPolicy.beforeRead(assetId, blobId);
  }
}
