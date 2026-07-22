package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** OSS/S3-backed collection asset access shared by hosted, proxy, and group services. */
@Component
final class AnsibleGalaxyAssetSupport {
  private final AssetDao assets;
  private final BlobStorageRegistry storages;
  private final RawHostedService hosted;

  AnsibleGalaxyAssetSupport(
      AssetDao assets,
      BlobStorageRegistry storages,
      RawHostedService hosted) {
    this.assets = assets;
    this.storages = storages;
    this.hosted = hosted;
  }

  record StoredCollection(AssetRecord asset, boolean created) {
  }

  StoredCollection storeCollection(
      RepositoryRuntime runtime,
      String path,
      Path file,
      Map<String, ?> attributes,
      String actor,
      String ip,
      ComponentRecord component) {
    String logicalPath = component.namespace() + "/" + component.name() + "/"
        + component.version() + "/" + Path.of(path).getFileName();
    boolean created = hosted.putInternalWithComponentFileAtBrowsePathIfAbsent(
        runtime,
        path,
        file,
        "application/octet-stream",
        attributes,
        actor,
        ip,
        component,
        logicalPath);
    AssetRecord asset = assets.findAssetByPath(runtime.id(), path)
        .orElseThrow(() -> new IllegalStateException(
            "Ansible collection asset was not persisted: " + runtime.name() + "/" + path));
    return new StoredCollection(asset, created);
  }

  AssetRecord stageCollection(
      RepositoryRuntime runtime,
      String taskId,
      String filename,
      Path file,
      String actor,
      String ip) {
    String stagingPath = ".ansible/staging/" + taskId + "/" + filename;
    hosted.putInternalUnindexedFile(
        runtime, stagingPath, file, "application/octet-stream", Map.of(), actor, ip);
    return assets.findAssetByPath(runtime.id(), stagingPath)
        .orElseThrow(() -> new IllegalStateException(
            "Ansible staged artifact was not persisted: " + stagingPath));
  }

  AssetRecord stageCollection(
      RepositoryRuntime runtime,
      String taskId,
      String filename,
      InputStream input,
      String actor,
      String ip) {
    String stagingPath = ".ansible/staging/" + taskId + "/" + filename;
    hosted.putInternalUnindexed(
        runtime, stagingPath, input, "application/octet-stream", Map.of(), actor, ip);
    return assets.findAssetByPath(runtime.id(), stagingPath)
        .orElseThrow(() -> new IllegalStateException(
            "Ansible staged artifact was not persisted: " + stagingPath));
  }

  StoredCollection promoteStagedCollection(
      RepositoryRuntime runtime,
      long stagingAssetId,
      String path,
      String actor,
      String ip,
      ComponentRecord component) {
    AssetDao.AssetWithBlob staged = assets.findAssetWithBlobById(stagingAssetId)
        .filter(value -> value.asset().repositoryId() == runtime.id())
        .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
            "Ansible staged artifact is missing"));
    if (staged.blob() == null) {
      throw new AnsibleGalaxyExceptions.NotFound("Ansible staged blob is missing");
    }
    String logicalPath = component.namespace() + "/" + component.name() + "/"
        + component.version() + "/" + Path.of(path).getFileName();
    boolean created = hosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        runtime, path, staged.blob(), "application/octet-stream", actor, ip, component, logicalPath);
    AssetRecord asset = assets.findAssetByPath(runtime.id(), path)
        .orElseThrow(() -> new IllegalStateException(
            "Ansible collection asset was not promoted: " + runtime.name() + "/" + path));
    return new StoredCollection(asset, created);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  MavenResponse serve(long repositoryId, long assetId, boolean headOnly) {
    AssetDao.AssetWithBlob stored = assets.findAssetWithBlobById(assetId)
        .filter(value -> value.asset().repositoryId() == repositoryId)
        .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
            "Ansible collection artifact is missing"));
    AssetRecord asset = stored.asset();
    AssetBlobRecord blob = stored.blob();
    if (blob == null) {
      throw new AnsibleGalaxyExceptions.NotFound("Ansible collection blob is missing");
    }
    var storage = storages.forBlobStoreId(blob.blobStoreId());
    var reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    if (headOnly) {
      return MavenResponse.noBody(
          200, blob.size(), "application/octet-stream", blob.sha256(), asset.lastUpdatedAt());
    }
    return MavenResponse.ok(
        () -> storage.get(reference).orElseThrow(() ->
            new AnsibleGalaxyExceptions.NotFound("Ansible collection blob is missing")),
        blob.size(),
        "application/octet-stream",
        blob.sha256(),
        asset.lastUpdatedAt());
  }

  InputStream open(long repositoryId, long assetId) {
    AssetDao.AssetWithBlob stored = assets.findAssetWithBlobById(assetId)
        .filter(value -> value.asset().repositoryId() == repositoryId)
        .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
            "Ansible staged artifact is missing"));
    if (stored.blob() == null) {
      throw new AnsibleGalaxyExceptions.NotFound("Ansible staged blob is missing");
    }
    return storages.forBlobStoreId(stored.blob().blobStoreId())
        .get(BlobReferenceCodec.reference(
            stored.blob().blobRef(), stored.blob().objectKey(), stored.blob().sha256(),
            stored.blob().size()))
        .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
            "Ansible staged blob is missing"));
  }

  AssetBlobRecord requiredBlob(AssetRecord asset) {
    if (asset.assetBlobId() == null) {
      throw new AnsibleGalaxyExceptions.NotFound("Ansible collection blob is missing");
    }
    return assets.findBlobById(asset.assetBlobId())
        .orElseThrow(() -> new AnsibleGalaxyExceptions.NotFound(
            "Ansible collection blob is missing"));
  }

  void delete(RepositoryRuntime runtime, String path) {
    hosted.deleteInternal(runtime, path);
  }
}
