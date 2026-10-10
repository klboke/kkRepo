package com.github.klboke.kkrepo.server.conan;

import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.protocol.conan.ConanManifestLimits;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ConanSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server defaults with durable, independently nullable repository overrides. */
@Component
public final class ConanManifestPolicy {
  private final ConanManifestLimits defaults;

  public ConanManifestPolicy(
      @Value("${kkrepo.conan.manifest.max-entries:4096}") int maxEntries,
      @Value("${kkrepo.conan.manifest.max-bytes:1048576}") int maxBytes) {
    this.defaults = new ConanManifestLimits(maxEntries, maxBytes);
  }

  public ConanManifestLimits forRepository(RepositoryRuntime runtime) {
    // The existing repository runtime cache supplies an immutable per-request snapshot.
    // RepositoryService invalidates it locally and broadcasts changes to sibling replicas;
    // its TTL remains the fallback when a broadcast is missed. No additional cache or query.
    return new ConanManifestLimits(
        runtime.conanManifestMaxEntries() == null ? defaults.maxEntries() : runtime.conanManifestMaxEntries(),
        runtime.conanManifestMaxBytes() == null ? defaults.maxBytes() : runtime.conanManifestMaxBytes());
  }

  public static ConanSettings read(Map<String, Object> attributes) {
    if (attributes == null || !(attributes.get("conan") instanceof Map<?, ?> values)) {
      return new ConanSettings(null, null);
    }
    return new ConanSettings(integer(values.get("manifestMaxEntries")),
        integer(values.get("manifestMaxBytes")));
  }

  public static Map<String, Object> attributes(ConanSettings settings) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (settings == null) return values;
    // Validate each override without coupling it to the other field or server defaults.
    new ConanManifestLimits(
        settings.manifestMaxEntries() == null ? 1 : settings.manifestMaxEntries(),
        settings.manifestMaxBytes() == null ? 1 : settings.manifestMaxBytes());
    if (settings.manifestMaxEntries() != null) {
      values.put("manifestMaxEntries", settings.manifestMaxEntries());
    }
    if (settings.manifestMaxBytes() != null) {
      values.put("manifestMaxBytes", settings.manifestMaxBytes());
    }
    return values;
  }

  private static Integer integer(Object value) {
    return value == null ? null : Integer.valueOf(value.toString());
  }
}
