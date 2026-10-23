package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes migrated path aliases and empty components only during an explicit repository deletion.
 * The Docker index is the shared source of truth. Repository/FK and row locks coordinate with
 * writers on every replica. Failed/incomplete migrations and any remaining manifest index
 * (including tombstones) prevent repair. The caller's repository deletion transaction owns all
 * mutations, including asset outbox/reference updates; a refused deletion rolls everything back.
 * Physical blob removal remains the responsibility of reference-aware global blob GC.
 */
@Component
public class DockerMigrationMetadataCleanup {
  private static final int BATCH_SIZE = 128;
  private final DockerRegistryDao docker;
  private final AssetDao assets;
  private final ComponentDao components;

  public DockerMigrationMetadataCleanup(DockerRegistryDao docker, AssetDao assets, ComponentDao components) {
    this.docker = docker;
    this.assets = assets;
    this.components = components;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cleanupBeforeRepositoryDeletion(long repositoryId) {
    if (!docker.lockEmptyRepositoryForMigrationCleanup(repositoryId)) return;
    List<Long> candidates;
    do {
      candidates = docker.lockMigratedManifestAssetsForCleanup(repositoryId, BATCH_SIZE);
      for (long assetId : candidates) {
        var asset = assets.findAssetByIdForUpdate(assetId).orElseThrow();
        assets.deleteAssetById(assetId);
        if (asset.assetBlobId() != null) {
          assets.markBlobDeletedIfUnreferenced(asset.assetBlobId(), "empty migrated Docker repository deleted");
        }
      }
    } while (!candidates.isEmpty());
    do {
      candidates = docker.lockEmptyMigratedComponentsForCleanup(repositoryId, BATCH_SIZE);
      for (long componentId : candidates) components.deleteIfNoAssets(componentId);
    } while (!candidates.isEmpty());
  }
}
