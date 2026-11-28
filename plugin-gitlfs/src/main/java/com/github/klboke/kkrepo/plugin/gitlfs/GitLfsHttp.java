package com.github.klboke.kkrepo.plugin.gitlfs;

import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsByteRange;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * HTTP details of the Git LFS routes, kept free of any web framework: media type matching, HTTP
 * dates, entity validators and single byte ranges. Each rule mirrors what the host web stack did
 * for the former built-in handler so the external behaviour does not change.
 */
final class GitLfsHttp {
  private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
      .withZone(ZoneId.of("GMT"));
  private static final String[] REQUEST_DATE_FORMATS = {
      "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM dd HH:mm:ss yyyy"};
  private static final String SEPARATORS = "()<>@,;:\\\"/[]?={} \t";

  private GitLfsHttp() {}

  /**
   * Whether {@code contentType} is a valid media type compatible with
   * {@code application/vnd.git-lfs+json}: the exact type, {@code application/*},
   * {@code application/*+json} or {@code *}{@code /*}, parameters ignored.
   */
  static boolean isLfsJson(String contentType) {
    if (contentType == null || contentType.isEmpty()) return false;
    List<String> parts = splitParameters(contentType);
    String fullType = parts.getFirst().trim();
    if (fullType.isEmpty()) return false;
    if (fullType.equals("*")) fullType = "*/*";
    int slash = fullType.indexOf('/');
    if (slash <= 0 || slash == fullType.length() - 1) return false;
    String type = fullType.substring(0, slash).toLowerCase(Locale.ENGLISH);
    String subtype = fullType.substring(slash + 1).toLowerCase(Locale.ENGLISH);
    if (!token(type) || !token(subtype) || (type.equals("*") && !subtype.equals("*"))) return false;
    for (String parameter : parts.subList(1, parts.size())) {
      if (!validParameter(parameter.trim())) return false;
    }
    if (type.equals("*")) return true;
    if (!type.equals("application")) return false;
    if (subtype.equals("vnd.git-lfs+json") || subtype.equals("*")) return true;
    int plus = subtype.lastIndexOf('+');
    return subtype.startsWith("*+") && plus < subtype.length() - 1
        && subtype.substring(plus + 1).equals("json");
  }

  private static List<String> splitParameters(String value) {
    List<String> parts = new ArrayList<>();
    boolean quoted = false;
    int start = 0;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '"') quoted = !quoted;
      else if (current == ';' && !quoted) {
        parts.add(value.substring(start, index));
        start = index + 1;
      }
    }
    parts.add(value.substring(start));
    return parts;
  }

  private static boolean validParameter(String parameter) {
    int equals = parameter.indexOf('=');
    if (parameter.isEmpty() || equals < 0) return true; // ignored, as by the former parser
    String name = parameter.substring(0, equals).trim();
    String value = parameter.substring(equals + 1).trim();
    if (name.isEmpty() || value.isEmpty() || !token(name)) return false;
    boolean quotedValue = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    if (name.equalsIgnoreCase("charset")) {
      try {
        Charset.forName(quotedValue ? value.substring(1, value.length() - 1) : value);
        return true;
      } catch (IllegalArgumentException unsupported) {
        return false;
      }
    }
    if (name.equalsIgnoreCase("q")) {
      try {
        double quality = Double.parseDouble(quotedValue ? value.substring(1, value.length() - 1) : value);
        return quality >= 0 && quality <= 1;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return quotedValue || token(value);
  }

  private static boolean token(String value) {
    if (value.isEmpty()) return false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current < 32 || current >= 127 || SEPARATORS.indexOf(current) >= 0) return false;
    }
    return true;
  }

  /** IMF-fixdate rendering, for example {@code Thu, 01 Jan 2026 00:00:00 GMT}. */
  static String httpDate(Instant instant) {
    return HTTP_DATE.format(instant);
  }

  /** Strong entity tag: the value is quoted unless it already is (or is weak). */
  static String entityTag(String etag) {
    String trimmed = etag.trim();
    if (trimmed.startsWith("\"") || trimmed.startsWith("W/\"") || trimmed.startsWith("w/\"")) {
      return trimmed;
    }
    return "\"" + trimmed + "\"";
  }

  /**
   * Whether a GET or HEAD for an entity with these validators is answered with 304. If-None-Match,
   * when present, decides alone; otherwise If-Modified-Since is compared at second precision.
   */
  static boolean notModified(String ifNoneMatch, String ifModifiedSince, String etag,
      Instant lastModified) {
    if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
      String expected = normalizeEntityTag(etag);
      for (String candidate : ifNoneMatch.split(",")) {
        String normalized = normalizeEntityTag(candidate);
        if ("*".equals(normalized) || (!expected.isEmpty() && expected.equals(normalized))) return true;
      }
      return false;
    }
    if (lastModified == null || ifModifiedSince == null) return false;
    Instant requested = parseHttpDate(ifModifiedSince);
    if (requested == null) return false;
    return !lastModified.truncatedTo(ChronoUnit.SECONDS)
        .isAfter(requested.truncatedTo(ChronoUnit.SECONDS));
  }

  private static String normalizeEntityTag(String etag) {
    if (etag == null) return "";
    String normalized = etag.trim();
    if (normalized.startsWith("W/") || normalized.startsWith("w/")) {
      normalized = normalized.substring(2).trim();
    }
    if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    return normalized;
  }

  /** Parses the three HTTP date formats; {@code null} when the value is not a date. */
  static Instant parseHttpDate(String value) {
    for (String pattern : REQUEST_DATE_FORMATS) {
      SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
      format.setTimeZone(TimeZone.getTimeZone("GMT"));
      try {
        return format.parse(value).toInstant();
      } catch (ParseException ignored) {
        // Try the next format.
      }
    }
    return null;
  }

  /**
   * Resolves a {@code Range} header holding one byte range against an entity of {@code size} bytes.
   * Supports {@code first-last}, open-ended {@code first-} and suffix {@code -length} ranges; the
   * last position is clamped to the entity.
   *
   * @return the satisfiable range, or {@code null} when the header is malformed, holds no or several
   *     ranges, or cannot be satisfied (the caller answers 416)
   */
  static GitLfsByteRange singleRange(String header, long size) {
    if (header == null || header.isEmpty() || !header.startsWith("bytes=")) return null;
    List<String> ranges = new ArrayList<>();
    for (String token : header.substring("bytes=".length()).split(",")) {
      if (!token.trim().isEmpty()) ranges.add(token.trim());
    }
    if (ranges.size() != 1 || size == 0) return null;
    String range = ranges.getFirst();
    int dash = range.indexOf('-');
    long start;
    long end;
    try {
      if (dash > 0) {
        start = Long.parseLong(range, 0, dash, 10);
        Long last = dash < range.length() - 1 ? Long.parseLong(range, dash + 1, range.length(), 10) : null;
        if (start < 0 || (last != null && last < start)) return null;
        end = last != null && last < size ? last : size - 1;
      } else if (dash == 0) {
        long suffix = Long.parseLong(range, 1, range.length(), 10);
        if (suffix < 0) return null;
        start = suffix < size ? size - suffix : 0;
        end = size - 1;
      } else {
        return null;
      }
    } catch (NumberFormatException invalid) {
      return null;
    }
    if (start >= size || end < start) return null;
    return new GitLfsByteRange(start, end - start + 1);
  }
}
