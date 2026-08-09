package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
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

/** OSS/S3-backed package and revisioned metadata assets for APT. */
@Component
final class AptAssetSupport {
  private final AssetDao assets;
  private final ComponentDao components;
  private final BrowseNodeDao browseNodes;
  private final RawHostedService hosted;

  AptAssetSupport(
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
        runtime,
        path,
        file,
        com.github.klboke.kkrepo.protocol.apt.AptMediaTypes.DEBIAN_PACKAGE,
        attributes,
        actor,
        ip,
        component,
        browsePath);
    return assets.findAssetByPath(runtime.id(), path)
        .orElseThrow(() -> new IllegalStateException("APT package asset was not persisted: " + path));
  }

  void storeGenerated(
      RepositoryRuntime runtime,
      String path,
      byte[] bytes,
      String contentType,
      Map<String, ?> attributes) {
    hosted.putInternalUnindexed(
        runtime,
        path,
        new ByteArrayInputStream(bytes),
        contentType,
        attributes,
        "apt-metadata",
        runtime.name());
  }

  void storeGeneratedFile(
      RepositoryRuntime runtime,
      String path,
      Path file,
      String contentType,
      Map<String, ?> attributes) {
    hosted.putInternalUnindexedFile(
        runtime,
        path,
        file,
        contentType,
        attributes,
        "apt-metadata",
        runtime.name());
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
    if (assetId == null) {
      return;
    }
    assets.findAssetById(assetId).ifPresent(asset -> {
      Long componentId = asset.componentId();
      browseNodes.deleteByAssetId(asset.id());
      assets.updateAssetComponentBinding(asset.id(), null);
      if (componentId != null) {
        components.deleteIfNoAssets(componentId);
      }
    });
  }

  long upsertComponent(ComponentRecord component) {
    return components.upsertReturningId(component);
  }

  AssetBlobRecord requireBlob(RepositoryRuntime runtime, String path) {
    AssetRecord asset = requireAsset(runtime, path);
    if (asset.assetBlobId() == null) {
      throw new IllegalStateException("APT asset has no blob binding: " + path);
    }
    return assets.findBlobById(asset.assetBlobId())
        .orElseThrow(() -> new IllegalStateException("APT asset blob is missing: " + path));
  }

  AssetRecord bindProxyPackage(
      RepositoryRuntime runtime,
      String path,
      ComponentRecord component,
      String browsePath,
      Map<String, ?> aptAttributes) {
    AssetRecord asset = requireAsset(runtime, path);
    long componentId = components.upsertReturningId(component);
    assets.updateAssetComponentBinding(asset.id(), componentId);
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(asset.attributes());
    if (aptAttributes != null) aptAttributes.forEach(attributes::put);
    assets.updateAssetAttributes(asset.id(), Map.copyOf(attributes));
    components.touchLastUpdated(componentId, Instant.now());
    browseNodes.deleteByAssetId(asset.id());
    browseNodes.upsertPathAncestors(runtime.id(), browsePath, asset.id(), componentId);
    return assets.findAssetById(asset.id()).orElse(asset);
  }
}
