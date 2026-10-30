package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.coordination.FencedLeaseManager;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Renews idle hosted metadata halfway through its configured validity window.
 *
 * <p>Each replica discovers work from durable repository/suite rows. The publication lease and
 * a fresh state check prevent replicas from queuing the same refresh repeatedly. Only a durable
 * revision is queued here: the bounded publication worker builds and atomically publishes the
 * complete signed snapshot, with its existing retry/backoff and by-hash retention semantics.
 */
@Component
final class AptMetadataRefreshWorker {
  private static final Logger log = LoggerFactory.getLogger(AptMetadataRefreshWorker.class);

  private final RepositoryDao repositories;
  private final RepositoryRuntimeRegistry runtimes;
  private final AptRepositorySettings settings;
  private final AptRegistryDao registry;
  private final AptLeaseManager leases;
  private final boolean enabled;

  AptMetadataRefreshWorker(
      RepositoryDao repositories,
      RepositoryRuntimeRegistry runtimes,
      AptRepositorySettings settings,
      AptRegistryDao registry,
      AptLeaseManager leases,
      @Value("${kkrepo.apt.publication.enabled:true}") boolean enabled) {
    this.repositories = repositories;
    this.runtimes = runtimes;
    this.settings = settings;
    this.registry = registry;
    this.leases = leases;
    this.enabled = enabled;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.apt.publication.refresh-interval-ms:3600000}",
      initialDelayString = "${kkrepo.apt.publication.refresh-initial-delay-ms:10000}")
  void refreshExpiringMetadata() {
    refreshExpiringMetadata(Instant.now());
  }

  void refreshExpiringMetadata(Instant now) {
    if (!enabled) return;
    for (RepositoryRecord repository : repositories.list()) {
      if (!repository.online() || repository.format() != RepositoryFormat.APT
          || repository.type() != RepositoryType.HOSTED) continue;
      try {
        RepositoryRuntime runtime = runtimes.resolveFreshById(repository.id()).orElse(null);
        if (runtime == null || !runtime.online() || !runtime.isHosted()
            || runtime.format() != RepositoryFormat.APT) continue;
        Integer validDays = settings.get(runtime).validUntilDays();
        if (validDays == null || validDays <= 0) continue;
        Instant cutoff = now.minus(Duration.ofDays(validDays).dividedBy(2));
        for (AptRegistryDao.SuiteState suite : registry.listSuites(runtime.id())) {
          if (!due(suite, cutoff)) continue;
          try {
            queueRefresh(runtime, suite.distribution(), cutoff, now);
          } catch (RuntimeException error) {
            log.warn("Could not queue APT metadata refresh repository={} distribution={}",
                runtime.name(), suite.distribution(), error);
          }
        }
      } catch (RuntimeException error) {
        log.warn("Could not check APT metadata expiration repository={}", repository.name(), error);
      }
    }
  }

  private void queueRefresh(
      RepositoryRuntime runtime, String distribution, Instant cutoff, Instant now) {
    Optional<FencedLeaseManager.Lease> acquired =
        leases.tryAcquire("apt:publish:" + runtime.id() + ":" + distribution);
    if (acquired.isEmpty()) return;
    try (FencedLeaseManager.Lease lease = acquired.orElseThrow()) {
      AptRegistryDao.SuiteState current = registry.findSuite(runtime.id(), distribution).orElse(null);
      if (!due(current, cutoff)) return;
      // Read the database, not a replica's snapshot cache. The snapshot creation time is the
      // signed Release Date; lastPublishedAt may be much later after a delayed build/retry.
      AptRegistryDao.Snapshot snapshot =
          registry.findPublishedSnapshot(runtime.id(), distribution).orElse(null);
      if (snapshot == null || snapshot.createdAt().isAfter(cutoff)) return;
      lease.assertHeld();
      registry.markSuiteDirty(runtime.id(), distribution, now);
    }
  }

  private static boolean due(AptRegistryDao.SuiteState suite, Instant cutoff) {
    return suite != null && suite.publishedRevision() > 0
        && suite.desiredRevision() == suite.publishedRevision()
        && (suite.desiredAt() == null || !suite.desiredAt().isAfter(cutoff));
  }
}
