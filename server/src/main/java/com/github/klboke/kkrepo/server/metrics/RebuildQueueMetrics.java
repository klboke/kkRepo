package com.github.klboke.kkrepo.server.metrics;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class RebuildQueueMetrics {
  private static final Logger log = LoggerFactory.getLogger(RebuildQueueMetrics.class);

  RebuildQueueMetrics(
      MeterRegistry registry,
      MetadataRebuildDao metadataRebuildDao,
      RepositoryIndexRebuildDao repositoryIndexRebuildDao,
      MaintenanceCursorDao maintenanceCursorDao,
      AssetDao assetDao) {
    Gauge.builder("kkrepo_metadata_rebuild_backlog", metadataRebuildDao, RebuildQueueMetrics::safeMetadataBacklog)
        .description("Pending Maven metadata rebuild markers")
        .register(registry);
    Gauge.builder("kkrepo_metadata_rebuild_oldest_age_seconds", metadataRebuildDao,
            RebuildQueueMetrics::safeMetadataOldestAge)
        .description("Age in seconds of the oldest Maven metadata rebuild marker")
        .register(registry);
    Gauge.builder("kkrepo_metadata_rebuild_failures", metadataRebuildDao, RebuildQueueMetrics::safeMetadataFailures)
        .description("Pending Maven metadata rebuild markers with failed attempts")
        .register(registry);
    Gauge.builder("kkrepo_repository_index_rebuild_backlog", repositoryIndexRebuildDao,
            RebuildQueueMetrics::safeRepositoryIndexBacklog)
        .description("Pending repository index rebuild markers")
        .register(registry);
    Gauge.builder("kkrepo_repository_index_rebuild_oldest_age_seconds", repositoryIndexRebuildDao,
            RebuildQueueMetrics::safeRepositoryIndexOldestAge)
        .description("Age in seconds of the oldest repository index rebuild marker")
        .register(registry);
    Gauge.builder("kkrepo_repository_index_rebuild_failures", repositoryIndexRebuildDao,
            RebuildQueueMetrics::safeRepositoryIndexFailures)
        .description("Pending repository index rebuild markers with failed attempts")
        .register(registry);
    Gauge.builder("kkrepo_blob_gc_backlog", assetDao, RebuildQueueMetrics::safeDeletedBlobBacklog)
        .description("Soft-deleted blobs waiting for garbage collection")
        .register(registry);
    Gauge.builder("kkrepo_blob_unreferenced_reconcile_cursor", maintenanceCursorDao,
            RebuildQueueMetrics::safeBlobReconcileCursor)
        .description("Last asset_blob id scanned by the orphan blob reconcile cursor")
        .register(registry);
  }

  private static double safeMetadataBacklog(MetadataRebuildDao dao) {
    return safe("metadata rebuild backlog", dao::countBacklog);
  }

  private static double safeMetadataOldestAge(MetadataRebuildDao dao) {
    return safe("metadata rebuild oldest age", dao::oldestBacklogAgeSeconds);
  }

  private static double safeMetadataFailures(MetadataRebuildDao dao) {
    return safe("metadata rebuild failures", dao::countFailures);
  }

  private static double safeRepositoryIndexBacklog(RepositoryIndexRebuildDao dao) {
    return safe("repository index rebuild backlog", dao::countBacklog);
  }

  private static double safeRepositoryIndexOldestAge(RepositoryIndexRebuildDao dao) {
    return safe("repository index rebuild oldest age", dao::oldestBacklogAgeSeconds);
  }

  private static double safeRepositoryIndexFailures(RepositoryIndexRebuildDao dao) {
    return safe("repository index rebuild failures", dao::countFailures);
  }

  private static double safeDeletedBlobBacklog(AssetDao dao) {
    return safe("blob gc backlog", dao::countDeletedBlobsAwaitingGc);
  }

  private static double safeBlobReconcileCursor(MaintenanceCursorDao dao) {
    return safe("blob orphan reconcile cursor",
        () -> dao.lastSeenId(MaintenanceCursorDao.BLOB_UNREFERENCED_RECONCILE));
  }

  private static double safe(String metric, LongSupplier supplier) {
    try {
      return supplier.getAsLong();
    } catch (RuntimeException e) {
      log.debug("failed to read {}", metric, e);
      return 0;
    }
  }

  @FunctionalInterface
  private interface LongSupplier {
    long getAsLong();
  }
}
