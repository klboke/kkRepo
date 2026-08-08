package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPackageIdentifiers;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.conda.CondaVersions;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Creates logical Conda package components independently from their physical archive path. */
@Component
final class CondaComponentFactory {
  /**
   * Derives the same name/version coordinate that Nexus extracts from the package request route.
   * This lets proxy assets create their component and Browse hierarchy in the cache-write
   * transaction instead of importing the complete upstream repodata solely for presentation.
   */
  Optional<ProjectedPackage> projectPackagePath(
      RepositoryRuntime runtime, CondaPath path, Instant updatedAt) {
    if (path == null || !path.packageFile()) return Optional.empty();
    Optional<FilenameCoordinate> parsed = parseFilename(path.filename());
    if (parsed.isEmpty()) return Optional.empty();
    FilenameCoordinate coordinate = parsed.orElseThrow();
    ComponentRecord component = component(
        runtime,
        path.channel(),
        path.subdir(),
        coordinate.name(),
        coordinate.version(),
        coordinate.build(),
        coordinate.buildNumber(),
        path.filename(),
        updatedAt);
    return Optional.of(new ProjectedPackage(
        component,
        browsePath(
            path.channel(), path.subdir(), coordinate.name(), coordinate.version(),
            path.filename())));
  }

  ComponentRecord component(
      RepositoryRuntime runtime,
      String channel,
      String subdir,
      String name,
      String version,
      String build,
      long buildNumber,
      String filename,
      Instant updatedAt) {
    requireConda(runtime);
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("channel", channel);
    attributes.put("subdir", subdir);
    attributes.put("build", build);
    attributes.put("buildNumber", buildNumber);
    attributes.put("filename", filename);
    attributes.put("browsePath", browsePath(channel, subdir, name, version, filename));
    return new ComponentRecord(
        null,
        runtime.id(),
        RepositoryFormat.CONDA,
        channel.isBlank() ? subdir : channel + "/" + subdir,
        name,
        version,
        "conda-package",
        PersistenceHashes.sha256("conda", channel, subdir, name, version, build),
        Map.copyOf(attributes),
        updatedAt == null ? Instant.now() : updatedAt);
  }

  String browsePath(
      String channel,
      String subdir,
      String name,
      String version,
      String filename) {
    String prefix = channel == null || channel.isBlank() ? "" : channel + "/";
    return prefix + subdir + "/" + name + "/" + version + "/" + filename;
  }

  private static void requireConda(RepositoryRuntime runtime) {
    if (runtime == null || runtime.format() != RepositoryFormat.CONDA || runtime.isGroup()) {
      throw new IllegalArgumentException("Conda components require a hosted or proxy repository");
    }
  }

  private static Optional<FilenameCoordinate> parseFilename(String filename) {
    if (filename == null) return Optional.empty();
    String suffix = filename.endsWith(".tar.bz2") ? ".tar.bz2"
        : filename.endsWith(".conda") ? ".conda" : null;
    if (suffix == null) return Optional.empty();
    String stem = filename.substring(0, filename.length() - suffix.length());
    int buildSeparator = stem.lastIndexOf('-');
    int versionSeparator = buildSeparator <= 0 ? -1 : stem.lastIndexOf('-', buildSeparator - 1);
    if (versionSeparator <= 0 || buildSeparator <= versionSeparator + 1
        || buildSeparator == stem.length() - 1) {
      return Optional.empty();
    }
    String name = stem.substring(0, versionSeparator);
    String version = stem.substring(versionSeparator + 1, buildSeparator);
    String build = stem.substring(buildSeparator + 1);
    if (!CondaPackageIdentifiers.isUpstreamName(name)
        || !CondaPackageIdentifiers.isBuild(build)) {
      return Optional.empty();
    }
    try {
      CondaVersions.require(version);
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
    return Optional.of(new FilenameCoordinate(name, version, build, buildNumber(build)));
  }

  private static long buildNumber(String build) {
    int separator = build.lastIndexOf('_');
    if (separator < 0 || separator == build.length() - 1) return 0;
    String candidate = build.substring(separator + 1);
    if (!candidate.chars().allMatch(Character::isDigit)) return 0;
    try {
      return Long.parseLong(candidate);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  record ProjectedPackage(ComponentRecord component, String browsePath) { }

  private record FilenameCoordinate(
      String name, String version, String build, long buildNumber) { }
}
