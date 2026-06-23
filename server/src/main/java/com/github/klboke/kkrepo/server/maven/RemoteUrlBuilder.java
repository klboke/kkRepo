package com.github.klboke.kkrepo.server.maven;

import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class RemoteUrlBuilder {
  private RemoteUrlBuilder() {
  }

  public static URI repositoryPath(String base, String path) {
    URI baseUri = repositoryBase(base);
    String normalizedPath = path == null ? "" : path;
    return baseUri.resolve(encodePath(normalizedPath));
  }

  public static URI repositoryPathWithQuery(String base, String path, String query) {
    URI pathUri = repositoryPath(base, path);
    if (query == null || query.isBlank()) {
      return pathUri;
    }
    return URI.create(pathUri.toString() + "?" + query);
  }

  public static String repositoryPathString(String base, String path) {
    return repositoryPath(base, path).toString();
  }

  public static String repositoryPathWithQueryString(String base, String path, String query) {
    return repositoryPathWithQuery(base, path, query).toString();
  }

  public static URI repositoryBase(String base) {
    if (base == null || base.isBlank()) {
      throw new IllegalStateException("Proxy repository has no remote URL configured");
    }
    URI uri = URI.create(base);
    String raw = uri.toString();
    return raw.endsWith("/") ? uri : URI.create(raw + "/");
  }

  public static String encodePath(String path) {
    if (path == null || path.isEmpty()) {
      return "";
    }
    StringBuilder leadingSlashes = new StringBuilder();
    while (path.startsWith("/")) {
      leadingSlashes.append("%2F");
      path = path.substring(1);
    }
    String[] segments = path.split("/", -1);
    StringBuilder out = new StringBuilder(path.length() + leadingSlashes.length() + 16);
    out.append(leadingSlashes);
    for (int i = 0; i < segments.length; i++) {
      if (i > 0) {
        out.append('/');
      }
      out.append(encodeSegment(segments[i], i == 0));
    }
    return out.toString();
  }

  private static String encodeSegment(String segment, boolean firstSegment) {
    if (".".equals(segment)) {
      return "%2E";
    }
    if ("..".equals(segment)) {
      return "%2E%2E";
    }
    StringBuilder out = new StringBuilder(segment.length() + 8);
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      if (c == '%' && i + 2 < segment.length() && isHex(segment.charAt(i + 1)) && isHex(segment.charAt(i + 2))) {
        out.append('%')
            .append(Character.toUpperCase(segment.charAt(i + 1)))
            .append(Character.toUpperCase(segment.charAt(i + 2)));
        i += 2;
      } else if (isAllowedPathChar(c) && !(c == ':' && firstSegment && looksLikeScheme(segment, i))) {
        out.append(c);
      } else {
        appendUtf8Escape(out, c);
      }
    }
    return out.toString();
  }

  private static boolean looksLikeScheme(String segment, int colonIndex) {
    if (colonIndex <= 0) {
      return false;
    }
    char first = segment.charAt(0);
    if (!isAsciiAlpha(first)) {
      return false;
    }
    for (int i = 1; i < colonIndex; i++) {
      char c = segment.charAt(i);
      if (!isAsciiAlpha(c) && !isAsciiDigit(c) && c != '+' && c != '-' && c != '.') {
        return false;
      }
    }
    return true;
  }

  private static void appendUtf8Escape(StringBuilder out, char c) {
    byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
    for (byte b : bytes) {
      out.append('%');
      int value = b & 0xff;
      char hi = Character.toUpperCase(Character.forDigit(value >>> 4, 16));
      char lo = Character.toUpperCase(Character.forDigit(value & 0xf, 16));
      out.append(hi).append(lo);
    }
  }

  private static boolean isAllowedPathChar(char c) {
    return isAsciiAlpha(c)
        || isAsciiDigit(c)
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~'
        || c == '!'
        || c == '$'
        || c == '&'
        || c == '\''
        || c == '('
        || c == ')'
        || c == '*'
        || c == '+'
        || c == ','
        || c == ';'
        || c == '='
        || c == ':'
        || c == '@';
  }

  private static boolean isAsciiAlpha(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9')
        || (c >= 'a' && c <= 'f')
        || (c >= 'A' && c <= 'F');
  }
}
