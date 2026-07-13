package com.github.klboke.kkrepo.server.composer;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.protocol.composer.ComposerPackageName;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ComposerComponentWriter {
  private final ComponentDao componentDao;
  private final AssetDao assetDao;
  private final BrowseNodeDao browseNodeDao;
  private final AssetMetadataCache assetMetadataCache;

  ComposerComponentWriter(
      ComponentDao componentDao,
      AssetDao assetDao,
      BrowseNodeDao browseNodeDao,
      AssetMetadataCache assetMetadataCache) {
    this.componentDao = componentDao;
    this.assetDao = assetDao;
    this.browseNodeDao = browseNodeDao;
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
        PersistenceHashes.componentCoordinateHash(null, packageName, version),
        componentAttributes,
        now);
    long componentId;
    try {
      componentId = componentDao.insert(record);
    } catch (DuplicateKeyException e) {
      throw new MavenExceptions.WritePolicyDenied(
          "Composer package version already exists: " + packageName + " " + version);
    }
    Map<String, Object> assetAttributes = new LinkedHashMap<>();
    assetAttributes.put("packageName", packageName);
    assetAttributes.put("version", version);
    assetAttributes.put("distPath", distPath);
    assetAttributes.put("source", "hosted");
    if (createdBy != null && !createdBy.isBlank()) assetAttributes.put("createdBy", createdBy);
    if (createdByIp != null && !createdByIp.isBlank()) assetAttributes.put("createdByIp", createdByIp);
    AssetRecord finalAsset = new AssetRecord(
        null,
        runtime.id(),
        componentId,
        asset.assetBlobId(),
        RepositoryFormat.COMPOSER,
        distPath,
        PersistenceHashes.pathHash(distPath),
        fileName(distPath),
        "composer-dist",
        asset.contentType(),
        asset.size(),
        null,
        now,
        assetAttributes);
    OptionalLong inserted = assetDao.tryInsertAsset(finalAsset);
    if (inserted.isEmpty()) {
      throw new MavenExceptions.WritePolicyDenied(
          "Composer package version already exists: " + packageName + " " + version);
    }
    browseNodeDao.upsertPathAncestors(runtime.id(), distPath, inserted.getAsLong(), componentId);
    assetMetadataCache.evictAfterCommit(runtime.id(), distPath);
    return componentId;
  }

  private static String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
