package com.github.klboke.kkrepo.protocol.swift;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict parser for the path and query portion below a Nexus-style repository base URL. */
public final class SwiftPathParser {
  public SwiftPath parse(String rawPath) {
    return parse(rawPath, false);
  }

  /** Parses a request whose method or representation requires release metadata semantics. */
  public SwiftPath parseReleaseMetadata(String rawPath) {
    return parse(rawPath, true);
  }

  private SwiftPath parse(String rawPath, boolean preferReleaseMetadata) {
    String raw = rawPath == null ? "" : rawPath;
    if (raw.isEmpty() || raw.equals("/")) {
      return new SwiftPath(SwiftPath.Kind.ROOT, raw, null, null, null);
    }

    String path;
    try {
      path = decodePath(raw);
    } catch (IllegalArgumentException e) {
      return unknown(raw);
    }
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) {
      return unknown(raw);
    }
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return unknown(raw);
      }
    }

    if (segments.length == 1) {
      return switch (segments[0]) {
        case "login" -> new SwiftPath(SwiftPath.Kind.LOGIN, raw, null, null, null);
        case "identifiers" -> new SwiftPath(
            SwiftPath.Kind.IDENTIFIERS, raw, null, null, null);
        default -> unknown(raw);
      };
    }
    if (segments.length == 2) {
      boolean jsonAlias = segments[1].endsWith(".json");
      String name = jsonAlias ? stripSuffix(segments[1], ".json") : segments[1];
      return validIdentity(segments[0], name)
          ? new SwiftPath(
              SwiftPath.Kind.RELEASE_LIST, raw, segments[0], name, null, jsonAlias)
          : unknown(raw);
    }
    if (segments.length == 3) {
      return parseReleaseResource(raw, segments, preferReleaseMetadata);
    }
    if (segments.length == 4
        && segments[3].equals("Package.swift")
        && validCoordinate(segments[0], segments[1], segments[2])) {
      return new SwiftPath(
          SwiftPath.Kind.MANIFEST, raw, segments[0], segments[1], segments[2]);
    }
    return unknown(raw);
  }

  /**
   * Parses query parameters once and rejects duplicates or endpoint-inapplicable parameters.
   * Invalid query input is a client error and therefore throws rather than becoming UNKNOWN.
   */
  public SwiftRequestTarget parse(String rawPath, String rawQuery) {
    return parseTarget(parse(rawPath), rawQuery);
  }

  public SwiftRequestTarget parseReleaseMetadata(String rawPath, String rawQuery) {
    return parseTarget(parseReleaseMetadata(rawPath), rawQuery);
  }

  private static SwiftRequestTarget parseTarget(SwiftPath path, String rawQuery) {
    Map<String, String> query = parseQuery(rawQuery);
    return switch (path.kind()) {
      case MANIFEST -> {
        rejectUnexpected(query, "swift-version");
        String requested = query.get("swift-version");
        if (requested != null) SwiftToolsVersions.require(requested);
        yield new SwiftRequestTarget(path, requested, null);
      }
      case IDENTIFIERS -> {
        rejectUnexpected(query, "url");
        String url = query.get("url");
        if (url == null || url.isBlank() || containsControl(url)) {
          throw new IllegalArgumentException("Swift identifier lookup requires one url parameter");
        }
        yield new SwiftRequestTarget(path, null, url);
      }
      default -> {
        if (!query.isEmpty()) {
          throw new IllegalArgumentException("Query parameters are not valid for " + path.kind());
        }
        yield new SwiftRequestTarget(path, null, null);
      }
    };
  }

  private static SwiftPath parseReleaseResource(
      String raw, String[] segments, boolean preferReleaseMetadata) {
    String scope = segments[0];
    String name = segments[1];
    String resource = segments[2];
    if (preferReleaseMetadata && validCoordinate(scope, name, resource)) {
      return new SwiftPath(SwiftPath.Kind.RELEASE_METADATA, raw, scope, name, resource);
    }
    if (resource.endsWith(".zip")) {
      String version = stripSuffix(resource, ".zip");
      return validCoordinate(scope, name, version)
          ? new SwiftPath(SwiftPath.Kind.SOURCE_ARCHIVE, raw, scope, name, version)
          : unknown(raw);
    }
    if (validCoordinate(scope, name, resource)) {
      return new SwiftPath(SwiftPath.Kind.RELEASE_METADATA, raw, scope, name, resource);
    }
    String version = stripSuffix(resource, ".json");
    return resource.endsWith(".json") && validCoordinate(scope, name, version)
        ? new SwiftPath(
            SwiftPath.Kind.RELEASE_METADATA, raw, scope, name, version, true)
        : unknown(raw);
  }

  private static boolean validIdentity(String scope, String name) {
    return SwiftScope.isValid(scope) && SwiftPackageName.isValid(name);
  }

  private static boolean validCoordinate(String scope, String name, String version) {
    return validIdentity(scope, name) && SwiftVersions.isValid(version);
  }

  private static String stripSuffix(String value, String suffix) {
    return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
  }

  private static SwiftPath unknown(String raw) {
    return new SwiftPath(SwiftPath.Kind.UNKNOWN, raw, null, null, null);
  }

  private static String decodePath(String raw) {
    if (raw.indexOf('?') >= 0 || raw.indexOf('#') >= 0 || raw.indexOf('\\') >= 0
        || containsControl(raw)) {
      throw new IllegalArgumentException("Unsafe Swift registry path");
    }
    return percentDecode(raw, true);
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&", -1)) {
      if (pair.isEmpty()) {
        throw new IllegalArgumentException("Empty Swift registry query parameter");
      }
      int separator = pair.indexOf('=');
      String rawName = separator < 0 ? pair : pair.substring(0, separator);
      String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
      String name = percentDecode(rawName, false);
      String value = percentDecode(rawValue, false);
      if (name.isEmpty() || containsControl(name) || containsControl(value)
          || result.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("Invalid or duplicate Swift registry query parameter");
      }
    }
    return Map.copyOf(result);
  }

  private static void rejectUnexpected(Map<String, String> query, String expected) {
    if (query.size() > 1 || (!query.isEmpty() && !query.containsKey(expected))) {
      throw new IllegalArgumentException("Unexpected Swift registry query parameter");
    }
  }

  private static String percentDecode(String value, boolean path) {
    byte[] source = value.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(source.length);
    for (int i = 0; i < source.length; i++) {
      int current = source[i] & 0xff;
      if (current != '%') {
        decoded.write(current);
        continue;
      }
      if (i + 2 >= source.length) {
        throw new IllegalArgumentException("Incomplete percent encoding");
      }
      int high = hex(source[++i]);
      int low = hex(source[++i]);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException("Invalid percent encoding");
      }
      int octet = (high << 4) | low;
      if (path && (octet == '/' || octet == '\\' || octet == '%')) {
        throw new IllegalArgumentException("Encoded path separator or second encoding");
      }
      decoded.write(octet);
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(decoded.toByteArray()))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Invalid UTF-8 encoding", e);
    }
  }

  private static int hex(byte value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f);
  }
}
