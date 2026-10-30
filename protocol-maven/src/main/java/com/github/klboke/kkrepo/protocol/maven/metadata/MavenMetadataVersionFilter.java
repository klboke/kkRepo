package com.github.klboke.kkrepo.protocol.maven.metadata;

import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml.Parsed;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Removes versions from an artifact-level {@code maven-metadata.xml} and recomputes
 * {@code <latest>} and {@code <release>} so they never name a removed version.
 *
 * <p>Used by proxy repositories to hide versions that are still inside their minimum release age.
 * The output is regenerated with {@link MavenMetadataXml#write}, so callers derive any checksum
 * from the returned bytes instead of reusing the upstream checksum files.
 */
public final class MavenMetadataVersionFilter {
  private static final DateTimeFormatter DOTLESS_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private MavenMetadataVersionFilter() {}

  /**
   * @param xml the filtered document
   * @param kept versions that survived, in upstream order
   * @param removed versions that were dropped, in upstream order
   */
  public record Result(byte[] xml, List<String> kept, List<String> removed) {}

  /**
   * Filters the {@code <versions>} list. Returns empty when {@code xml} is not an artifact-level
   * document (group/plugin level and SNAPSHOT base-version metadata have no {@code <versions>}), or
   * when it lists no versions, so the caller can pass the upstream bytes through untouched.
   */
  public static Optional<Result> retain(byte[] xml, Predicate<String> keep) throws IOException {
    Parsed parsed = MavenMetadataXml.read(xml);
    if (parsed.versions.isEmpty()) {
      return Optional.empty();
    }
    List<String> kept = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    for (String version : parsed.versions) {
      if (version == null || version.isBlank()) {
        continue;
      }
      (keep.test(version) ? kept : removed).add(version);
    }
    String latest = kept.contains(parsed.latest) ? parsed.latest : lastOrNull(kept, false);
    String release = kept.contains(parsed.release) ? parsed.release : lastOrNull(kept, true);
    Instant lastUpdated = parseLastUpdated(parsed.lastUpdated);
    byte[] filtered = MavenMetadataXml.write(Maven2Metadata.artifactLevel(
        lastUpdated, parsed.groupId, parsed.artifactId, latest, release, kept));
    return Optional.of(new Result(filtered, List.copyOf(kept), List.copyOf(removed)));
  }

  private static String lastOrNull(List<String> versions, boolean releasesOnly) {
    for (int i = versions.size() - 1; i >= 0; i--) {
      String candidate = versions.get(i);
      if (!releasesOnly || !candidate.endsWith("-SNAPSHOT")) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * {@code lastUpdated} is optional in the metadata schema. A missing or unparsable value stays
   * omitted: stamping the current time would change the bytes on every request, so a checksum
   * fetched a moment after the metadata would no longer match it.
   */
  private static Instant parseLastUpdated(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(text.trim(), DOTLESS_TIMESTAMP).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
