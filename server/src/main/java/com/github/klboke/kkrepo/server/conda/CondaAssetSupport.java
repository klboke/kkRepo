package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** OSS/S3-backed Conda package access shared by hosted and proxy flows. */
@Component
class CondaAssetSupport {
  private final AssetDao assets;
  private final BrowseNodeDao browseNodes;
  private final RawHostedService hosted;

  CondaAssetSupport(AssetDao assets, BrowseNodeDao browseNodes, RawHostedService hosted) {
    this.assets = assets;
    this.browseNodes = browseNodes;
    this.hosted = hosted;
  }

  AssetRecord store(
      RepositoryRuntime runtime,
      String path,
      String browsePath,
      Path file,
      String contentType,
      Map<String, ?> attributes,
      String actor,
      String ip,
      ComponentRecord component) {
    hosted.putInternalWithComponentFileAtBrowsePath(
        runtime, path, file, contentType, attributes, actor, ip, component, browsePath);
    return assets.findAssetByPath(runtime.id(), path)
        .orElseThrow(() -> new IllegalStateException("Conda package asset was not persisted: " + path));
  }

  StagedAsset stage(
      RepositoryRuntime runtime,
      String logicalPath,
      Path file,
      String contentType,
      Map<String, ?> attributes,
      String actor,
      String ip,
      String expectedSha256,
      long expectedSize) {
    String stagingPath = ".conda/staging/" + UUID.randomUUID() + "/"
        + logicalPath.substring(logicalPath.lastIndexOf('/') + 1);
    hosted.putInternalUnindexedFile(
        runtime, stagingPath, file, contentType, attributes, actor, ip);
    try {
      AssetRecord asset = find(runtime, stagingPath)
          .orElseThrow(() -> new IllegalStateException(
              "Conda staged package asset was not persisted: " + stagingPath));
      AssetBlobRecord blob = asset.assetBlobId() == null
          ? null
          : assets.findBlobById(asset.assetBlobId()).orElse(null);
      if (blob == null
          || blob.size() != expectedSize
          || expectedSha256 == null
          || !expectedSha256.equalsIgnoreCase(blob.sha256())) {
        throw new IllegalStateException("Conda staged package checksum is invalid");
      }
      return new StagedAsset(stagingPath, blob);
    } catch (RuntimeException e) {
      try {
        hosted.deleteInternal(runtime, stagingPath);
      } catch (RuntimeException ignored) {
      }
      throw e;
    }
  }

  AssetRecord promote(
      RepositoryRuntime runtime,
      String path,
      String browsePath,
      StagedAsset staged,
      String contentType,
      String actor,
      String ip,
      ComponentRecord component) {
    hosted.linkInternalBlobWithComponentAtBrowsePath(
        runtime, path, staged.blob(), contentType, actor, ip, component, browsePath);
    return assets.findAssetByPath(runtime.id(), path)
        .orElseThrow(() -> new IllegalStateException(
            "Conda promoted package asset was not persisted: " + path));
  }

  void discard(RepositoryRuntime runtime, StagedAsset staged) {
    if (staged == null) return;
    try {
      hosted.deleteInternal(runtime, staged.path());
    } catch (RuntimeException ignored) {
      // Staging paths are hidden and blob GC can reclaim them after a transient cleanup failure.
    }
  }

  MavenResponse serve(RepositoryRuntime runtime, String path, boolean headOnly) {
    return hosted.get(runtime, path, headOnly);
  }

  MavenResponse serveInternal(RepositoryRuntime runtime, String path, boolean headOnly) {
    return hosted.getInternal(runtime, path, headOnly);
  }

  Optional<CachedAssetMetadata> findInternal(RepositoryRuntime runtime, String path) {
    return hosted.findInternal(runtime, path);
  }

  void storeGenerated(
      RepositoryRuntime runtime,
      String path,
      Path file,
      String contentType,
      Map<String, ?> attributes) {
    hosted.putInternalUnindexedFile(
        runtime, path, file, contentType, attributes, "conda-metadata", runtime.name());
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  AssetBlobRecord blob(RepositoryRuntime runtime, String path) {
    AssetRecord asset = find(runtime, path)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    if (asset.assetBlobId() == null) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
    return assets.findBlobById(asset.assetBlobId())
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
  }

  /** Attaches a deferred proxy download to its exact Conda component and Nexus-style Browse path. */
  @Transactional
  void bindCachedPackage(
      RepositoryRuntime runtime,
      AssetRecord asset,
      AssetBlobRecord blob,
      ComponentRecord component,
      String browsePath) {
    if (asset == null || asset.id() == null || blob == null || component == null) return;
    browseNodes.deleteByAssetId(asset.id());
    hosted.linkInternalBlobWithComponentAtBrowsePath(
        runtime,
        asset.path(),
        blob,
        asset.contentType(),
        "conda-proxy-index",
        runtime.proxyRemoteUrl(),
        component,
        browsePath);
  }

  void delete(RepositoryRuntime runtime, String path) {
    hosted.deleteInternal(runtime, path);
  }

  record StagedAsset(String path, AssetBlobRecord blob) { }
}
