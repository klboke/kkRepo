package com.github.klboke.kkrepo.server.pub;

import com.github.klboke.kkrepo.protocol.pub.PubVersions;
import java.util.List;
import java.util.function.Function;

final class PubMetadataSupport {
  private PubMetadataSupport() {
  }

  static <T> T latestStableFirst(List<T> sortedVersions, Function<T, String> version) {
    if (sortedVersions == null || sortedVersions.isEmpty()) {
      throw new IllegalArgumentException("Pub metadata requires at least one version");
    }
    for (int i = sortedVersions.size() - 1; i >= 0; i--) {
      T entry = sortedVersions.get(i);
      if (!PubVersions.isPrerelease(version.apply(entry))) {
        return entry;
      }
    }
    return sortedVersions.get(sortedVersions.size() - 1);
  }
}
