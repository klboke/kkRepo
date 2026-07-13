package com.github.klboke.kkrepo.server.cargo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.cargo.CargoCrateName;
import com.github.klboke.kkrepo.protocol.cargo.CargoVersions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CargoSearchResults {
  private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
  };

  private CargoSearchResults() {
  }

  static MavenResponse fromComponents(
      ObjectMapper objectMapper,
      List<ComponentRecord> components,
      CargoSearchQuery query,
      boolean headOnly) {
    List<Map<String, Object>> crates = dedupe(componentCrates(components));
    return fromCrates(objectMapper, crates, query, headOnly);
  }

  static MavenResponse fromCrates(
      ObjectMapper objectMapper,
      List<Map<String, Object>> crates,
      CargoSearchQuery query,
      boolean headOnly) {
    List<Map<String, Object>> deduped = dedupe(crates);
    int total = deduped.size();
    int from = Math.min(query.offset(), total);
    int to = Math.min(total, from + query.perPage());
    return CargoResponses.json(objectMapper, responseBody(deduped.subList(from, to), total), 200, headOnly);
  }

  static List<Map<String, Object>> cratesFromResponse(ObjectMapper objectMapper, MavenResponse response) {
    try (var body = response.body()) {
      if (body == null) {
        return List.of();
      }
      Map<String, Object> json = objectMapper.readValue(
          new String(body.readAllBytes(), StandardCharsets.UTF_8),
          JSON_MAP);
      return crates(json);
    } catch (IOException e) {
      throw new CargoExceptions.BadUpstreamException("Failed reading Cargo search response", e);
    }
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> crates(Map<String, Object> response) {
    Object raw = response.get("crates");
    if (!(raw instanceof Iterable<?> iterable)) {
      return List.of();
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : iterable) {
      if (item instanceof Map<?, ?> map) {
        result.add(new LinkedHashMap<>((Map<String, Object>) map));
      }
    }
    return result;
  }

  private static Map<String, Object> responseBody(List<Map<String, Object>> crates, int total) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("crates", crates);
    body.put("meta", Map.of("total", total));
    return body;
  }

  private static List<Map<String, Object>> componentCrates(List<ComponentRecord> components) {
    if (components == null || components.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> crates = new ArrayList<>();
    for (ComponentRecord component : components) {
      Map<String, Object> attrs = component.attributes() == null ? Map.of() : component.attributes();
      String name = firstText(attrs.get("crateName"), component.name());
      String version = firstText(attrs.get("version"), component.version());
      if (name == null || version == null) {
        continue;
      }
      Map<String, Object> crate = new LinkedHashMap<>();
      crate.put("name", name);
      crate.put("max_version", version);
      crate.put("description", firstText(attrs.get("description"), ""));
      putIfPresent(crate, "homepage", attrs.get("homepage"));
      putIfPresent(crate, "documentation", attrs.get("documentation"));
      putIfPresent(crate, "repository", attrs.get("repository"));
      crates.add(crate);
    }
    return crates;
  }

  private static List<Map<String, Object>> dedupe(List<Map<String, Object>> crates) {
    if (crates == null || crates.isEmpty()) {
      return List.of();
    }
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    for (Map<String, Object> crate : crates) {
      String name = firstText(crate.get("name"), null);
      if (name == null) {
        continue;
      }
      byName.merge(nameKey(name), crate, CargoSearchResults::newerCrate);
    }
    return new ArrayList<>(byName.values());
  }

  private static Map<String, Object> newerCrate(Map<String, Object> left, Map<String, Object> right) {
    String leftVersion = firstText(left.get("max_version"), null);
    String rightVersion = firstText(right.get("max_version"), null);
    if (leftVersion == null) {
      return right;
    }
    if (rightVersion == null) {
      return left;
    }
    try {
      return CargoVersions.compare(rightVersion, leftVersion) > 0 ? right : left;
    } catch (IllegalArgumentException ignored) {
      return rightVersion.compareTo(leftVersion) > 0 ? right : left;
    }
  }

  private static void putIfPresent(Map<String, Object> target, String key, Object value) {
    String text = firstText(value, null);
    if (text != null) {
      target.put(key, text);
    }
  }

  private static String nameKey(String value) {
    try {
      return CargoCrateName.parse(value).lowerDashUnderscoreKey();
    } catch (RuntimeException ignored) {
      return value.toLowerCase(Locale.ROOT).replace('-', '_');
    }
  }

  private static String firstText(Object first, Object fallback) {
    String text = text(first);
    return text == null ? text(fallback) : text;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }
}
