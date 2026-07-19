package com.github.klboke.kkrepo.protocol.npm;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Applies a publish-age gate to an npm packument.
 *
 * <p>The upstream packument remains the source of truth and should be stored unmodified. This
 * class produces a response copy with versions that have not reached the configured age removed,
 * and repairs dist-tags so they never point at a hidden version. Missing or invalid per-version
 * publish timestamps fail closed while the gate is enabled.
 */
public final class NpmMinimumReleaseAge {
  private static final Pattern SEMVER_MAJOR = Pattern.compile("^[v=]?(\\d+)(?:\\.|$)");

  private NpmMinimumReleaseAge() {
  }

  public static Map<String, Object> filter(
      Map<String, Object> packageRoot,
      Instant evaluatedAt,
      int minimumReleaseAgeMinutes) {
    return analyze(packageRoot, minimumReleaseAgeMinutes).filter(packageRoot, evaluatedAt);
  }

  public static Eligibility eligibility(
      Map<String, Object> packageRoot,
      String version,
      Instant evaluatedAt,
      int minimumReleaseAgeMinutes) {
    return analyze(packageRoot, minimumReleaseAgeMinutes).eligibility(version, evaluatedAt);
  }

  /**
   * Returns true when a version became eligible after the last upstream verification. Callers use
   * this to revalidate the raw packument even when its ordinary metadata TTL has not expired.
   */
  public static boolean crossedMaturityBoundary(
      Map<String, Object> packageRoot,
      Instant lastVerifiedAt,
      Instant now,
      int minimumReleaseAgeMinutes) {
    return analyze(packageRoot, minimumReleaseAgeMinutes)
        .crossedMaturityBoundary(lastVerifiedAt, now);
  }

  public static boolean hasCompletePublishTimes(Map<String, Object> packageRoot) {
    return analyze(packageRoot, 1).hasCompletePublishTimes();
  }

  /** Returns every version whose dist.tarball resolves to the requested tarball filename. */
  @SuppressWarnings("unchecked")
  public static List<String> versionsForTarball(
      Map<String, Object> packageRoot,
      String tarballName) {
    return analyze(packageRoot, 1).versionsForTarball(tarballName);
  }

  /**
   * Parses publish timestamps and tarball mappings once so hot request paths can reuse the compact
   * derived state instead of repeatedly walking and parsing a full packument.
   */
  public static Analysis analyze(
      Map<String, Object> packageRoot,
      int minimumReleaseAgeMinutes) {
    return analyze(index(packageRoot), minimumReleaseAgeMinutes);
  }

  /**
   * Extracts the policy-relevant fields from a packument independently of a repository policy.
   * The result can be persisted as a shared, rebuildable index and reused after a node restart.
   */
  public static ReleaseIndex index(Map<String, Object> packageRoot) {
    List<IndexedRelease> indexed = new ArrayList<>();
    for (Map.Entry<String, Object> entry : NpmMetadata.versions(packageRoot).entrySet()) {
      String version = entry.getKey();
      String declaredVersion = version(entry);
      Optional<Instant> publishedAt = publishedAt(packageRoot, version);
      String invalidReason = null;
      if (!version.equals(declaredVersion)) {
        invalidReason = "version metadata does not match packument key";
      } else if (publishedAt.isEmpty()) {
        invalidReason = "missing or invalid publish time";
      }
      indexed.add(new IndexedRelease(
          version,
          publishedAt.orElse(null),
          invalidReason,
          tarball(entry.getValue())));
    }
    return new ReleaseIndex(indexed);
  }

