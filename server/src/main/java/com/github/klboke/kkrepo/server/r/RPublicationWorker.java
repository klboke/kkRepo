package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Debounces durable R revisions and lets any replica finish their publication. */
@Component
final class RPublicationWorker {
  private static final Logger log = LoggerFactory.getLogger(RPublicationWorker.class);

  private final RRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final RService service;
  private final boolean enabled;
  private final int batchSize;
  private final long debounceMillis;
  private final long maxDelayMillis;
  private final long retryMillis;

  RPublicationWorker(
      RRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      RService service,
      @Value("${kkrepo.r.publication.enabled:true}") boolean enabled,
      @Value("${kkrepo.r.publication.batch-size:16}") int batchSize,
      @Value("${kkrepo.r.publication.debounce-ms:500}") long debounceMillis,
      @Value("${kkrepo.r.publication.max-delay-ms:30000}") long maxDelayMillis,
      @Value("${kkrepo.r.publication.retry-ms:30000}") long retryMillis) {
    this.registry = registry;
    this.runtimes = runtimes;
    this.service = service;
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(batchSize, 256));
    this.debounceMillis = Math.max(0, debounceMillis);
    this.maxDelayMillis = Math.max(this.debounceMillis, Math.max(1_000, maxDelayMillis));
    this.retryMillis = Math.max(1_000, retryMillis);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.r.publication.poll-interval-ms:500}",
      initialDelayString = "${kkrepo.r.publication.initial-delay-ms:1000}")
  void publishPending() {
    if (!enabled) return;
    Instant now = Instant.now();
    for (RRegistryDao.SuiteState suite : registry.listPendingSuites(
        now.minusMillis(debounceMillis),
        now.minusMillis(maxDelayMillis),
        now.minusMillis(retryMillis),
        batchSize)) {
      publishOne(suite);
    }
  }

  private void publishOne(RRegistryDao.SuiteState suite) {
    RepositoryRuntime runtime = runtimes.resolveById(suite.repositoryId()).orElse(null);
    if (runtime == null || !runtime.online() || runtime.format() != RepositoryFormat.R) return;
    try {
      service.publishPendingIfAvailable(runtime, suite.distribution());
    } catch (RuntimeException error) {
      log.warn(
          "R publication failed; durable revision remains pending repository={} namespace={} revision={}",
          runtime.name(), suite.distribution(), suite.desiredRevision(), error);
    }
  }
}
