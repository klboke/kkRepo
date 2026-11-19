package com.github.klboke.kkrepo.protocol.winget;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.Locale;

/** Parsed WinGet REST API route. */
public record WingetPath(Kind kind, String packageIdentifier) {
  public enum Kind {
    INFORMATION,
    MANIFEST_SEARCH,
    PACKAGE_MANIFESTS,
    MANIFEST,
    /** The granular {@code /api/packages} read model is intentionally not implemented. */
    PACKAGES,
    UNKNOWN
  }

  public static WingetPathParser parser() {
    return WingetPathParser.INSTANCE;
  }

  /** Path parser for repository-relative WinGet URLs (the {@code api/} prefix is optional). */
  public static final class WingetPathParser {
    static final WingetPathParser INSTANCE = new WingetPathParser();

    public WingetPath parse(String rawPath) {
      String path = normalize(rawPath);
      if (path.isEmpty()) {
        return new WingetPath(Kind.UNKNOWN, null);
      }
      if (path.startsWith(WingetProtocol.API_PREFIX)) {
        path = path.substring(WingetProtocol.API_PREFIX.length());
      }
      if ("information".equals(path)) {
        return new WingetPath(Kind.INFORMATION, null);
      }
      if ("manifestSearch".equals(path)) {
        return new WingetPath(Kind.MANIFEST_SEARCH, null);
      }
      if ("packageManifests".equals(path)) {
        return new WingetPath(Kind.PACKAGE_MANIFESTS, null);
      }
      if (path.startsWith("packageManifests/")) {
        String encoded = path.substring("packageManifests/".length());
        if (encoded.isEmpty() || encoded.indexOf('/') >= 0) {
          return new WingetPath(Kind.UNKNOWN, null);
        }
        String identifier;
        try {
          identifier = UriPathDecoder.decodeSegment(encoded).trim();
        } catch (IllegalArgumentException invalid) {
          throw WingetException.badRequest("The package identifier is not a valid URI segment");
        }
        if (identifier.isEmpty() || identifier.length() > 128) {
          throw WingetException.badRequest("PackageIdentifier must be between 1 and 128 characters");
        }
        return new WingetPath(Kind.MANIFEST, identifier);
      }
      if ("packages".equals(path) || path.startsWith("packages/")) {
        return new WingetPath(Kind.PACKAGES, null);
      }
      return new WingetPath(Kind.UNKNOWN, null);
    }

    private static String normalize(String rawPath) {
      if (rawPath == null) {
        return "";
      }
      String path = rawPath.trim();
      while (path.startsWith("/")) {
        path = path.substring(1);
      }
      while (path.endsWith("/") && !path.isEmpty()) {
        path = path.substring(0, path.length() - 1);
      }
      return path;
    }
  }

  public static String normalized(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
