package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DockerCleanupPolicyWorker {
  private static final Logger log = LoggerFactory.getLogger(DockerCleanupPolicyWorker.class);

  private final RepositoryDao repositoryDao;
  private final DockerRegistryDao dockerRegistryDao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final DockerManifestStore manifestStore;
  private final DockerMetrics metrics;
  private final boolean enabled;
  private final int batchSize;

  @Autowired
  public DockerCleanupPolicyWorker(
      RepositoryDao repositoryDao,
      DockerRegistryDao dockerRegistryDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      DockerManifestStore manifestStore,
      ObjectProvider<DockerMetrics> metricsProvider,
      @Value("${kkrepo.docker.cleanup-policy.enabled:true}") boolean enabled,
      @Value("${kkrepo.docker.cleanup-policy.batch-size:128}") int batchSize) {
    this(
        repositoryDao,
        dockerRegistryDao,
        runtimeRegistry,
        manifestStore,
        metricsProvider == null ? null : metricsProvider.getIfAvailable(),
        enabled,
        batchSize);
  }

  public DockerCleanupPolicyWorker(
      RepositoryDao repositoryDao,
      DockerRegistryDao dockerRegistryDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      DockerManifestStore manifestStore,
      DockerMetrics metrics,
      @Value("${kkrepo.docker.cleanup-policy.enabled:true}") boolean enabled,
      @Value("${kkrepo.docker.cleanup-policy.batch-size:128}") int batchSize) {
    this.repositoryDao = repositoryDao;
    this.dockerRegistryDao = dockerRegistryDao;
    this.runtimeRegistry = runtimeRegistry;
    this.manifestStore = manifestStore;
    this.metrics = metrics;
    this.enabled = enabled;
    this.batchSize = Math.max(1, batchSize);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.docker.cleanup-policy.interval-ms:900000}",
      initialDelayString = "${kkrepo.docker.cleanup-policy.initial-delay-ms:180000}")
  public void cleanup() {
    if (!enabled) {
      return;
    }
    for (RepositoryRecord repository : repositoryDao.list()) {
      if (repository.format() != RepositoryFormat.DOCKER || !repository.online()) {
        continue;
      }
      RepositoryRuntime runtime = runtimeRegistry.resolveById(repository.id()).orElse(null);
      if (runtime == null || !runtime.isHosted()) {
        continue;
      }
      for (DockerRegistryDao.CleanupPolicyRecord policy : dockerRegistryDao.listCleanupPolicies(repository.id())) {
        try {
          apply(runtime, policy);
        } catch (RuntimeException e) {
          log.warn("Docker cleanup policy failed: repository={} policy={}", runtime.name(), policy.name(), e);
          recordCleanup(runtime, policy.name(), "policy", "error", 1);
        }
      }
    }
  }

  void apply(RepositoryRuntime runtime, DockerRegistryDao.CleanupPolicyRecord policy) {
    Criteria criteria = Criteria.from(policy.criteria());
    if (criteria.isEmpty()) {
      return;
    }
    if (criteria.tagPattern() != null) {
      deleteTags(runtime, policy, criteria.tagPattern());
    }
    if (criteria.lastDownloadedBefore() != null
        || criteria.lastUpdatedBefore() != null
        || criteria.untaggedOnly()) {
      deleteManifests(runtime, policy, criteria);
    }
  }

  private void deleteTags(RepositoryRuntime runtime, DockerRegistryDao.CleanupPolicyRecord policy, Pattern tagPattern) {
    int deleted = 0;
    List<DockerRegistryDao.CleanupTagCandidate> candidates =
        dockerRegistryDao.listTagCleanupCandidates(runtime.id(), batchSize);
    for (DockerRegistryDao.CleanupTagCandidate candidate : candidates) {
      if (!tagPattern.matcher(candidate.tag()).matches()) {
        continue;
      }
      deleted += manifestStore.deleteReference(runtime, candidate.imageName(), candidate.tag());
    }
    recordCleanup(runtime, policy.name(), "tag", "deleted", deleted);
  }

  private void deleteManifests(
      RepositoryRuntime runtime, DockerRegistryDao.CleanupPolicyRecord policy, Criteria criteria) {
    List<DockerRegistryDao.CleanupManifestCandidate> candidates = dockerRegistryDao.listManifestCleanupCandidates(
        runtime.id(),
        criteria.untaggedOnly(),
        criteria.lastDownloadedBefore(),
        criteria.lastUpdatedBefore(),
        batchSize);
    int deleted = 0;
    for (DockerRegistryDao.CleanupManifestCandidate candidate : candidates) {
      deleted += manifestStore.deleteReference(runtime, candidate.imageName(), candidate.digest());
    }
    recordCleanup(runtime, policy.name(), "manifest", "deleted", deleted);
  }

  private void recordCleanup(
      RepositoryRuntime runtime, String policy, String action, String outcome, long count) {
    if (metrics != null) {
      metrics.cleanup(runtime, policy, action, outcome, count);
    }
  }

  record Criteria(
      Pattern tagPattern,
      Instant lastDownloadedBefore,
      Instant lastUpdatedBefore,
      boolean untaggedOnly) {
    boolean isEmpty() {
      return tagPattern == null && lastDownloadedBefore == null && lastUpdatedBefore == null && !untaggedOnly;
    }

    static Criteria from(Map<String, Object> raw) {
      Map<String, Object> criteria = raw == null ? Map.of() : raw;
      return new Criteria(
          pattern(text(criteria, "tagRegex", "tag_regex", "tagPattern", "tagNamePattern")),
          daysBefore(criteria, "lastDownloadedDays", "last_downloaded_days", "lastDownloadedOlderThanDays"),
          daysBefore(criteria, "lastUpdatedDays", "last_updated_days", "lastUpdatedOlderThanDays"),
          bool(criteria, "untagged", "untaggedOnly", "deleteUntagged"));
    }

    private static Pattern pattern(String value) {
      return value == null || value.isBlank() ? null : Pattern.compile(value);
    }

    private static Instant daysBefore(Map<String, Object> criteria, String... keys) {
      Integer days = integer(criteria, keys);
      return days == null || days < 0 ? null : Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private static String text(Map<String, Object> criteria, String... keys) {
      for (String key : keys) {
        Object value = criteria.get(key);
        if (value != null && !value.toString().isBlank()) {
          return value.toString();
        }
      }
      return null;
    }

    private static Integer integer(Map<String, Object> criteria, String... keys) {
      String value = text(criteria, keys);
      if (value == null) {
        return null;
      }
      try {
        return Integer.parseInt(value.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }

    private static boolean bool(Map<String, Object> criteria, String... keys) {
      String value = text(criteria, keys);
      return value != null && ("true".equalsIgnoreCase(value)
          || "1".equals(value)
          || "yes".equals(value.toLowerCase(Locale.ROOT)));
    }
  }
}
