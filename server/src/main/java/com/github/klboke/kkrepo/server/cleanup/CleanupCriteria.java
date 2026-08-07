package com.github.klboke.kkrepo.server.cleanup;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class CleanupCriteria {
  private static final int MAX_PATTERN_LENGTH = 512;
  private static final int MAX_AGE_DAYS = 36_500;
  private static final int MAX_RETAIN_COUNT = 10_000;

  private final String patternText;
  private final String patternType;
  private final Pattern pattern;
  private final Integer publishedOlderThanDays;
  private final Integer lastDownloadedOlderThanDays;
  private final Integer retainCount;

  private CleanupCriteria(
      String patternText,
      String patternType,
      Pattern pattern,
      Integer publishedOlderThanDays,
      Integer lastDownloadedOlderThanDays,
      Integer retainCount) {
    this.patternText = patternText;
    this.patternType = patternType;
    this.pattern = pattern;
    this.publishedOlderThanDays = publishedOlderThanDays;
    this.lastDownloadedOlderThanDays = lastDownloadedOlderThanDays;
    this.retainCount = retainCount;
  }

  static CleanupCriteria parse(Map<String, Object> raw) {
    Map<String, Object> value = raw == null ? Map.of() : raw;
    String patternText = text(value, "pattern");
    String patternType = text(value, "patternType");
    patternType = patternType == null ? "GLOB" : patternType.trim().toUpperCase(Locale.ROOT);
    if (!patternType.equals("GLOB") && !patternType.equals("REGEX")) {
      throw new CleanupValidationException("criteria.patternType must be GLOB or REGEX");
    }
    if (patternText != null && patternText.length() > MAX_PATTERN_LENGTH) {
      throw new CleanupValidationException("criteria.pattern must be at most 512 characters");
    }
    Pattern pattern = compilePattern(patternText, patternType);
    Integer publishedDays = integer(value, "publishedOlderThanDays");
    Integer downloadedDays = integer(value, "lastDownloadedOlderThanDays");
    Integer retainCount = integer(value, "retainCount");
    requireRange(publishedDays, 0, MAX_AGE_DAYS, "criteria.publishedOlderThanDays");
    requireRange(downloadedDays, 0, MAX_AGE_DAYS, "criteria.lastDownloadedOlderThanDays");
    requireRange(retainCount, 0, MAX_RETAIN_COUNT, "criteria.retainCount");
    if (publishedDays == null && downloadedDays == null && retainCount == null) {
      throw new CleanupValidationException(
          "at least one deletion criterion is required; pattern only narrows the scope");
    }
    return new CleanupCriteria(
        patternText,
        patternType,
        pattern,
        publishedDays,
        downloadedDays,
        retainCount);
  }

  boolean matchesPattern(String coordinate) {
    return pattern == null || pattern.matches(coordinate == null ? "" : coordinate);
  }

  boolean matchesPublishedAt(Instant publishedAt, Instant cutoff) {
    if (publishedOlderThanDays == null) {
      return true;
    }
    return publishedAt != null
        && publishedAt.isBefore(cutoff.minus(publishedOlderThanDays, ChronoUnit.DAYS));
  }

  boolean matchesLastDownloadedAt(Instant lastDownloadedAt, Instant cutoff) {
    if (lastDownloadedOlderThanDays == null) {
      return true;
    }
    if (lastDownloadedAt == null) {
      return false;
    }
    return lastDownloadedAt.isBefore(cutoff.minus(lastDownloadedOlderThanDays, ChronoUnit.DAYS));
  }

  Integer publishedOlderThanDays() {
    return publishedOlderThanDays;
  }

  Integer lastDownloadedOlderThanDays() {
    return lastDownloadedOlderThanDays;
  }

  Integer retainCount() {
    return retainCount;
  }

  Map<String, Object> matchReason(Integer versionRank) {
    Map<String, Object> reason = new LinkedHashMap<>();
    if (pattern != null) {
      reason.put("patternType", patternType);
      reason.put("pattern", patternText);
    }
    if (publishedOlderThanDays != null) {
      reason.put("publishedOlderThanDays", publishedOlderThanDays);
    }
    if (lastDownloadedOlderThanDays != null) {
      reason.put("lastDownloadedOlderThanDays", lastDownloadedOlderThanDays);
    }
    if (retainCount != null) {
      reason.put("retainCount", retainCount);
      reason.put("versionRank", versionRank);
    }
    return Map.copyOf(reason);
  }

  private static Pattern compilePattern(String raw, String type) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String expression = type.equals("GLOB") ? globToRegex(raw.trim()) : raw.trim();
    try {
      return Pattern.compile(expression);
    } catch (PatternSyntaxException e) {
      throw new CleanupValidationException("criteria.pattern is invalid: " + e.getMessage());
    }
  }

  private static String globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char current = glob.charAt(i);
      if (current == '*') {
        regex.append(".*");
      } else if (current == '?') {
        regex.append('.');
      } else {
        if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
          regex.append('\\');
        }
        regex.append(current);
      }
    }
    return regex.append('$').toString();
  }

  private static void requireRange(Integer value, int min, int max, String field) {
    if (value != null && (value < min || value > max)) {
      throw new CleanupValidationException(field + " must be between " + min + " and " + max);
    }
  }

  private static String text(Map<String, Object> value, String key) {
    Object raw = value.get(key);
    return raw == null || raw.toString().isBlank() ? null : raw.toString().trim();
  }

  private static Integer integer(Map<String, Object> value, String key) {
    String raw = text(value, key);
    if (raw == null) {
      return null;
    }
    try {
      return Integer.valueOf(raw);
    } catch (NumberFormatException e) {
      throw new CleanupValidationException(key + " must be an integer");
    }
  }

}
