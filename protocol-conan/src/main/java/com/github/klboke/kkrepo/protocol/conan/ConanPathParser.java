package com.github.klboke.kkrepo.protocol.conan;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict parser for the Conan 2 REST routes emitted by the official client. */
public final class ConanPathParser {
  private static final int MAX_FILE_PATH = 1024;

  public ConanPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    final String decoded;
    try {
      decoded = decode(raw, true);
    } catch (IllegalArgumentException invalid) {
      return unknown(raw);
    }
    String path = trimLeadingSlash(decoded);
    if (path.endsWith("/") || path.isEmpty() || path.contains("//")) return unknown(raw);
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (unsafeSegment(segment)) return unknown(raw);
    }
    if (segments.length == 2 && segments[0].equals("v1") && segments[1].equals("ping")) {
      return new ConanPath(ConanPath.Kind.PING, raw, null, null);
    }
    if (segments.length == 3 && segments[0].equals("v2") && segments[1].equals("users")) {
      return switch (segments[2]) {
        case "authenticate" -> new ConanPath(ConanPath.Kind.AUTHENTICATE, raw, null, null);
        case "check_credentials" ->
            new ConanPath(ConanPath.Kind.CHECK_CREDENTIALS, raw, null, null);
        default -> unknown(raw);
      };
    }
    if (segments.length == 3 && segments[0].equals("v2")
        && segments[1].equals("conans") && segments[2].equals("search")) {
      return new ConanPath(ConanPath.Kind.RECIPE_SEARCH, raw, null, null);
    }
    if (segments.length < 6 || !segments[0].equals("v2") || !segments[1].equals("conans")) {
      return unknown(raw);
    }
    ConanReference recipe;
    try {
      recipe = new ConanReference(
          segments[2], segments[3], optional(segments[4]), optional(segments[5]),
          null, null, null);
    } catch (IllegalArgumentException invalid) {
      return unknown(raw);
    }
    int cursor = 6;
    if (segments.length == cursor + 1 && segments[cursor].equals("search")) {
      return result(ConanPath.Kind.PACKAGE_SEARCH, raw, recipe, null);
    }
    if (segments.length == cursor) {
      return result(ConanPath.Kind.RECIPE, raw, recipe, null);
    }
    if (segments.length == cursor + 1 && segments[cursor].equals("latest")) {
      return result(ConanPath.Kind.RECIPE_LATEST, raw, recipe, null);
    }
    if (!segments[cursor].equals("revisions")) return unknown(raw);
    if (segments.length == cursor + 1) {
      return result(ConanPath.Kind.RECIPE_REVISIONS, raw, recipe, null);
    }
    String rrev = segments[cursor + 1];
    try {
      recipe = recipe.recipeRevision(rrev);
    } catch (IllegalArgumentException invalid) {
      return unknown(raw);
    }
    cursor += 2;
    if (segments.length == cursor) {
      return result(ConanPath.Kind.RECIPE_REVISION, raw, recipe, null);
    }
    if (segments.length == cursor + 1 && segments[cursor].equals("search")) {
      return result(ConanPath.Kind.PACKAGE_SEARCH, raw, recipe, null);
    }
    if (segments[cursor].equals("files")) {
      if (segments.length == cursor + 1) {
        return result(ConanPath.Kind.RECIPE_FILES, raw, recipe, null);
      }
      return fileResult(ConanPath.Kind.RECIPE_FILE, raw, recipe, segments, cursor + 1);
    }
    if (!segments[cursor].equals("packages")) return unknown(raw);
    if (segments.length == cursor + 1) {
      return result(ConanPath.Kind.PACKAGES, raw, recipe, null);
    }
    String packageId = segments[cursor + 1];
    try {
      recipe = recipe.packageCoordinate(packageId, null);
    } catch (IllegalArgumentException invalid) {
      return unknown(raw);
    }
    cursor += 2;
    if (segments.length == cursor) {
      return result(ConanPath.Kind.PACKAGE, raw, recipe, null);
    }
    if (segments.length == cursor + 1 && segments[cursor].equals("latest")) {
      return result(ConanPath.Kind.PACKAGE_LATEST, raw, recipe, null);
    }
    if (!segments[cursor].equals("revisions")) return unknown(raw);
    if (segments.length == cursor + 1) {
      return result(ConanPath.Kind.PACKAGE_REVISIONS, raw, recipe, null);
    }
    try {
      recipe = recipe.packageCoordinate(packageId, segments[cursor + 1]);
    } catch (IllegalArgumentException invalid) {
      return unknown(raw);
    }
    cursor += 2;
    if (segments.length == cursor) {
      return result(ConanPath.Kind.PACKAGE_REVISION, raw, recipe, null);
    }
    if (!segments[cursor].equals("files")) return unknown(raw);
    if (segments.length == cursor + 1) {
      return result(ConanPath.Kind.PACKAGE_FILES, raw, recipe, null);
    }
    return fileResult(ConanPath.Kind.PACKAGE_FILE, raw, recipe, segments, cursor + 1);
  }

  public ConanRequestTarget parse(String rawPath, String rawQuery) {
    ConanPath path = parse(rawPath);
    Map<String, String> query = parseQuery(rawQuery);
    return switch (path.kind()) {
      case RECIPE_SEARCH -> {
        rejectUnexpected(query, "q", "ignorecase");
        String pattern = query.get("q");
        if (pattern != null && (pattern.length() > 512 || containsControl(pattern))) {
          throw new IllegalArgumentException("Invalid Conan search pattern");
        }
        yield new ConanRequestTarget(
            path, pattern, booleanValue(query.get("ignorecase"), true), false);
      }
      case PACKAGE_SEARCH -> {
        rejectUnexpected(query, "list_only");
        yield new ConanRequestTarget(
            path, null, true, booleanValue(query.get("list_only"), false));
      }
      default -> {
        if (!query.isEmpty()) {
          throw new IllegalArgumentException("Query parameters are not valid for " + path.kind());
        }
        yield new ConanRequestTarget(path, null, true, false);
      }
    };
  }

  private static ConanPath fileResult(
      ConanPath.Kind kind,
      String raw,
      ConanReference reference,
      String[] segments,
      int start) {
    String file = String.join("/", java.util.Arrays.copyOfRange(segments, start, segments.length));
    if (!validFilePath(file)) return unknown(raw);
    return result(kind, raw, reference, file);
  }

  public static boolean validFilePath(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_FILE_PATH
        || value.startsWith("/") || value.endsWith("/") || value.indexOf('\\') >= 0
        || containsControl(value)) {
      return false;
    }
    String[] segments = value.split("/", -1);
    for (String segment : segments) {
      if (unsafeSegment(segment)) return false;
    }
    return true;
  }

  private static ConanPath result(
      ConanPath.Kind kind, String raw, ConanReference reference, String file) {
    return new ConanPath(kind, raw, reference, file);
  }

  private static ConanPath unknown(String raw) {
    return new ConanPath(ConanPath.Kind.UNKNOWN, raw, null, null);
  }

  private static String optional(String routeValue) {
    return routeValue.equals("_") ? null : routeValue;
  }

  private static boolean unsafeSegment(String value) {
    return value == null || value.isEmpty() || value.equals(".") || value.equals("..")
        || value.indexOf('\\') >= 0 || containsControl(value);
  }

  private static String trimLeadingSlash(String value) {
    String result = value;
    while (result.startsWith("/")) result = result.substring(1);
    return result;
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&", -1)) {
      if (pair.isEmpty()) throw new IllegalArgumentException("Empty Conan query parameter");
      int separator = pair.indexOf('=');
      String name = decode(separator < 0 ? pair : pair.substring(0, separator), false);
      String value = decode(separator < 0 ? "" : pair.substring(separator + 1), false);
      if (name.isEmpty() || containsControl(name) || containsControl(value)
          || result.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("Invalid or duplicate Conan query parameter");
      }
    }
    return Map.copyOf(result);
  }

  private static void rejectUnexpected(Map<String, String> query, String... allowed) {
    java.util.Set<String> names = java.util.Set.of(allowed);
    if (query.keySet().stream().anyMatch(name -> !names.contains(name))) {
      throw new IllegalArgumentException("Unexpected Conan query parameter");
    }
  }

  private static boolean booleanValue(String value, boolean fallback) {
    if (value == null || value.isEmpty()) return fallback;
    if (value.equalsIgnoreCase("true")) return true;
    if (value.equalsIgnoreCase("false")) return false;
    throw new IllegalArgumentException("Invalid Conan boolean query value");
  }

  private static String decode(String raw, boolean path) {
    if (raw.indexOf('#') >= 0 || raw.indexOf('\\') >= 0 || containsControl(raw)) {
      throw new IllegalArgumentException("Unsafe Conan request target");
    }
    byte[] source = raw.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(source.length);
    for (int index = 0; index < source.length; index++) {
      int current = source[index] & 0xff;
      if (current == '+' && !path) {
        decoded.write(' ');
        continue;
      }
      if (current != '%') {
        decoded.write(current);
        continue;
      }
      if (index + 2 >= source.length) {
        throw new IllegalArgumentException("Incomplete percent encoding");
      }
      int high = hex(source[++index]);
      int low = hex(source[++index]);
      if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid percent encoding");
      int octet = (high << 4) | low;
      if (path && (octet == '/' || octet == '\\' || octet == '%')) {
        throw new IllegalArgumentException("Encoded separator or second encoding");
      }
      decoded.write(octet);
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(decoded.toByteArray()))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("Invalid UTF-8 encoding", invalid);
    }
  }

  private static int hex(byte value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(character -> character <= 0x1f || character == 0x7f);
  }
}