  /** Builds policy state from a durable release index without reading the raw packument blob. */
  public static Analysis analyze(
      ReleaseIndex releaseIndex,
      int minimumReleaseAgeMinutes) {
    boolean disabled = minimumReleaseAgeMinutes <= 0;
    Map<String, Release> releases = new LinkedHashMap<>();
    Map<String, List<String>> tarballVersions = new LinkedHashMap<>();
    List<Instant> transitions = new ArrayList<>();
    boolean complete = true;

    for (IndexedRelease indexed : releaseIndex.releases()) {
      String version = indexed.version();
      Instant publishedAt = indexed.publishedAt();
      Instant availableAt = null;
      String invalidReason = disabled ? null : indexed.invalidReason();
      if (!disabled) {
        if (invalidReason != null || publishedAt == null) {
          complete = false;
        } else {
          try {
            availableAt = publishedAt.plus(Duration.ofMinutes(minimumReleaseAgeMinutes));
            transitions.add(availableAt);
          } catch (ArithmeticException | DateTimeException e) {
            complete = false;
            invalidReason = "invalid publish time";
          }
        }
      }
      releases.put(version, new Release(publishedAt, availableAt, invalidReason));

      String tarball = indexed.tarballName();
      if (tarball != null) {
        tarballVersions.computeIfAbsent(tarball, ignored -> new ArrayList<>()).add(version);
      }
    }
    transitions.sort(Comparator.naturalOrder());
    Map<String, List<String>> immutableTarballs = new LinkedHashMap<>();
    tarballVersions.forEach((name, versions) -> immutableTarballs.put(name, List.copyOf(versions)));
    return new Analysis(
        disabled,
        Collections.unmodifiableMap(releases),
        Collections.unmodifiableMap(immutableTarballs),
        List.copyOf(transitions),
        disabled || complete);
  }

  private static String version(Map.Entry<String, Object> entry) {
    if (entry.getValue() instanceof Map<?, ?> rawVersion) {
      return NpmMetadata.stringValue(rawVersion.get(NpmMetadata.VERSION), entry.getKey());
    }
    return entry.getKey();
  }

  private static String tarball(Object rawVersion) {
    if (!(rawVersion instanceof Map<?, ?> version)) {
      return null;
    }
    Object rawDist = version.get(NpmMetadata.DIST);
    if (!(rawDist instanceof Map<?, ?> dist)) {
      return null;
    }
    Object rawTarball = dist.get(NpmMetadata.TARBALL);
    return rawTarball == null ? null : NpmMetadata.extractTarballName(rawTarball.toString());
  }

