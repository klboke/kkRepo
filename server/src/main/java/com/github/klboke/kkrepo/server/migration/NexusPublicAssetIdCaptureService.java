package com.github.klboke.kkrepo.server.migration;

import com.github.klboke.kkrepo.migration.nexus.NexusRestClient;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusPublicAsset;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.server.management.AssetPublicIdService;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Validates and registers Nexus REST public asset IDs during repository-data migration. */
@Service
class NexusPublicAssetIdCaptureService {
  private final AssetDao assetDao;
  private final AssetPublicIdService publicIdService;

  NexusPublicAssetIdCaptureService(AssetDao assetDao, AssetPublicIdService publicIdService) {
    this.assetDao = assetDao;
    this.publicIdService = publicIdService;
  }

  void capture(
      NexusRestClient client,
      String sourceInstance,
      long migrationJobId,
      String sourceRepositoryName,
      long targetRepositoryId,
      RepositoryDataMigrationAssetRecord source,
      long targetAssetId) throws IOException, InterruptedException {
    NexusPublicAsset prefetched = prefetchedPublicAsset(sourceRepositoryName, source);
    if (prefetched != null) {
      capture(
          sourceInstance,
          migrationJobId,
          sourceRepositoryName,
          targetRepositoryId,
          source.sourcePath(),
          prefetched,
          targetAssetId);
      return;
    }
    List<NexusPublicAsset> matches = client.findPublicAssets(
        sourceRepositoryName,
        source.sourcePath(),
        source.format().id(),
        source.namespace(),
        source.name(),
        source.version());
    if (matches.isEmpty()) {
      throw new IOException("Nexus exact asset search returned no public ID for "
          + sourceRepositoryName + "/" + source.sourcePath());
    }
    if (matches.size() != 1) {
      throw new IOException("Nexus exact asset search returned " + matches.size()
          + " distinct public IDs for " + sourceRepositoryName + "/" + source.sourcePath());
    }
    capture(
        sourceInstance,
        migrationJobId,
        sourceRepositoryName,
        targetRepositoryId,
        source.sourcePath(),
        matches.get(0),
        targetAssetId);
  }

  void capture(
      String sourceInstance,
      long migrationJobId,
      String sourceRepositoryName,
      long targetRepositoryId,
      String sourcePath,
      NexusPublicAsset nexus,
      long targetAssetId) throws IOException {
    if (nexus == null
        || !sourceRepositoryName.equals(nexus.repository())
        || !sourcePath.equals(nexus.path())) {
      throw new IOException("Nexus public asset identity does not match expected source path: "
          + sourceRepositoryName + "/" + sourcePath);
    }
    AssetWithBlob target = assetDao.findAssetWithBlobById(targetAssetId)
        .orElseThrow(() -> new IOException("Target asset is missing while registering Nexus public ID: "
            + targetAssetId));
    if (target.asset().repositoryId() != targetRepositoryId
        || !sourcePath.equals(target.asset().path())) {
      throw new IOException("Target asset identity does not match Nexus public ID source path: "
          + sourceRepositoryName + "/" + sourcePath);
    }
    if (target.blob() == null || target.blob().sha1() == null || target.blob().sha1().isBlank()) {
      throw new IOException("Target asset has no SHA-1 for Nexus public ID verification: "
          + sourceRepositoryName + "/" + sourcePath);
    }
    if (nexus.sha1() == null || nexus.sha1().isBlank()) {
      throw new IOException("Nexus public asset listing returned no SHA-1 for "
          + sourceRepositoryName + "/" + sourcePath);
    }
    if (!target.blob().sha1().equalsIgnoreCase(nexus.sha1())) {
      throw new IOException("Nexus/kkRepo SHA-1 mismatch while registering public ID for "
          + sourceRepositoryName + "/" + sourcePath
          + ": nexus=" + nexus.sha1() + ", kkrepo=" + target.blob().sha1());
    }
    publicIdService.registerNexusAlias(
        nexus.id(),
        sourceRepositoryName,
        targetRepositoryId,
        targetAssetId,
        sourceInstance,
        migrationJobId);
  }

  private static NexusPublicAsset prefetchedPublicAsset(
      String sourceRepositoryName,
      RepositoryDataMigrationAssetRecord source) {
    if (source.metadata() == null) {
      return null;
    }
    String id = string(source.metadata().get("nexusPublicAssetId"));
    if (id == null) {
      return null;
    }
    return new NexusPublicAsset(
        id,
        sourceRepositoryName,
        source.sourcePath(),
        string(source.metadata().get("nexusSha1")),
        null);
  }

  private static String string(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }
}
