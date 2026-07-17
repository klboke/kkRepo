package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired Swift proxy negative-cache and upstream rate-limit waterline rows.
 *
 * <p>The rows live in the shared database and the delete predicate is idempotent, so every replica
 * may run this worker concurrently without node-local ownership. Permanent tombstones and lease
 * fencing rows are deliberately retained: deleting either could revive a release or let an old
 * owner reuse a fencing token.
 */
@Component
final class SwiftCacheCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(SwiftCacheCleanupWorker.class);

  private final SwiftRegistryDao registry;
  private final KkRepoMetrics metrics;
  private final boolean enabled;

  SwiftCacheCleanupWorker(
      SwiftRegistryDao registry,
      KkRepoMetrics metrics,
      @Value("${kkrepo.swift.cleanup.enabled:true}") boolean enabled) {
    this.registry = registry;
    this.metrics = metrics;
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.swift.cleanup.interval-ms:60000}",
      initialDelayString = "${kkrepo.swift.cleanup.initial-delay-ms:60000}")
  void cleanup() {
    if (!enabled) {
      return;
    }
    try {
      int deleted = registry.deleteExpiredNegativeCache(Instant.now());
      metrics.incrementWorkerItems(
          "swift_cleanup", "proxy_cache_waterline", "deleted", deleted);
    } catch (RuntimeException error) {
      log.warn("Swift shared cache cleanup failed; another replica will retry", error);
    }
  }
}
