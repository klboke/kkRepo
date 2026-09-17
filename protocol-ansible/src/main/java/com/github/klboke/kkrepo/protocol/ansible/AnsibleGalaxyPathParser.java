package com.github.klboke.kkrepo.protocol.ansible;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict parser for Galaxy v3 paths below one Nexus-style repository base URL. */
public final class AnsibleGalaxyPathParser {
  public static final String V3_BASE = "api/v3/";
  public static final String PUBLISHED_COLLECTION_INDEX =
      "plugin/ansible/content/published/collections/index/";
  public static final String LONG_INDEX = V3_BASE + PUBLISHED_COLLECTION_INDEX;
  public static final String ARTIFACT_BASE =
      "api/v3/plugin/ansible/content/published/collections/artifacts/";
  private static final Pattern ARTIFACT = Pattern.compile("[a-z][a-z0-9_]{0,63}-[a-z][a-z0-9_]{0,63}-.+\\.tar\\.gz");

  public AnsibleGalaxyPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    String decoded;
    try {
      decoded = decodePath(raw);
    } catch (IllegalArgumentException e) {
      return unknown(raw);
    }
    while (decoded.startsWith("/")) decoded = decoded.substring(1);
    if (decoded.isEmpty() || decoded.equals("api") || decoded.equals("api/")) {
      return new AnsibleGalaxyPath(
          AnsibleGalaxyPath.Kind.DISCOVERY, raw, null, null, null, null, null, false);
    }
    String path = decoded.endsWith("/") ? decoded.substring(0, decoded.length() - 1) : decoded;
    if (path.isEmpty() || path.contains("//")) return unknown(raw);
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return unknown(raw);
    }

    if (path.equals("api/v3/artifacts/collections")) {
      return new AnsibleGalaxyPath(
          AnsibleGalaxyPath.Kind.PUBLISH, raw, null, null, null, null, null, false);
    }
    if (path.startsWith("api/v3/imports/collections/")) {
      String task = path.substring("api/v3/imports/collections/".length());
      if (validUuid(task)) {
        return new AnsibleGalaxyPath(
            AnsibleGalaxyPath.Kind.IMPORT_TASK, raw, null, null, null, null, task, false);
      }
      return unknown(raw);
    }
    if (path.startsWith(ARTIFACT_BASE)) {
      String filename = path.substring(ARTIFACT_BASE.length());
      if (isArtifactFilename(filename)) {
        return new AnsibleGalaxyPath(
            AnsibleGalaxyPath.Kind.ARTIFACT, raw, null, null, null, filename, null, false);
      }
      return unknown(raw);
    }
    if (path.startsWith("api/v3/collections/")) {
      return parseCollection(raw, path.substring("api/v3/collections/".length()), false);
    }
    if (path.startsWith(LONG_INDEX)) {
      return parseCollection(raw, path.substring(LONG_INDEX.length()), true);
    }
    return unknown(raw);
  }

  public AnsibleGalaxyRequestTarget parse(String rawPath, String rawQuery) {
    AnsibleGalaxyPath path = parse(rawPath);
    Map<String, String> query = parseQuery(rawQuery);
    if (path.kind() != AnsibleGalaxyPath.Kind.VERSION_LIST) {
      if (!query.isEmpty()) {
        throw new IllegalArgumentException("Query parameters are not valid for " + path.kind());
      }
      return new AnsibleGalaxyRequestTarget(path, 100, 0);
    }
    for (String name : query.keySet()) {
      if (!name.equals("limit") && !name.equals("offset")) {
        throw new IllegalArgumentException("Unexpected Galaxy v3 query parameter: " + name);
      }
    }
    int limit = positiveInt(query.getOrDefault("limit", "100"), "limit", 1, 1000);
    int offset = positiveInt(query.getOrDefault("offset", "0"), "offset", 0, Integer.MAX_VALUE);
    return new AnsibleGalaxyRequestTarget(path, limit, offset);
  }

  public static boolean isArtifactFilename(String filename) {
    return filename != null && filename.length() <= 255 && ARTIFACT.matcher(filename).matches()
        && filename.indexOf('/') < 0 && filename.indexOf('\\') < 0;
  }

  public static String canonicalFilename(String namespace, String name, String version) {
    return AnsibleGalaxyNames.requireNamespace(namespace) + "-"
        + AnsibleGalaxyNames.requireCollection(name) + "-"
        + AnsibleGalaxyVersions.require(version) + ".tar.gz";
  }

  private static AnsibleGalaxyPath parseCollection(String raw, String suffix, boolean longAlias) {
    String[] parts = suffix.split("/", -1);
    if (parts.length < 2
        || !AnsibleGalaxyNames.isValidNamespace(parts[0])
        || !AnsibleGalaxyNames.isValidCollection(parts[1])) {
      return unknown(raw);
    }
    if (parts.length == 2) {
      return collection(AnsibleGalaxyPath.Kind.COLLECTION, raw, parts, null, longAlias);
    }
    if (parts.length == 3 && parts[2].equals("versions")) {
      return collection(AnsibleGalaxyPath.Kind.VERSION_LIST, raw, parts, null, longAlias);
    }
    if (parts.length == 4 && parts[2].equals("versions")
        && AnsibleGalaxyVersions.isValid(parts[3])) {
      return collection(AnsibleGalaxyPath.Kind.VERSION_DETAIL, raw, parts, parts[3], longAlias);
    }
    return unknown(raw);
  }

  private static AnsibleGalaxyPath collection(
      AnsibleGalaxyPath.Kind kind, String raw, String[] parts, String version, boolean longAlias) {
    return new AnsibleGalaxyPath(
        kind, raw, parts[0], parts[1], version, null, null, longAlias);
  }

  private static AnsibleGalaxyPath unknown(String raw) {
    return new AnsibleGalaxyPath(
        AnsibleGalaxyPath.Kind.UNKNOWN, raw, null, null, null, null, null, false);
  }

  private static boolean validUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value.toLowerCase(java.util.Locale.ROOT));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static int positiveInt(String value, String label, int minimum, int maximum) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid Galaxy v3 " + label + ": " + value, e);
    }
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&", -1)) {
      if (pair.isEmpty()) throw new IllegalArgumentException("Empty Galaxy v3 query parameter");
      int separator = pair.indexOf('=');
      String name = percentDecode(separator < 0 ? pair : pair.substring(0, separator), false);
      String value = percentDecode(separator < 0 ? "" : pair.substring(separator + 1), false);
      if (name.isEmpty() || containsControl(name) || containsControl(value)
          || result.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("Invalid or duplicate Galaxy v3 query parameter");
      }
    }
    return Map.copyOf(result);
  }

  private static String decodePath(String raw) {
    if (raw.indexOf('?') >= 0 || raw.indexOf('#') >= 0 || raw.indexOf('\\') >= 0
        || containsControl(raw)) {
      throw new IllegalArgumentException("Unsafe Galaxy v3 path");
    }
    return percentDecode(raw, true);
  }

  private static String percentDecode(String value, boolean path) {
    String decoded = path ? UriPathDecoder.decodePath(value) : UriPathDecoder.decodeComponent(value);
    if (path && decoded.indexOf('%') >= 0) {
      throw new IllegalArgumentException("Encoded percent sign is not allowed in this protocol path");
    }
    return decoded;
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f);
  }
}
