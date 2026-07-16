package com.github.klboke.kkrepo.protocol.swift;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SwiftReleaseMetadata(
    String id,
    String version,
    List<SwiftReleaseResource> resources,
    Map<String, Object> metadata,
    Instant publishedAt) {
  public SwiftReleaseMetadata {
    id = SwiftPackageIdentity.parse(id).value();
    version = SwiftVersions.require(version);
    if (resources == null || resources.size() != 1 || resources.getFirst() == null) {
      throw new IllegalArgumentException(
          "Swift release metadata requires exactly one source archive resource");
    }
    resources = List.copyOf(resources);
    metadata = metadata == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
  }
}
