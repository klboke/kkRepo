package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Debounces durable suite revisions and publishes them safely from any replica. */
@Component
final class AlpinePublicationWorker {
  private static final Logger log = LoggerFactory.getLogger(AlpinePublicationWorker.class);

  private final AlpineRegistryDao registry;
  private final RepositoryRuntimeRegistry runtimes;
  private final AlpineRepositorySettings settings;
  private final AlpineService service;
  private final boolean enabled;
  private final int batchSize;
  private final long debounceMillis;
  private final long maxDelayMillis;
  private final long retryMillis;

  AlpinePublicationWorker(
      AlpineRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      AlpineRepositorySettings settings,
      AlpineService service,
      @Value("${kkrepo.alpine.publication.enabled:true}") boolean enabled,
      @Value("${kkrepo.alpine.publication.batch-size:16}") int batchSize,
      @Value("${kkrepo.alpine.publication.debounce-ms:500}") long debounceMillis,
      @Value("${kkrepo.alpine.publication.max-delay-ms:30000}") long maxDelayMillis,
      @Value("${kkrepo.alpine.publication.retry-ms:30000}") long retryMillis) {
    this.registry = registry;
    this.runtimes = runtimes;
    this.settings = settings;
    this.service = service;
    this.enabled = enabled;
    this.batchSize = Math.max(1, Math.min(batchSize, 256));
    this.debounceMillis = Math.max(0, debounceMillis);
    this.maxDelayMillis = Math.max(this.debounceMillis, Math.max(1_000, maxDelayMillis));
    this.retryMillis = Math.max(1_000, retryMillis);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.alpine.publication.poll-interval-ms:500}",
      initialDelayString = "${kkrepo.alpine.publication.initial-delay-ms:1000}")
  void publishPending() {
    if (!enabled) return;
    Instant now = Instant.now();
    for (AlpineRegistryDao.SuiteState suite : registry.listPendingSuites(
        now.minusMillis(debounceMillis),
        now.minusMillis(maxDelayMillis),
        now.minusMillis(retryMillis),
        batchSize)) {
      publishOne(suite);
    }
  }

  private void publishOne(AlpineRegistryDao.SuiteState suite) {
    RepositoryRuntime runtime = runtimes.resolveById(suite.repositoryId()).orElse(null);
    if (runtime == null || !runtime.online() || runtime.format() != RepositoryFormat.ALPINE) {
      return;
    }
    AlpineRepositorySettings.Settings repositorySettings;
    try {
      repositorySettings = settings.get(runtime);
    } catch (RuntimeException error) {
      registry.recordBuildFailure(
          suite.repositoryId(),
          suite.distribution(),
          suite.desiredRevision(),
          error.getMessage(),
          Instant.now());
      log.warn(
          "Skipping invalid Alpine publication configuration repositoryId={} distribution={}",
          suite.repositoryId(), suite.distribution(), error);
      return;
    }
    if (!runtime.isHosted() && !runtime.isGroup() && !repositorySettings.resign()) return;
    try {
      service.publishPendingIfAvailable(runtime, suite.distribution());
    } catch (RuntimeException error) {
      // AlpineService persists the suite-level error against the exact desired revision.
      log.warn(
          "Alpine publication failed; durable revision remains pending repository={} distribution={} revision={}",
          runtime.name(), suite.distribution(), suite.desiredRevision(), error);
    }
  }
}
