package com.github.klboke.kkrepo.protocol.maven.path;

import java.util.Optional;

/** Canonical identity shared by timestamped artifacts and all their subordinate files. */
public record MavenSnapshotBuild(
    String groupId, String artifactId, String baseVersion, String version,
    long timestamp, int buildNumber, String variant, boolean primary) {
  public static Optional<MavenSnapshotBuild> parse(String path) {
    MavenPath parsed = new MavenPathParser().parsePath(path);
    Coordinates c = parsed.coordinates();
    if (c == null || !c.snapshot() || c.timestamp() == null || c.buildNumber() == null
        || c.buildNumber() < 0 || c.version().equals(c.baseVersion())) return Optional.empty();
    // Classifiers/extensions are exact protocol identities, independent of database collation.
    Coordinates main = parsed.main().coordinates();
    String classifier = main.classifier() == null ? "" : main.classifier();
    String variant = classifier.length() + ":" + classifier + ":" + main.extension();
    return Optional.of(new MavenSnapshotBuild(c.groupId(), c.artifactId(), c.baseVersion(),
        c.version(), c.timestamp(), c.buildNumber(), variant, !parsed.isSubordinate()));
  }

  public String familyPath() {
    return groupId.replace('.', '/') + "/" + artifactId + "/" + baseVersion;
  }
}
