package com.github.klboke.kkrepo.protocol.goartifact;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical Go module version validation, SemVer precedence, and {@code @latest} selection. */
public final class GoVersions {
  private static final String NUMBER = "0|[1-9][0-9]*";
  private static final String PRE_ID = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)";
  private static final Pattern VERSION = Pattern.compile(
      "^v(" + NUMBER + ")\\.(" + NUMBER + ")\\.(" + NUMBER + ")"
          + "(?:-(" + PRE_ID + "(?:\\." + PRE_ID + ")*))?"
          + "(?:\\+(incompatible))?$");
  // Mirrors golang.org/x/mod/module.pseudoVersionRE. A tagged prerelease that merely ends in a
  // timestamp and revision-like token is not necessarily a pseudo-version.
  private static final Pattern PSEUDO = Pattern.compile(
      "^v[0-9]+\\.(?:0\\.0-|[0-9]+\\.[0-9]+-(?:[^+]*\\.)?0\\.)"
          + "([0-9]{14})-([A-Za-z0-9]+)(?:\\+incompatible)?$");

  public static final Comparator<String> COMPARATOR = GoVersions::compare;

  private GoVersions() {
  }

  public static String requireCanonical(String value) {
    if (value == null || !VERSION.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid canonical Go module version: " + value);
    }
    return value;
  }

  public static boolean isCanonical(String value) {
    try {
      requireCanonical(value);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  public static String escape(String version) {
    String value = requireCanonical(version);
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch >= 'A' && ch <= 'Z') {
        escaped.append('!').append(Character.toLowerCase(ch));
      } else {
        escaped.append(ch);
      }
    }
    return escaped.toString();
  }

  public static String unescape(String escapedVersion) {
    if (escapedVersion == null || escapedVersion.isBlank()) {
      throw new IllegalArgumentException("Invalid escaped Go module version: " + escapedVersion);
    }
    StringBuilder decoded = new StringBuilder(escapedVersion.length());
    for (int i = 0; i < escapedVersion.length(); i++) {
      char ch = escapedVersion.charAt(i);
      if (ch == '!') {
        if (++i >= escapedVersion.length()) {
          throw new IllegalArgumentException("Invalid escaped Go module version: " + escapedVersion);
        }
        char lower = escapedVersion.charAt(i);
        if (lower < 'a' || lower > 'z') {
          throw new IllegalArgumentException("Invalid escaped Go module version: " + escapedVersion);
        }
        decoded.append(Character.toUpperCase(lower));
      } else {
        if (ch >= 'A' && ch <= 'Z') {
          throw new IllegalArgumentException("Invalid escaped Go module version: " + escapedVersion);
        }
        decoded.append(ch);
      }
    }
    String version = requireCanonical(decoded.toString());
    if (!escape(version).equals(escapedVersion)) {
      throw new IllegalArgumentException("Non-canonical escaped Go module version: " + escapedVersion);
    }
    return version;
  }

  public static int major(String value) {
    BigInteger parsed = Parsed.parse(value).major();
    try {
      return parsed.intValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Go module major version is too large: " + value, e);
    }
  }

  public static boolean isPrerelease(String value) {
    return Parsed.parse(value).prerelease() != null;
  }

  public static boolean isPseudoVersion(String value) {
    Parsed.parse(value);
    return PSEUDO.matcher(value).matches();
  }

  public static Optional<Instant> pseudoTimestamp(String value) {
    Parsed.parse(value);
    Matcher matcher = PSEUDO.matcher(value);
    if (!matcher.matches()) return Optional.empty();
    String timestamp = matcher.group(1);
    try {
      return Optional.of(Instant.parse(
          timestamp.substring(0, 4) + "-" + timestamp.substring(4, 6) + "-"
              + timestamp.substring(6, 8) + "T" + timestamp.substring(8, 10) + ":"
              + timestamp.substring(10, 12) + ":" + timestamp.substring(12, 14) + "Z"));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  public static int compare(String left, String right) {
    Parsed a = Parsed.parse(left);
    Parsed b = Parsed.parse(right);
    int core = a.major().compareTo(b.major());
    if (core == 0) core = a.minor().compareTo(b.minor());
    if (core == 0) core = a.patch().compareTo(b.patch());
    return core != 0 ? core : comparePrerelease(a.prerelease(), b.prerelease());
  }

  public static List<String> listVersions(Collection<String> versions) {
    if (versions == null) return List.of();
    return versions.stream()
        .filter(Objects::nonNull)
        .map(GoVersions::requireCanonical)
        .filter(version -> !isPseudoVersion(version))
        .distinct()
        .sorted(COMPARATOR.thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  /** Selects releases before prereleases, and pseudo-versions only when no tagged version exists. */
  public static Optional<Candidate> latest(Collection<Candidate> candidates) {
    if (candidates == null || candidates.isEmpty()) return Optional.empty();
    List<Candidate> valid = candidates.stream()
        .filter(Objects::nonNull)
        .peek(candidate -> requireCanonical(candidate.version()))
        .toList();
    Optional<Candidate> release = valid.stream()
        .filter(candidate -> !isPrerelease(candidate.version()))
        .max(candidateComparator());
    if (release.isPresent()) return release;
    Optional<Candidate> taggedPrerelease = valid.stream()
        .filter(candidate -> !isPseudoVersion(candidate.version()))
        .max(candidateComparator());
    if (taggedPrerelease.isPresent()) return taggedPrerelease;
    return valid.stream().max(Comparator
        .comparing(GoVersions::candidateTime)
        .thenComparing(Candidate::version, COMPARATOR)
        .thenComparing(Candidate::version));
  }

  private static Instant candidateTime(Candidate candidate) {
    return pseudoTimestamp(candidate.version())
        .orElse(candidate.time() == null ? Instant.EPOCH : candidate.time());
  }

  private static Comparator<Candidate> candidateComparator() {
    return Comparator.comparing(Candidate::version, COMPARATOR)
        .thenComparing(Candidate::version);
  }

  private static int comparePrerelease(String left, String right) {
    if (left == null && right == null) return 0;
    if (left == null) return 1;
    if (right == null) return -1;
    String[] a = left.split("\\.");
    String[] b = right.split("\\.");
    int max = Math.max(a.length, b.length);
    for (int i = 0; i < max; i++) {
      if (i >= a.length) return -1;
      if (i >= b.length) return 1;
      int compared = compareIdentifier(a[i], b[i]);
      if (compared != 0) return compared;
    }
    return 0;
  }

  private static int compareIdentifier(String left, String right) {
    boolean leftNumeric = left.chars().allMatch(Character::isDigit);
    boolean rightNumeric = right.chars().allMatch(Character::isDigit);
    if (leftNumeric && rightNumeric) return new BigInteger(left).compareTo(new BigInteger(right));
    if (leftNumeric) return -1;
    if (rightNumeric) return 1;
    return left.compareTo(right);
  }

  public record Candidate(String version, Instant time) {
    public Candidate {
      requireCanonical(version);
    }
  }

  private record Parsed(
      BigInteger major,
      BigInteger minor,
      BigInteger patch,
      String prerelease) {
    private static Parsed parse(String value) {
      Matcher matcher = VERSION.matcher(requireCanonical(value));
      if (!matcher.matches()) throw new IllegalArgumentException("Invalid Go module version: " + value);
      return new Parsed(
          new BigInteger(matcher.group(1)),
          new BigInteger(matcher.group(2)),
          new BigInteger(matcher.group(3)),
          matcher.group(4));
    }
  }
}
