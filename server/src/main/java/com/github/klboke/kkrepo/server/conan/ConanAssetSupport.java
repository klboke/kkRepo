package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanBrowsePathProjector;
import com.github.klboke.kkrepo.protocol.conan.ConanPaths;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Blob-backed staging, promotion, and serving for Conan files. */
@Component
final class ConanAssetSupport {
  private final AssetDao assets;
  private final RawHostedService hosted;
  private final ConanBrowsePathProjector browsePaths = new ConanBrowsePathProjector();

  ConanAssetSupport(AssetDao assets, RawHostedService hosted) {
    this.assets = assets;
    this.hosted = hosted;
  }

  Staged stage(
      RepositoryRuntime runtime,
      long sessionId,
      String path,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    String stagingPath = ConanPaths.stagingPath(sessionId, path);
    hosted.putInternalHidden(
        runtime,
        stagingPath,
        body,
        contentType,
        Map.of("conanStaging", true, "conanFile", path),
        actor,
        ip);
    AssetRecord asset = assets.findAssetByPath(runtime.id(), stagingPath)
        .orElseThrow(() -> new IllegalStateException("Conan staging asset was not persisted"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      hosted.deleteInternal(runtime, stagingPath);
      throw new IllegalStateException("Conan staging blob was not persisted");
    }
    return new Staged(stagingPath, asset, blob);
  }

  Staged stageProxy(
      RepositoryRuntime runtime,
      String path,
      InputStream body,
      String contentType,
      String actor,
      String ip) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    String stagingPath = ".conan/proxy-staging/" + UUID.randomUUID() + "/" + filename;
    hosted.putInternalHidden(
        runtime,
        stagingPath,
        body,
        contentType,
        Map.of("conanProxyStaging", true, "conanFile", path),
        actor,
        ip);
    AssetRecord asset = assets.findAssetByPath(runtime.id(), stagingPath)
        .orElseThrow(() -> new IllegalStateException("Conan proxy staging asset was not persisted"));
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null : assets.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      hosted.deleteInternal(runtime, stagingPath);
      throw new IllegalStateException("Conan proxy staging blob was not persisted");
    }
    return new Staged(stagingPath, asset, blob);
  }

  AssetRecord promote(
      RepositoryRuntime runtime,
      ConanReference reference,
      String path,
      Staged staged,
      String contentType,
      String actor,
      String ip,
      ComponentRecord component) {
    String storagePath = ConanPaths.storagePath(reference, path);
    hosted.linkInternalBlobWithComponentAtBrowsePath(
        runtime,
        storagePath,
        staged.blob(),
        contentType,
        actor,
        ip,
        component,
        browsePaths.project(reference, path));
    return assets.findAssetByPath(runtime.id(), storagePath)
        .orElseThrow(() -> new IllegalStateException("Conan final asset was not persisted"));
  }

  MavenResponse serve(
      RepositoryRuntime runtime, ConanReference reference, String path, boolean headOnly) {
    return hosted.getInternal(runtime, ConanPaths.storagePath(reference, path), headOnly);
  }

  byte[] readStaged(RepositoryRuntime runtime, String stagingPath, int limit) {
    MavenResponse response = hosted.getInternal(runtime, stagingPath, false);
    if (response.contentLength() > limit) {
      response.closeBodyIfOpen();
      throw new ConanExceptions.ContentTooLarge("Conan metadata exceeds " + limit + " bytes");
    }
    try (InputStream body = response.body()) {
      byte[] value = body.readNBytes(limit + 1);
      if (value.length > limit) {
        throw new ConanExceptions.ContentTooLarge("Conan metadata exceeds " + limit + " bytes");
      }
      return value;
    } catch (IOException e) {
      throw new ConanExceptions.BadRequest("Unable to read staged Conan metadata", e);
    }
  }

  MavenResponse openStaged(RepositoryRuntime runtime, String stagingPath) {
    return hosted.getInternal(runtime, stagingPath, false);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  AssetBlobRecord blob(long assetId) {
    AssetRecord asset = assets.findAssetById(assetId)
        .orElseThrow(() -> new IllegalStateException("Conan asset no longer exists"));
    if (asset.assetBlobId() == null) throw new IllegalStateException("Conan asset has no blob");
    return assets.findBlobById(asset.assetBlobId())
        .orElseThrow(() -> new IllegalStateException("Conan blob no longer exists"));
  }

  void deleteByAssetId(RepositoryRuntime runtime, long assetId) {
    assets.findAssetById(assetId)
        .filter(asset -> asset.repositoryId() == runtime.id())
        .ifPresent(asset -> hosted.deleteInternal(runtime, asset.path()));
  }

  void discard(RepositoryRuntime runtime, Staged staged) {
    if (staged != null) hosted.deleteInternal(runtime, staged.path());
  }

  record Staged(String path, AssetRecord asset, AssetBlobRecord blob) {}
}
