package com.github.klboke.kkrepo.server.alpine;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads validated Alpine settings from the durable repository definition.
 *
 * <p>Parsed settings are immutable and cached locally. The runtime registry version changes on
 * both local repository mutations and sibling-replica catalog broadcasts, so configuration
 * changes invalidate this hot-path cache without making every package or metadata request parse
 * the repository attribute map again. TTL is a missed-broadcast safety net.
 */
@Component
final class AlpineRepositorySettings {
  private final RepositoryRuntimeRegistry repositories;
  private final LocalCache<RepositoryKey, CachedSettings> cache;
  private final boolean cacheEnabled;

  @Autowired
  AlpineRepositorySettings(
      RepositoryRuntimeRegistry repositories,
      @Value("${kkrepo.alpine.settings-cache-ttl-seconds:30}") long ttlSeconds) {
    this.repositories = repositories;
    this.cacheEnabled = ttlSeconds > 0;
    this.cache = LocalCacheFactory.standard()
        .<RepositoryKey, CachedSettings>builder("alpine-repository-settings")
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
        .maximumSize(100_000)
        .build();
  }

  /** Backward-compatible constructor used by focused unit tests. */
  AlpineRepositorySettings(RepositoryDao repositories) {
    this(new RepositoryRuntimeRegistry(repositories, 0), 30);
  }

  Settings get(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.ALPINE) {
      throw new IllegalArgumentException("Alpine repository runtime is required");
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
        .orElseThrow(() -> new IllegalStateException("Alpine repository definition is missing"));
    Settings settings = parse(runtime, repository);
    if (cacheEnabled) cache.put(key, new CachedSettings(version, settings));
    return settings;
  }

  private static Settings parse(RepositoryRuntime runtime, RepositoryRecord repository) {
    Map<String, Object> attributes = repository.attributes() == null ? Map.of() : repository.attributes();
    Object raw = attributes.get("alpine");
    Map<?, ?> map = raw instanceof Map<?, ?> value ? value : Map.of();
    List<String> distributions = segments(map.get("distributions"), Segment.DISTRIBUTION);
    List<String> channels = segments(map.get("channels"), Segment.CHANNEL);
    List<String> architectures = segments(map.get("architectures"), Segment.ARCHITECTURE);
    String mode = text(
        map.get("metadataMode"), runtime.isHosted() || runtime.isGroup() ? "RESIGN" : "PASSTHROUGH")
        .trim().toUpperCase(Locale.ROOT);
    if (!Set.of("PASSTHROUGH", "RESIGN").contains(mode)) {
      throw new IllegalArgumentException("Unsupported Alpine metadata mode: " + mode);
    }
    String signatureType = text(map.get("signatureType"), "RSA").trim().toUpperCase(Locale.ROOT);
    if (AlpineSignature.Type.fromLabel(signatureType) == AlpineSignature.Type.DSA) {
      throw new IllegalArgumentException("Alpine repository signing requires an RSA key type");
    }
    String defaultKey = safeRepositoryName(runtime.name()) + ".rsa.pub";
    String keyFilename = AlpineSignature.requireKeyFilename(
        text(map.get("keyFilename"), defaultKey).trim());
    return new Settings(
        distributions,
        channels,
        architectures,
        "RESIGN".equals(mode),
        bool(map.get("verifyUpstreamSignatures"), !runtime.isProxy()),
        bool(map.get("staleIfError"), true),
        keyFilename,
        signatureType,
        text(map.get("description"), "kkRepo Alpine repository").trim(),
        stringList(map.get("upstreamPublicKeys")));
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

  private static List<String> segments(Object raw, Segment type) {
    ArrayList<String> values = new ArrayList<>();
    if (raw instanceof Iterable<?> iterable) {
      for (Object candidate : iterable) {
        if (candidate == null || candidate.toString().isBlank()) continue;
        String value = candidate.toString().trim().toLowerCase(Locale.ROOT);
        boolean valid = switch (type) {
          case DISTRIBUTION -> AlpinePathParser.isDistribution(value);
          case CHANNEL -> AlpinePathParser.isChannel(value);
          case ARCHITECTURE -> AlpinePathParser.isArchitecture(value);
        };
        if (!valid) throw new IllegalArgumentException("Invalid Alpine " + type + ": " + value);
        values.add(value);
      }
    }
    return List.copyOf(new LinkedHashSet<>(values));
  }

  private static List<String> stringList(Object raw) {
    ArrayList<String> values = new ArrayList<>();
    if (raw instanceof Iterable<?> iterable) {
      for (Object candidate : iterable) {
        if (candidate != null && !candidate.toString().isBlank()) values.add(candidate.toString());
      }
    }
    return List.copyOf(values);
  }

  private static String safeRepositoryName(String value) {
    String normalized = value == null ? "kkrepo-alpine"
        : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    if (normalized.isBlank()) normalized = "kkrepo-alpine";
    return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
  }

  record Settings(
      List<String> distributions,
      List<String> channels,
      List<String> architectures,
      boolean resign,
      boolean verifyUpstreamSignatures,
      boolean staleIfError,
      String keyFilename,
      String signatureType,
      String description,
      List<String> upstreamPublicKeys) {
    boolean allows(String distribution, String channel, String architecture) {
      return (distributions.isEmpty() || distributions.contains(distribution))
          && (channels.isEmpty() || channels.contains(channel))
          && (architectures.isEmpty() || architectures.contains(architecture));
    }
  }

  private enum Segment {
    DISTRIBUTION,
    CHANNEL,
    ARCHITECTURE
  }
}
