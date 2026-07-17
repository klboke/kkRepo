package com.github.klboke.kkrepo.protocol.swift;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public record SwiftReleaseList(Map<String, SwiftListedRelease> releases) {
  public SwiftReleaseList {
    LinkedHashMap<String, SwiftListedRelease> copied = new LinkedHashMap<>();
    if (releases != null) {
      releases.forEach((version, release) -> copied.put(SwiftVersions.require(version), release));
    }
    releases = Collections.unmodifiableMap(copied);
  }

  public static SwiftReleaseList available(
      Collection<String> versions, Function<String, String> releaseUrl) {
    return listed(versions, Map.of(), releaseUrl);
  }

  public static SwiftReleaseList listed(
      Collection<String> availableVersions,
      Map<String, String> unavailableVersions,
      Function<String, String> releaseUrl) {
    if (releaseUrl == null) {
      throw new IllegalArgumentException("Swift release URL renderer must not be null");
    }
    LinkedHashMap<String, SwiftListedRelease> result = new LinkedHashMap<>();
    LinkedHashMap<String, SwiftListedRelease> byVersion = new LinkedHashMap<>();
    if (unavailableVersions != null) {
      unavailableVersions.forEach((version, detail) -> byVersion.put(
          SwiftVersions.require(version), SwiftListedRelease.unavailable(detail)));
    }
    if (availableVersions != null) {
      availableVersions.forEach(version -> byVersion.put(
          SwiftVersions.require(version), SwiftListedRelease.available(releaseUrl.apply(version))));
    }
    SwiftVersions.sortDescending(byVersion.keySet()).forEach(version ->
        result.put(version, byVersion.get(version)));
    return new SwiftReleaseList(result);
  }
}
