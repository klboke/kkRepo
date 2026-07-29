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
    AssetWithBlob target = assetDao.findAssetWithBlobById(targetAssetId)
        .orElseThrow(() -> new IOException("Target asset is missing while registering Nexus public ID: "
            + targetAssetId));
    if (target.asset().repositoryId() != targetRepositoryId
        || !source.sourcePath().equals(target.asset().path())) {
      throw new IOException("Target asset identity does not match Nexus public ID source path: "
          + sourceRepositoryName + "/" + source.sourcePath());
    }
    if (target.blob() == null || target.blob().sha1() == null || target.blob().sha1().isBlank()) {
      throw new IOException("Target asset has no SHA-1 for Nexus public ID verification: "
          + sourceRepositoryName + "/" + source.sourcePath());
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
    NexusPublicAsset nexus = matches.getFirst();
    if (nexus.sha1() == null || nexus.sha1().isBlank()) {
      throw new IOException("Nexus exact asset search returned no SHA-1 for "
          + sourceRepositoryName + "/" + source.sourcePath());
    }
    if (!target.blob().sha1().equalsIgnoreCase(nexus.sha1())) {
      throw new IOException("Nexus/kkRepo SHA-1 mismatch while registering public ID for "
          + sourceRepositoryName + "/" + source.sourcePath()
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
}