  @SuppressWarnings("unchecked")
  private static Optional<Instant> publishedAt(Map<String, Object> packageRoot, String version) {
    if (packageRoot == null || version == null) {
      return Optional.empty();
    }
    Object rawTime = packageRoot.get(NpmMetadata.TIME);
    if (!(rawTime instanceof Map<?, ?> time)) {
      return Optional.empty();
    }
    Object raw = ((Map<String, Object>) time).get(version);
    if (raw == null || raw.toString().isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(
          raw.toString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
    } catch (DateTimeParseException first) {
      try {
        return Optional.of(Instant.parse(raw.toString()));
      } catch (DateTimeParseException ignored) {
        return Optional.empty();
      }
    }
  }

  private static Optional<String> replacementForTag(
      String tag,
      String originalTarget,
      List<String> eligibleVersions,
      Map<String, Object> filteredVersions) {
    if (eligibleVersions.isEmpty() || originalTarget == null) {
      return Optional.empty();
    }
    boolean prerelease = isPrerelease(originalTarget);
    Integer major = major(originalTarget);
    List<String> candidates = eligibleVersions.stream()
        .filter(version -> isPrerelease(version) == prerelease)
        .filter(version -> "latest".equals(tag) || major == null || major.equals(major(version)))
        .toList();
    return candidates.stream().max(Comparator
        .comparing((String version) -> !isDeprecated(filteredVersions.get(version)))
        .thenComparing(ComparableVersion::new));
  }

  private static boolean isDeprecated(Object rawVersion) {
    if (!(rawVersion instanceof Map<?, ?> version)) {
      return false;
    }
    Object deprecated = version.get("deprecated");
    return deprecated != null && !deprecated.toString().isBlank();
  }

  private static boolean isPrerelease(String version) {
    return version != null && version.indexOf('-') >= 0;
  }

  private static Integer major(String version) {
    if (version == null) {
      return null;
    }
    Matcher matcher = SEMVER_MAJOR.matcher(version);
    if (!matcher.find()) {
      return null;
    }
    try {
      return Integer.valueOf(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Immutable, compact policy state derived from one raw packument revision. */
  public static final class Analysis {
    private final boolean disabled;
    private final Map<String, Release> releases;
    private final Map<String, List<String>> tarballVersions;
    private final List<Instant> transitions;
    private final boolean completePublishTimes;

    private Analysis(
        boolean disabled,
        Map<String, Release> releases,
        Map<String, List<String>> tarballVersions,
        List<Instant> transitions,
        boolean completePublishTimes) {
      this.disabled = disabled;
      this.releases = releases;
      this.tarballVersions = tarballVersions;
      this.transitions = transitions;
      this.completePublishTimes = completePublishTimes;
    }

    public Eligibility eligibility(String version, Instant evaluatedAt) {
      if (disabled && releases.containsKey(version)) {
        return new Eligibility(true, null, null, null);
      }
      Release release = releases.get(version);
      if (release == null) {
        return new Eligibility(false, "version is absent from package metadata", null, null);
      }
      if (release.invalidReason() != null || release.availableAt() == null) {
        return new Eligibility(
            false,
            release.invalidReason() == null ? "invalid publish time" : release.invalidReason(),
            release.publishedAt(),
            null);
      }
      boolean eligible = evaluatedAt != null && !evaluatedAt.isBefore(release.availableAt());
      return new Eligibility(
          eligible,
          eligible ? null : "release has not reached the minimum age",
          release.publishedAt(),
          release.availableAt());
    }

    public Map<String, Object> filter(
        Map<String, Object> packageRoot,
        Instant evaluatedAt) {
      Map<String, Object> filtered = NpmMetadata.deepCopy(packageRoot);
      return filterPreparedCopy(filtered, evaluatedAt);
    }

    /**
     * Applies the policy to a caller-owned response copy and returns that same map.
     *
     * <p>This avoids copying a full packument when the caller has already produced an isolated
     * install-v1 projection. The supplied map is mutated and must never be the durable raw
     * packument.
     */
    public Map<String, Object> filterPreparedCopy(
        Map<String, Object> preparedCopy,
        Instant evaluatedAt) {
      if (disabled) {
        return preparedCopy;
      }
      Map<String, Object> versions = NpmMetadata.versions(preparedCopy);
      List<String> eligibleVersions = new ArrayList<>();
      Iterator<Map.Entry<String, Object>> iterator = versions.entrySet().iterator();
      while (iterator.hasNext()) {
        Map.Entry<String, Object> entry = iterator.next();
        if (eligibility(entry.getKey(), evaluatedAt).eligible()) {
          eligibleVersions.add(entry.getKey());
        } else {
          iterator.remove();
        }
      }
      Map<String, Object> tags = filteredDistTags(
          preparedCopy, evaluatedAt, eligibleVersions);
      Map<String, Object> responseTags = NpmMetadata.distTags(preparedCopy);
      responseTags.clear();
      responseTags.putAll(tags);
      Map<String, Object> time = filteredTime(preparedCopy, eligibleVersions);
      if (time != null) {
        preparedCopy.put(NpmMetadata.TIME, time);
      }
      return preparedCopy;
    }

    public Map<String, Object> filteredDistTags(
        Map<String, Object> packageRoot,
        Instant evaluatedAt) {
      if (disabled) {
        return new LinkedHashMap<>(NpmMetadata.distTags(packageRoot));
      }
      List<String> eligibleVersions = visibleVersions(evaluatedAt);
      return filteredDistTags(packageRoot, evaluatedAt, eligibleVersions);
    }

    public List<String> visibleVersions(Instant evaluatedAt) {
      if (disabled) {
        return List.copyOf(releases.keySet());
      }
      List<String> eligibleVersions = new ArrayList<>();
      for (String version : releases.keySet()) {
        if (eligibility(version, evaluatedAt).eligible()) {
          eligibleVersions.add(version);
        }
      }
      return List.copyOf(eligibleVersions);
    }

    /**
     * Removes timestamps for hidden versions while preserving package-level fields such as
     * {@code created} and {@code modified}, plus any upstream extension fields.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> filteredTime(
        Map<String, Object> packageRoot,
        List<String> visibleVersions) {
      Object rawTime = packageRoot.get(NpmMetadata.TIME);
      if (!(rawTime instanceof Map<?, ?> time)) {
        return null;
      }
      Set<String> visible = new HashSet<>(visibleVersions);
      Map<String, Object> filtered = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) time).entrySet()) {
        if (!releases.containsKey(entry.getKey()) || visible.contains(entry.getKey())) {
          filtered.put(entry.getKey(), entry.getValue());
        }
      }
      return filtered;
    }

    public Map<String, Object> filteredDistTags(
        Map<String, Object> packageRoot,
        Instant evaluatedAt,
        List<String> eligibleVersions) {
      Map<String, Object> originalVersions = NpmMetadata.versions(packageRoot);
      Map<String, Object> tags = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : NpmMetadata.distTags(packageRoot).entrySet()) {
        String target = NpmMetadata.stringValue(entry.getValue(), null);
        if (target != null && eligibility(target, evaluatedAt).eligible()) {
          tags.put(entry.getKey(), target);
          continue;
        }
        replacementForTag(entry.getKey(), target, eligibleVersions, originalVersions)
            .ifPresent(replacement -> tags.put(entry.getKey(), replacement));
      }
      return tags;
    }

    public boolean crossedMaturityBoundary(Instant lastVerifiedAt, Instant now) {
      return !disabled
          && lastVerifiedAt != null
          && now != null
          && now.isAfter(lastVerifiedAt)
          && visibilityGeneration(now) > visibilityGeneration(lastVerifiedAt);
    }

    /** Stable response-cache generation; it changes only when another version becomes mature. */
    public int visibilityGeneration(Instant evaluatedAt) {
      if (disabled) {
        return releases.size();
      }
      if (evaluatedAt == null || transitions.isEmpty()) {
        return 0;
      }
      int low = 0;
      int high = transitions.size();
      while (low < high) {
        int mid = (low + high) >>> 1;
        if (!transitions.get(mid).isAfter(evaluatedAt)) {
          low = mid + 1;
        } else {
          high = mid;
        }
      }
      return low;
    }

    public Optional<Instant> nextMaturityAfter(Instant evaluatedAt) {
      if (disabled || transitions.isEmpty()) {
        return Optional.empty();
      }
      int generation = visibilityGeneration(evaluatedAt);
      return generation >= transitions.size()
          ? Optional.empty()
          : Optional.of(transitions.get(generation));
    }

    public boolean hasCompletePublishTimes() {
      return completePublishTimes;
    }

    public List<String> versionsForTarball(String tarballName) {
      String expected = NpmMetadata.extractTarballName(tarballName);
      if (expected == null) {
        return List.of();
      }
      return tarballVersions.getOrDefault(expected, List.of());
    }

    public int versionCount() {
      return releases.size();
    }

    /** Approximate entry count used to bound node-local derived caches. */
    public int cacheWeight() {
      long weight = releases.size();
      for (List<String> versions : tarballVersions.values()) {
        weight += 1L + versions.size();
      }
      return (int) Math.max(1, Math.min(Integer.MAX_VALUE, weight));
    }
  }

  private record Release(
      Instant publishedAt,
      Instant availableAt,
      String invalidReason) {
  }

  /** Policy-independent release data suitable for durable persistence. */
  public record IndexedRelease(
      String version,
      Instant publishedAt,
      String invalidReason,
      String tarballName) {
    public IndexedRelease {
      if (version == null || version.isBlank()) {
        throw new IllegalArgumentException("indexed npm release version is required");
      }
    }
  }

  public record ReleaseIndex(List<IndexedRelease> releases) {
    public ReleaseIndex {
      releases = releases == null ? List.of() : List.copyOf(releases);
    }
  }

  public record Eligibility(
      boolean eligible,
      String reason,
      Instant publishedAt,
      Instant availableAt) {
  }
}
