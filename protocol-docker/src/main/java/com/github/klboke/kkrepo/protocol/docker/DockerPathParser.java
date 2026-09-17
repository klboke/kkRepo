package com.github.klboke.kkrepo.protocol.docker;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DockerPathParser {
  public DockerPath parse(String rawPath) {
    String path = normalize(rawPath);
    if (path.isEmpty()) {
      return new DockerPath(DockerPath.Kind.BASE, null, null, null, null);
    }
    if ("_catalog".equals(path)) {
      return new DockerPath(DockerPath.Kind.CATALOG, null, null, null, null);
    }
    List<String> segments = split(path);
    int manifests = indexOfEndpoint(segments, "manifests", 1);
    if (manifests > 0) {
      return new DockerPath(
          DockerPath.Kind.MANIFEST,
          imageName(segments, manifests),
          segments.get(manifests + 1),
          digestIfPossible(segments.get(manifests + 1)),
          null);
    }
    int upload = indexOfUploadEndpoint(segments);
    if (upload > 0) {
      if (upload + 2 == segments.size()) {
        return new DockerPath(DockerPath.Kind.UPLOAD_START, imageName(segments, upload), null, null, null);
      }
      return new DockerPath(
          DockerPath.Kind.UPLOAD_SESSION,
          imageName(segments, upload),
          null,
          null,
          segments.get(upload + 2));
    }
    int blobs = indexOfEndpoint(segments, "blobs", 1);
    if (blobs > 0) {
      if (blobs + 2 == segments.size()) {
        DockerDigest digest = DockerDigest.parse(segments.get(blobs + 1));
        return new DockerPath(DockerPath.Kind.BLOB, imageName(segments, blobs), null, digest, null);
      }
    }
    int tags = indexOfEndpoint(segments, "tags", 1);
    if (tags > 0 && "list".equals(segments.get(tags + 1))) {
      return new DockerPath(DockerPath.Kind.TAGS, imageName(segments, tags), null, null, null);
    }
    int referrers = indexOfEndpoint(segments, "referrers", 1);
    if (referrers > 0) {
      DockerDigest digest = DockerDigest.parse(segments.get(referrers + 1));
      return new DockerPath(DockerPath.Kind.REFERRERS, imageName(segments, referrers), null, digest, null);
    }
    throw new DockerProtocolException(DockerErrorCode.NAME_INVALID, "unsupported Docker registry path: " + path);
  }

  public DockerPath parsePathBased(String rawPath, String repositoryName) {
    String path = normalize(rawPath);
    if (path.isEmpty() || repositoryName == null || repositoryName.isBlank()) {
      return parse(path);
    }
    String prefix = repositoryName + "/";
    if (path.equals(repositoryName)) {
      return parse("");
    }
    if (!path.startsWith(prefix)) {
      throw new DockerProtocolException(DockerErrorCode.NAME_UNKNOWN, "Docker repository route does not match");
    }
    return parse(path.substring(prefix.length()));
  }

  private static DockerDigest digestIfPossible(String reference) {
    try {
      return DockerDigest.parse(decode(reference));
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static String imageName(List<String> segments, int endExclusive) {
    if (endExclusive <= 0) {
      throw new DockerProtocolException(DockerErrorCode.NAME_INVALID, "image name is required");
    }
    String name = String.join("/", segments.subList(0, endExclusive));
    validateImageName(name);
    return name;
  }

  public static void validateImageName(String name) {
    if (name == null || name.isBlank()) {
      throw new DockerProtocolException(DockerErrorCode.NAME_INVALID, "image name is required");
    }
    String normalized = name.toLowerCase(Locale.ROOT);
    if (!name.equals(normalized) || name.startsWith("/") || name.endsWith("/") || name.contains("//")
        || name.length() > 255) {
      throw new DockerProtocolException(DockerErrorCode.NAME_INVALID, "invalid image name: " + name);
    }
    for (String segment : name.split("/")) {
      if (segment.isBlank() || segment.startsWith(".") || segment.endsWith(".")) {
        throw new DockerProtocolException(DockerErrorCode.NAME_INVALID, "invalid image name: " + name);
      }
    }
  }

  public static void validateTag(String tag) {
    if (tag == null || tag.isBlank() || tag.length() > 128
        || !tag.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) {
      throw new DockerProtocolException(DockerErrorCode.TAG_INVALID, "invalid tag: " + tag);
    }
  }

  public static boolean isDigestReference(String reference) {
    try {
      DockerDigest.parse(reference);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static int indexOfEndpoint(List<String> segments, String value, int trailingSegments) {
    int index = segments.size() - trailingSegments - 1;
    if (index > 0 && value.equals(segments.get(index))) {
      return index;
    }
    return -1;
  }

  private static int indexOfUploadEndpoint(List<String> segments) {
    int uploads = segments.size() >= 2 && "uploads".equals(segments.get(segments.size() - 1))
        ? segments.size() - 1
        : segments.size() >= 3 && "uploads".equals(segments.get(segments.size() - 2))
            ? segments.size() - 2
            : -1;
    int blobs = uploads - 1;
    if (blobs > 0 && "blobs".equals(segments.get(blobs))) {
      return blobs;
    }
    return -1;
  }

  private static List<String> split(String path) {
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (!segment.isBlank()) {
        segments.add(decode(segment));
      }
    }
    return segments;
  }

  private static String normalize(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    return path;
  }

  private static String decode(String value) {
    try {
      return UriPathDecoder.decodeSegment(value);
    } catch (IllegalArgumentException e) {
      throw new DockerProtocolException(DockerErrorCode.NAME_INVALID, "Invalid Docker URI path segment");
    }
  }
}
