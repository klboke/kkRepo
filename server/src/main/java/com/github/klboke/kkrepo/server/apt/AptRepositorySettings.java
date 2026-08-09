package com.github.klboke.kkrepo.server.apt;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads validated APT settings from the durable repository definition.
 *
 * <p>Parsed settings are immutable and cached locally. The runtime registry version changes on
 * both local repository mutations and sibling-replica catalog broadcasts, so configuration
 * changes invalidate this hot-path cache without making every package or metadata request parse
 * the repository attribute map again. TTL is a missed-broadcast safety net.
 */
@Component
final class AptRepositorySettings {
  private final RepositoryRuntimeRegistry repositories;
  private final Cache<RepositoryKey, CachedSettings> cache;
  private final boolean cacheEnabled;

  @Autowired
  AptRepositorySettings(
      RepositoryRuntimeRegistry repositories,
      @Value("${kkrepo.apt.settings-cache-ttl-seconds:30}") long ttlSeconds) {
    this.repositories = repositories;
    this.cacheEnabled = ttlSeconds > 0;
    this.cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
        .maximumSize(100_000)
        .build();
  }

  /** Backward-compatible constructor used by focused unit tests. */
  AptRepositorySettings(RepositoryDao repositories) {
    this(new RepositoryRuntimeRegistry(repositories, 0), 30);
  }

  Settings get(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.APT) {
      throw new IllegalArgumentException("APT repository runtime is required");
    }
    long version = repositories.configurationVersion();
    RepositoryKey key = new RepositoryKey(runtime.id(), runtime.type());
    if (cacheEnabled) {
      CachedSettings cached = cache.getIfPresent(key);
      if (cached != null && cached.configurationVersion() == version) {
        return cached.settings();
      }
    }
    RepositoryRecord repository = repositories.findRecordById(runtime.id())
        .orElseThrow(() -> new IllegalStateException("APT repository definition is missing"));
    Settings settings = parse(runtime, repository);
    if (cacheEnabled) cache.put(key, new CachedSettings(version, settings));
    return settings;
  }

  private static Settings parse(RepositoryRuntime runtime, RepositoryRecord repository) {
    Map<String, Object> attributes = repository.attributes() == null ? Map.of() : repository.attributes();
    Object raw = attributes.get("apt");
    Map<?, ?> map = raw instanceof Map<?, ?> value ? value : Map.of();
    boolean hosted = runtime.isHosted();
    String distribution = text(map.get("distribution"), hosted ? "stable" : "").trim();
    String component = text(map.get("component"), "main").trim();
    ArrayList<String> architectures = new ArrayList<>();
    if (map.get("architectures") instanceof Iterable<?> values) {
      for (Object value : values) {
        if (value != null && !value.toString().isBlank()) {
          architectures.add(value.toString().trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (architectures.isEmpty()) architectures.add("amd64");
    boolean flat = bool(map.get("flat"), false);
    boolean enforce = bool(map.get("enforceDistribution"), hosted);
    String mode = text(map.get("metadataMode"), hosted ? "RESIGN" : "PASSTHROUGH")
        .trim().toUpperCase(Locale.ROOT);
    Integer validUntilDays = integer(map.get("validUntilDays"));
    return new Settings(
        distribution,
        component,
        List.copyOf(new LinkedHashSet<>(architectures)),
        flat,
        enforce,
        "RESIGN".equals(mode),
        validUntilDays,
        text(map.get("origin"), "kkRepo").trim(),
        text(map.get("label"), "kkRepo").trim());
  }

  private record CachedSettings(long configurationVersion, Settings settings) { }

  private record RepositoryKey(long repositoryId, RepositoryType type) { }

  private static String text(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  private static boolean bool(Object value, boolean fallback) {
    if (value == null) return fallback;
    return value instanceof Boolean flag ? flag : Boolean.parseBoolean(value.toString());
  }

  private static Integer integer(Object value) {
    if (value == null) return null;
    if (value instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  record Settings(
      String distribution,
      String component,
      List<String> architectures,
      boolean flat,
      boolean enforceDistribution,
      boolean resign,
      Integer validUntilDays,
      String origin,
      String label) { }
}
