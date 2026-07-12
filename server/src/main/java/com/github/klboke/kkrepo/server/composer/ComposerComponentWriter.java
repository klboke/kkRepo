package com.github.klboke.kkrepo.server.composer;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import com.github.klboke.kkrepo.protocol.composer.ComposerPackageName;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

@Component
class ComposerComponentWriter {
  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final AssetMetadataCache assetMetadataCache;

  ComposerComponentWriter(
      ComponentDao componentDao,
      AssetDao assetDao,
      AssetMetadataCache assetMetadataCache) {
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.assetMetadataCache = assetMetadataCache;
  }

  @Transactional
  long bindHostedArchive(
      RepositoryRuntime runtime,
      AssetRecord asset,
      String packageName,
      String version,
      Map<String, Object> metadata,
      String distPath,
      String createdBy,
      String createdByIp) {
    Instant now = Instant.now();
    Map<String, Object> componentAttributes = new LinkedHashMap<>();
    componentAttributes.put("composerMetadata", metadata);
    componentAttributes.put("distPath", distPath);
    componentAttributes.put("source", "hosted");
    ComponentRecord record = new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.COMPOSER,
        ComposerPackageName.vendor(packageName),
        packageName,
        version,
        "composer-package",
        HashColumns.componentCoordinateHash(null, packageName, version),
        componentAttributes,
        now);
    long componentId;
    try {
      componentId = componentDao.insert(record);
    } catch (DuplicateKeyException e) {
      throw new MavenExceptions.WritePolicyDenied(
          "Composer package version already exists: " + packageName + " " + version);
    }
    Long previousComponentId = asset.componentId();
    Map<String, Object> assetAttributes = new LinkedHashMap<>(asset.attributes());
    assetAttributes.put("packageName", packageName);
    assetAttributes.put("version", version);
    assetAttributes.put("distPath", distPath);
    assetAttributes.put("source", "hosted");
    if (createdBy != null && !createdBy.isBlank()) assetAttributes.put("createdBy", createdBy);
    if (createdByIp != null && !createdByIp.isBlank()) assetAttributes.put("createdByIp", createdByIp);
    assetDao.updateAssetBlobBindingAndMetadata(
        asset.id(), componentId, asset.assetBlobId(), "composer-dist", asset.contentType(),
        asset.size(), now, assetAttributes);
    if (previousComponentId != null && previousComponentId != componentId) {
      componentDao.deleteIfNoAssets(previousComponentId);
    }
    assetMetadataCache.evictAfterCommit(runtime.id(), asset.path());
    return componentId;
  }
}
