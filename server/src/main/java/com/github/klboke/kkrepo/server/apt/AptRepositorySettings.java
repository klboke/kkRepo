package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Reads validated APT settings from the durable repository definition. */
@Component
final class AptRepositorySettings {
  private final RepositoryDao repositories;

  AptRepositorySettings(RepositoryDao repositories) {
    this.repositories = repositories;
  }

  Settings get(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.APT) {
      throw new IllegalArgumentException("APT repository runtime is required");
    }
    RepositoryRecord repository = repositories.findById(runtime.id())
        .orElseThrow(() -> new IllegalStateException("APT repository definition is missing"));
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
