package com.github.klboke.kkrepo.server.winget;

import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetVersion;
import com.github.klboke.kkrepo.protocol.winget.WingetVersions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small merge/filter helpers shared by hosted, proxy and group behavior. */
public final class WingetManifests {
  private WingetManifests() {}

  public static WingetManifest filter(
      WingetManifest manifest, String packageVersion, String channel) {
    List<WingetVersion> versions = new ArrayList<>();
    for (WingetVersion version : manifest.versions()) {
      if (matches(version, packageVersion, channel)) {
        versions.add(version);
      }
    }
    versions.sort(Comparator
        .comparing(WingetVersion::packageVersion, WingetVersions::compare)
        .thenComparing(version -> version.index().channel()));
    return new WingetManifest(manifest.packageIdentifier(), versions);
  }

  /** Merges versions with first-member precedence on the (version, channel) identity. */
  public static WingetManifest merge(String packageIdentifier, List<WingetManifest> manifests) {
    Map<String, WingetVersion> versions = new LinkedHashMap<>();
    for (WingetManifest manifest : manifests) {
      for (WingetVersion version : manifest.versions()) {
        versions.putIfAbsent(identity(version), version);
      }
    }
    List<WingetVersion> merged = new ArrayList<>(versions.values());
    merged.sort(Comparator
        .comparing(WingetVersion::packageVersion, WingetVersions::compare)
        .thenComparing(version -> version.index().channel()));
    return new WingetManifest(packageIdentifier, merged);
  }

  public static String identity(WingetVersion version) {
    return version.packageVersion() + '\u0000' + WingetVersions.channelKey(version.channel());
  }

  private static boolean matches(WingetVersion version, String packageVersion, String channel) {
    return (packageVersion == null || packageVersion.isBlank()
        || version.packageVersion().equalsIgnoreCase(packageVersion))
        && (channel == null || channel.isBlank()
        || version.channel().equalsIgnoreCase(channel));
  }
}
