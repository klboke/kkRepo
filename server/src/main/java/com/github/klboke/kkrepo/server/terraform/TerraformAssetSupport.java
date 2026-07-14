package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
final class TerraformAssetSupport {
  private final AssetDao assets;
  private final BlobStorageRegistry storages;
  private final RawHostedService hosted;

  TerraformAssetSupport(AssetDao assets, BlobStorageRegistry storages, RawHostedService hosted) {
    this.assets = assets;
    this.storages = storages;
    this.hosted = hosted;
  }

  void store(RepositoryRuntime runtime, String path, InputStream body, String contentType,
      Map<String, ?> attributes, String actor, String ip) {
    hosted.putInternal(runtime, path, body, contentType, attributes, actor, ip);
  }

  void storeBytes(RepositoryRuntime runtime, String path, byte[] body, String contentType,
      Map<String, ?> attributes) {
    store(runtime, path, new ByteArrayInputStream(body), contentType, attributes, "terraform", null);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  java.util.List<AssetRecord> list(RepositoryRuntime runtime, String prefix) {
    return assets.listAssetsByPrefix(runtime.id(), prefix);
  }

  AssetBlobRecord blob(AssetRecord asset) {
    return asset.assetBlobId() == null ? null : assets.findBlobById(asset.assetBlobId()).orElse(null);
  }

  byte[] bytes(RepositoryRuntime runtime, String path) {
    AssetRecord asset = find(runtime, path).orElseThrow(() -> notFound(path));
    AssetBlobRecord blob = blob(asset);
    if (blob == null) throw notFound(path);
    try (InputStream in = storages.forBlobStoreId(blob.blobStoreId()).get(
        BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> notFound(path))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Failed reading Terraform asset " + path, e);
    }
  }

  MavenResponse serve(RepositoryRuntime runtime, String path, boolean headOnly) {
    AssetRecord asset = find(runtime, path).orElseThrow(() -> notFound(path));
    AssetBlobRecord blob = blob(asset);
    if (blob == null) throw notFound(path);
    var ref = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    var storage = storages.forBlobStoreId(blob.blobStoreId());
    if (storage.stat(ref).isEmpty()) throw notFound(path);
    return headOnly
        ? MavenResponse.noBody(200, blob.size(), asset.contentType(), blob.sha1(), asset.lastUpdatedAt())
        : MavenResponse.ok(() -> storage.get(ref).orElseThrow(() -> notFound(path)),
            blob.size(), asset.contentType(), blob.sha1(), asset.lastUpdatedAt());
  }

  private static MavenExceptions.MavenNotFoundException notFound(String path) {
    return new MavenExceptions.MavenNotFoundException(path);
  }
}
