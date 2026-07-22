package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.server.metrics.KkRepoMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bounds durable Ansible cache, terminal-task, and expired coordination rows.
 *
 * <p>All predicates are idempotent, so every replica may run this worker. Lease rows are retained
 * for at least seven days after expiry, far beyond the renewable ten-minute operation lease, before
 * their fencing tokens can be reclaimed.
 */
@Component
final class AnsibleCacheCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(AnsibleCacheCleanupWorker.class);

  private final AnsibleGalaxyRegistryDao registry;
  private final KkRepoMetrics metrics;
  private final boolean enabled;
  private final int batchSize;
  private final int maxBatches;
  private final Duration proxyPageRetention;
  private final Duration terminalTaskRetention;
  private final Duration expiredLeaseRetention;

  AnsibleCacheCleanupWorker(
      AnsibleGalaxyRegistryDao registry,
      KkRepoMetrics metrics,
      @Value("${kkrepo.ansible.cleanup.enabled:true}") boolean enabled,
      @Value("${kkrepo.ansible.cleanup.batch-size:256}") int batchSize,
      @Value("${kkrepo.ansible.cleanup.max-batches:8}") int maxBatches,
      @Value("${kkrepo.ansible.cleanup.proxy-page-retention-hours:24}") long proxyPageHours,
      @Value("${kkrepo.ansible.cleanup.terminal-task-retention-days:30}") long terminalTaskDays,
      @Value("${kkrepo.ansible.cleanup.expired-lease-retention-days:7}") long expiredLeaseDays) {
    this.registry = registry;
    this.metrics = metrics;
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(1000, batchSize));
    this.maxBatches = Math.max(1, Math.min(100, maxBatches));
    this.proxyPageRetention = Duration.ofHours(Math.max(1, proxyPageHours));
    this.terminalTaskRetention = Duration.ofDays(Math.max(1, terminalTaskDays));
    this.expiredLeaseRetention = Duration.ofDays(Math.max(1, expiredLeaseDays));
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.ansible.cleanup.interval-ms:60000}",
      initialDelayString = "${kkrepo.ansible.cleanup.initial-delay-ms:60000}")
  void cleanup() {
    if (!enabled) return;
    Instant now = Instant.now();
    try {
      int proxyRows = drain(() -> registry.deleteExpiredProxyCache(
          now, now.minus(proxyPageRetention), batchSize));
      int taskRows = drain(() -> registry.deleteTerminalTasksBefore(
          now.minus(terminalTaskRetention), batchSize));
      int leaseRows = drain(() -> registry.deleteExpiredLeasesBefore(
          now.minus(expiredLeaseRetention), batchSize));
      metrics.incrementWorkerItems("ansible_cleanup", "proxy_cache", "deleted", proxyRows);
      metrics.incrementWorkerItems("ansible_cleanup", "terminal_task", "deleted", taskRows);
      metrics.incrementWorkerItems("ansible_cleanup", "expired_lease", "deleted", leaseRows);
    } catch (RuntimeException error) {
      log.warn("Ansible shared-state cleanup failed; another replica will retry", error);
    }
  }

  private int drain(IntSupplier deleteBatch) {
    int total = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      int deleted = deleteBatch.getAsInt();
      total += deleted;
      if (deleted < batchSize) break;
    }
    return total;
  }
}
