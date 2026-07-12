package com.github.klboke.kkrepo.server.composer;

import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
final class ComposerAssetSupport {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final RawHostedService rawHosted;

  ComposerAssetSupport(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      RawHostedService rawHosted) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.rawHosted = rawHosted;
  }

  void storeBytes(
      RepositoryRuntime runtime,
      String path,
      byte[] bytes,
      String contentType,
      Map<String, ?> attributes) {
    store(runtime, path, new ByteArrayInputStream(bytes), contentType, attributes);
  }

  void store(
      RepositoryRuntime runtime,
      String path,
      InputStream body,
      String contentType,
      Map<String, ?> attributes) {
    store(runtime, path, body, contentType, attributes, "system", null);
  }

  void store(
      RepositoryRuntime runtime,
      String path,
      InputStream body,
      String contentType,
      Map<String, ?> attributes,
      String createdBy,
      String createdByIp) {
    rawHosted.putInternal(runtime, path, body, contentType, attributes, createdBy, createdByIp);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assetDao.findAssetByPath(runtime.id(), path);
  }

  AssetBlobRecord blob(AssetRecord asset, String path) {
    if (asset.assetBlobId() == null) throw new MavenExceptions.MavenNotFoundException(path);
    return assetDao.findBlobById(asset.assetBlobId())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
  }

  byte[] readBytes(RepositoryRuntime runtime, String path) {
    AssetRecord asset = find(runtime, path)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    AssetBlobRecord blob = blob(asset, path);
    try (InputStream in = blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Failed reading Composer asset " + path, e);
    }
  }

  MavenResponse serve(RepositoryRuntime runtime, String path, boolean headOnly) {
    AssetRecord asset = find(runtime, path)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    AssetBlobRecord blob = blob(asset, path);
    var reference = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    var storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    if (storage.stat(reference).isEmpty()) throw new MavenExceptions.MavenNotFoundException(path);
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), asset.contentType(), blob.sha1(), asset.lastUpdatedAt());
    }
    return MavenResponse.ok(
        () -> storage.get(reference).orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path)),
        blob.size(), asset.contentType(), blob.sha1(), asset.lastUpdatedAt());
  }

  Instant lastModified(RepositoryRuntime runtime, String path) {
    return find(runtime, path).map(AssetRecord::lastUpdatedAt).orElse(null);
  }

  void delete(RepositoryRuntime runtime, String path) {
    rawHosted.deleteInternal(runtime, path);
  }
}
