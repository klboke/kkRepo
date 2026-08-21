package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.r.RMediaTypes;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** OSS/S3-backed source package and immutable index assets for R repositories. */
@Component
final class RAssetSupport {
  private final AssetDao assets;
  private final ComponentDao components;
  private final BrowseNodeDao browseNodes;
  private final RawHostedService hosted;

  RAssetSupport(
      AssetDao assets,
      ComponentDao components,
      BrowseNodeDao browseNodes,
      RawHostedService hosted) {
    this.assets = assets;
    this.components = components;
    this.browseNodes = browseNodes;
    this.hosted = hosted;
  }

  AssetRecord storePackage(
      RepositoryRuntime runtime,
      String path,
      String browsePath,
      Path file,
      Map<String, ?> attributes,
      String actor,
      String ip,
      ComponentRecord component) {
    hosted.putInternalWithComponentFileAtBrowsePath(
        runtime, path, file, RMediaTypes.SOURCE_PACKAGE, attributes,
        actor, ip, component, browsePath);
    return requireAsset(runtime, path);
  }

  void storeGenerated(
      RepositoryRuntime runtime,
      String path,
      byte[] bytes,
      Map<String, ?> attributes) {
    hosted.putInternalUnindexed(
        runtime, path, new ByteArrayInputStream(bytes), RMediaTypes.PACKAGES_GZIP,
        attributes, "r-metadata", null);
  }

  void storeGeneratedFile(
      RepositoryRuntime runtime,
      String path,
      Path file,
      Map<String, ?> attributes) {
    hosted.putInternalUnindexedFile(
        runtime, path, file, RMediaTypes.PACKAGES_GZIP,
        attributes, "r-metadata", null);
  }

  MavenResponse serve(RepositoryRuntime runtime, String path, boolean headOnly) {
    return hosted.getInternal(runtime, path, headOnly);
  }

  void delete(RepositoryRuntime runtime, String path) {
    hosted.deleteInternal(runtime, path);
  }

  AssetRecord requireAsset(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
  }

  Optional<AssetRecord> findAsset(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  List<AssetRecord> listAssetsByComponent(long componentId) {
    return assets.listAssetsByComponent(componentId);
  }

  void retirePackageProjection(Long assetId) {
    if (assetId == null) return;
    assets.findAssetById(assetId).ifPresent(asset -> {
      Long componentId = asset.componentId();
      browseNodes.deleteByAssetId(asset.id());
      assets.updateAssetComponentBinding(asset.id(), null);
      if (componentId != null) components.deleteIfNoAssets(componentId);
    });
  }

  AssetRecord bindProxyPackage(
      RepositoryRuntime runtime,
      String path,
      ComponentRecord component,
      String browsePath,
      Map<String, ?> rAttributes) {
    AssetRecord asset = requireAsset(runtime, path);
    long componentId = components.upsertReturningId(component);
    assets.updateAssetComponentBinding(asset.id(), componentId);
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(asset.attributes());
    if (rAttributes != null) rAttributes.forEach(attributes::put);
    assets.updateAssetAttributes(asset.id(), Map.copyOf(attributes));
    components.touchLastUpdated(componentId, Instant.now());
    browseNodes.deleteByAssetId(asset.id());
    browseNodes.upsertPathAncestors(runtime.id(), browsePath, asset.id(), componentId);
    return assets.findAssetById(asset.id()).orElse(asset);
  }
}
