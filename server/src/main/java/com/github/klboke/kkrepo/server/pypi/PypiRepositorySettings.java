package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads PyPI proxy settings from the durable repository definition.
 *
 * <p>The local cache is only a parsed-settings hot cache. Repository catalog version changes are
 * broadcast across replicas and invalidate it; the TTL covers missed broadcasts. The database
 * remains the source of truth.
 */
@Component
final class PypiRepositorySettings {
  private final RepositoryRuntimeRegistry repositories;
  private final LocalCache<Long, CachedSettings> cache;
  private final boolean cacheEnabled;

  @Autowired
  PypiRepositorySettings(
      RepositoryRuntimeRegistry repositories,
      @Value("${kkrepo.pypi.settings-cache-ttl-seconds:30}") long ttlSeconds) {
    this.repositories = repositories;
    this.cacheEnabled = ttlSeconds > 0;
    this.cache = LocalCacheFactory.standard()
        .<Long, CachedSettings>builder("pypi-repository-settings")
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
        .maximumSize(100_000)
        .build();
  }

  /** Backward-compatible constructor used by focused unit tests. */
  PypiRepositorySettings(RepositoryDao repositories) {
    this(new RepositoryRuntimeRegistry(repositories, 0), 30);
  }

  Settings get(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.PYPI || !runtime.isProxy()) {
      throw new IllegalArgumentException("PyPI proxy repository runtime is required");
    }
    long version = repositories.configurationVersion();
    if (cacheEnabled) {
      CachedSettings cached = cache.getIfPresent(runtime.id());
      if (cached != null && cached.configurationVersion() == version) return cached.settings();
    }
    RepositoryRecord repository = repositories.findRecordById(runtime.id())
        .orElseThrow(() -> new IllegalStateException("PyPI repository definition is missing"));
    Settings settings = parse(repository);
    if (cacheEnabled) cache.put(runtime.id(), new CachedSettings(version, settings));
    return settings;
  }

  private static Settings parse(RepositoryRecord repository) {
    Map<String, Object> attributes = repository.attributes() == null
        ? Map.of() : repository.attributes();
    Object raw = attributes.get("pypi");
    Map<?, ?> pypi = raw instanceof Map<?, ?> map ? map : Map.of();
    Object indexPath = pypi.get("indexPath");
    return new Settings(PypiRemoteIndexPath.normalize(
        indexPath == null ? null : indexPath.toString()));
  }

  private record CachedSettings(long configurationVersion, Settings settings) { }

  record Settings(String indexPath) { }
}
