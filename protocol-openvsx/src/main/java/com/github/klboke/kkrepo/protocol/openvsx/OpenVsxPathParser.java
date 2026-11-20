package com.github.klboke.kkrepo.protocol.openvsx;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One-pass parser for the Open VSX registry API and the VS Code gallery routes.
 *
 * <p>The parser is deliberately strict: encoded separators, empty segments, control characters
 * and traversal segments are rejected before any storage or upstream access. Unknown paths are
 * reported as {@link OpenVsxPath.Kind#UNKNOWN} so callers can return a protocol-shaped 404.
 */
public final class OpenVsxPathParser {
  private static final Set<String> PLATFORMS = Set.of(
      "win32-x64", "win32-ia32", "win32-arm64", "linux-x64", "linux-arm64", "linux-armhf",
      "alpine-x64", "alpine-arm64", "darwin-x64", "darwin-arm64", "web", "universal");

  public OpenVsxPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    while (raw.startsWith("/")) raw = raw.substring(1);
    while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
    if (raw.isEmpty()) {
      return new OpenVsxPath(OpenVsxPath.Kind.ROOT, "", null, null, null, null, null, null, false);
    }
    String[] rawSegments = raw.split("/", -1);
    List<String> segments = new ArrayList<>(rawSegments.length);
    for (String segment : rawSegments) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException("Invalid Open VSX path: empty segment");
      }
      segments.add(UriPathDecoder.decodeSegment(segment));
    }
    String normalized = String.join("/", segments);

    if (segments.size() == 1 && segments.getFirst().equals("api")) {
      return path(OpenVsxPath.Kind.UNKNOWN, normalized);
    }
    if (segments.size() >= 1 && segments.getFirst().equals("api")) {
      return parseApi(normalized, segments);
    }
    if (segments.size() >= 1 && segments.getFirst().equals("vscode")) {
      return parseVscode(normalized, segments);
    }
    return path(OpenVsxPath.Kind.UNKNOWN, normalized);
  }

  private OpenVsxPath parseApi(String normalized, List<String> segments) {
    if (segments.size() == 2 && segments.get(1).equals("version")) {
      return path(OpenVsxPath.Kind.REGISTRY_VERSION, normalized);
    }
    if (segments.size() == 3 && segments.get(1).equals("-") && segments.get(2).equals("search")) {
      return path(OpenVsxPath.Kind.SEARCH, normalized);
    }
    if (segments.size() == 3 && segments.get(1).equals("-") && segments.get(2).equals("query")) {
      return new OpenVsxPath(OpenVsxPath.Kind.QUERY, normalized, null, null, null, null,
          null, null, false);
    }
    if (segments.size() == 4 && segments.get(1).equals("v2") && segments.get(2).equals("-")
        && segments.get(3).equals("query")) {
      return new OpenVsxPath(OpenVsxPath.Kind.QUERY, normalized, null, null, null, null,
          null, null, true);
    }
    if (segments.size() == 3 && segments.get(1).equals("-") && segments.get(2).equals("publish")) {
      return path(OpenVsxPath.Kind.PUBLISH, normalized);
    }
    if (segments.size() == 2) {
      return namespace(normalized, segments.get(1));
    }
    if (segments.size() < 3) {
      return path(OpenVsxPath.Kind.UNKNOWN, normalized);
    }

    String namespace = segments.get(1);
    String extension = segments.get(2);
    if (!OpenVsxCoordinates.validName(namespace)
        || !OpenVsxCoordinates.validName(extension)) {
      return path(OpenVsxPath.Kind.UNKNOWN, normalized);
    }
    if (segments.size() == 3) {
      return extension(normalized, OpenVsxPath.Kind.EXTENSION, namespace, extension);
    }
    if (segments.size() == 4) {
      String fourth = segments.get(3);
      return switch (fourth) {
        case "versions" -> extension(normalized, OpenVsxPath.Kind.EXTENSION_VERSIONS,
            namespace, extension);
        case "version-references" -> extension(normalized,
            OpenVsxPath.Kind.VERSION_REFERENCES, namespace, extension);
        case "reviews" -> extension(normalized, OpenVsxPath.Kind.REVIEWS, namespace, extension);
        default -> {
          if (PLATFORMS.contains(fourth)) {
            yield platform(normalized, OpenVsxPath.Kind.PLATFORM_EXTENSION, namespace, extension,
                fourth, null);
          }
          if (OpenVsxCoordinates.validVersion(fourth)) {
            yield version(normalized, OpenVsxPath.Kind.EXTENSION_VERSION, namespace, extension,
                fourth, null);
          }
          yield path(OpenVsxPath.Kind.UNKNOWN, normalized);
        }
      };
    }

    String fourth = segments.get(3);
    boolean platform = PLATFORMS.contains(fourth);
    int versionIndex = platform ? 4 : 3;
    if (segments.size() <= versionIndex) {
      return path(OpenVsxPath.Kind.UNKNOWN, normalized);
    }
    if (platform && segments.size() == 5) {
      if ("versions".equals(segments.get(4))) {
        return platform(normalized, OpenVsxPath.Kind.PLATFORM_VERSIONS, namespace, extension,
            fourth, null);
      }
      if ("version-references".equals(segments.get(4))) {
        return platform(normalized, OpenVsxPath.Kind.PLATFORM_VERSION_REFERENCES, namespace,
            extension, fourth, null);
      }
    }
    String version = segments.get(versionIndex);
    if (!OpenVsxCoordinates.validVersion(version)) {
      return path(OpenVsxPath.Kind.UNKNOWN, normalized);
    }
    String tail = segments.size() > versionIndex + 1
        ? String.join("/", segments.subList(versionIndex + 1, segments.size()))
        : "";
    if (tail.isEmpty()) {
      return platform
          ? platform(normalized, OpenVsxPath.Kind.PLATFORM_VERSION, namespace, extension,
              fourth, version)
          : version(normalized, OpenVsxPath.Kind.EXTENSION_VERSION, namespace, extension,
              version, null);
    }
    if (tail.startsWith("file/")) {
      String filePath = tail.substring("file/".length());
      return new OpenVsxPath(
          platform ? OpenVsxPath.Kind.PLATFORM_FILE : OpenVsxPath.Kind.FILE,
          normalized, namespace, extension, version, platform ? fourth : null, filePath, null,
          false);
    }
    return path(OpenVsxPath.Kind.UNKNOWN, normalized);
  }

  private OpenVsxPath parseVscode(String normalized, List<String> segments) {
    if (segments.size() == 2 && segments.get(1).equals("item")) {
      return path(OpenVsxPath.Kind.VSCODE_ITEM, normalized);
    }
    if (segments.size() >= 3 && segments.get(1).equals("gallery")) {
      if (segments.size() == 3 && segments.get(2).equals("extensionquery")) {
        return path(OpenVsxPath.Kind.VSCODE_QUERY, normalized);
      }
      if (segments.size() == 5 && segments.get(4).equals("latest")) {
        String namespace = segments.get(2);
        String extension = segments.get(3);
        if (!OpenVsxCoordinates.validName(namespace)
            || !OpenVsxCoordinates.validName(extension)) {
          return path(OpenVsxPath.Kind.UNKNOWN, normalized);
        }
        return extension(normalized, OpenVsxPath.Kind.VSCODE_LATEST, namespace, extension);
      }
      // vscode/gallery/publishers/{ns}/vsextensions/{ext}/{version}/vspackage
      if (segments.size() == 8 && segments.get(2).equals("publishers")
          && segments.get(4).equals("vsextensions") && segments.get(7).equals("vspackage")) {
        String namespace = segments.get(3);
        String extension = segments.get(5);
        String version = segments.get(6);
        if (!OpenVsxCoordinates.validName(namespace)
            || !OpenVsxCoordinates.validName(extension)
            || !OpenVsxCoordinates.validVersion(version)) {
          return path(OpenVsxPath.Kind.UNKNOWN, normalized);
        }
        return new OpenVsxPath(OpenVsxPath.Kind.VSCODE_VSPACKAGE, normalized, namespace,
            extension, version, null, null, null, false);
      }
    }
    if (segments.size() >= 6 && segments.get(1).equals("asset")) {
      String namespace = segments.get(2);
      String extension = segments.get(3);
      String version = segments.get(4);
      String assetType = segments.get(5);
      if (!OpenVsxCoordinates.validName(namespace)
          || !OpenVsxCoordinates.validName(extension)
          || !OpenVsxCoordinates.validVersion(version)) {
        return path(OpenVsxPath.Kind.UNKNOWN, normalized);
      }
      String filePath = segments.size() > 6
          ? String.join("/", segments.subList(6, segments.size()))
          : "";
      return new OpenVsxPath(OpenVsxPath.Kind.VSCODE_ASSET, normalized, namespace, extension,
          version, null, filePath, assetType, false);
    }
    if (segments.size() >= 5 && segments.get(1).equals("unpkg")) {
      String namespace = segments.get(2);
      String extension = segments.get(3);
      String version = segments.get(4);
      if (!OpenVsxCoordinates.validName(namespace)
          || !OpenVsxCoordinates.validName(extension)
          || !OpenVsxCoordinates.validVersion(version)) {
        return path(OpenVsxPath.Kind.UNKNOWN, normalized);
      }
      String filePath = segments.size() > 5
          ? String.join("/", segments.subList(5, segments.size()))
          : "";
      return new OpenVsxPath(OpenVsxPath.Kind.VSCODE_UNPKG, normalized, namespace, extension,
          version, null, filePath, null, false);
    }
    return path(OpenVsxPath.Kind.UNKNOWN, normalized);
  }

  private static OpenVsxPath namespace(String raw, String namespace) {
    if (!OpenVsxCoordinates.validName(namespace)) {
      return path(OpenVsxPath.Kind.UNKNOWN, raw);
    }
    return new OpenVsxPath(OpenVsxPath.Kind.NAMESPACE, raw, namespace, null, null, null, null,
        null, false);
  }

  private static OpenVsxPath extension(
      String raw, OpenVsxPath.Kind kind, String namespace, String extension) {
    return new OpenVsxPath(kind, raw, namespace, extension, null, null, null, null, false);
  }

  private static OpenVsxPath version(
      String raw, OpenVsxPath.Kind kind, String namespace, String extension, String version,
      String platform) {
    return new OpenVsxPath(kind, raw, namespace, extension, version, platform, null, null, false);
  }

  private static OpenVsxPath platform(
      String raw, OpenVsxPath.Kind kind, String namespace, String extension, String platform,
      String version) {
    return new OpenVsxPath(kind, raw, namespace, extension, version, platform, null, null, false);
  }

  private static OpenVsxPath path(OpenVsxPath.Kind kind, String raw) {
    return new OpenVsxPath(kind, raw, null, null, null, null, null, null, false);
  }
}
