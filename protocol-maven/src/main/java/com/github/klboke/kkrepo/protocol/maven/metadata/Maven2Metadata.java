package com.github.klboke.kkrepo.protocol.maven.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * In-memory model of a Maven 2 {@code maven-metadata.xml}. Three flavors: GROUP (plugin prefix
 * index — rarely used here but kept for parity), ARTIFACT (versions list per GA), and
 * BASE_VERSION (snapshot timestamping per GAV-SNAPSHOT).
 */
public final class Maven2Metadata {

  public enum Level { GROUP, ARTIFACT, BASE_VERSION }

  public record Plugin(String artifactId, String prefix, String name) {}

  public record Snapshot(Instant lastUpdated, String extension, String classifier, String version) {}

  public record BaseVersions(String latest, String release, List<String> versions) {}

  public record Snapshots(Long timestamp, int buildNumber, List<Snapshot> snapshots) {}

  private final Level level;
  private final Instant lastUpdated;
  private final String groupId;
  private final String artifactId;
  private final String version;
  private final List<Plugin> plugins;
  private final BaseVersions baseVersions;
  private final Snapshots snapshots;

  private Maven2Metadata(Level level, Instant lastUpdated, String groupId, String artifactId,
      String version, List<Plugin> plugins, BaseVersions baseVersions, Snapshots snapshots) {
    this.level = Objects.requireNonNull(level);
    this.lastUpdated = lastUpdated;
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.version = version;
    this.plugins = plugins == null ? null : List.copyOf(plugins);
    this.baseVersions = baseVersions;
    this.snapshots = snapshots;
  }

  public Level level() { return level; }
  /** Null only for documents that omit the optional {@code <lastUpdated>} element. */
  public Instant lastUpdated() { return lastUpdated; }
  public String groupId() { return groupId; }
  public String artifactId() { return artifactId; }
  public String version() { return version; }
  public List<Plugin> plugins() { return plugins; }
  public BaseVersions baseVersions() { return baseVersions; }
  public Snapshots snapshots() { return snapshots; }

  public static Maven2Metadata groupLevel(Instant lastUpdated, List<Plugin> plugins) {
    return new Maven2Metadata(Level.GROUP, lastUpdated, null, null, null, plugins, null, null);
  }

  public static Maven2Metadata artifactLevel(Instant lastUpdated, String groupId,
      String artifactId, String latest, String release, List<String> versions) {
    return new Maven2Metadata(Level.ARTIFACT, lastUpdated, groupId, artifactId, null, null,
        new BaseVersions(latest, release, List.copyOf(versions)), null);
  }

  public static Maven2Metadata baseVersionLevel(Instant lastUpdated, String groupId,
      String artifactId, String version, Long timestamp, int buildNumber, List<Snapshot> snapshots) {
    return new Maven2Metadata(Level.BASE_VERSION, lastUpdated, groupId, artifactId, version, null,
        null, new Snapshots(timestamp, buildNumber, snapshots == null ? List.of() : List.copyOf(snapshots)));
  }
}
