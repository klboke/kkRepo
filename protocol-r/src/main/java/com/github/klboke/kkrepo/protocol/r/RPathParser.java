package com.github.klboke.kkrepo.protocol.r;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict one-pass parser for CRAN-style repository paths. */
public final class RPathParser {
  private static final String SOURCE_NAMESPACE = "src/contrib";
  private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9.]+(?<!\\.)");
  private static final Pattern SOURCE_FILE = Pattern.compile(
      "([A-Za-z][A-Za-z0-9.]+(?<!\\.))_([0-9]+(?:[.-][0-9]+)+)\\.tar\\.gz");
  private static final Pattern PACKAGE_FILE = Pattern.compile(
      "([A-Za-z][A-Za-z0-9.]+(?<!\\.))_([0-9]+(?:[.-][0-9]+)+)(\\.tar\\.gz|\\.tgz|\\.zip)");
  private static final Pattern WINDOWS_NAMESPACE = Pattern.compile(
      "bin/windows/(?:[A-Za-z0-9_-]+/)?contrib/[0-9]+\\.[0-9]+");
  private static final Pattern MACOS_NAMESPACE = Pattern.compile(
      "bin/macosx/(?:[A-Za-z0-9_-]+/)?contrib/[0-9]+\\.[0-9]+");
  private static final Pattern LINUX_NAMESPACE = Pattern.compile(
      "bin/linux/[A-Za-z0-9_-]+/[0-9]+\\.[0-9]+/src/contrib");
  private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+._@()~-]{0,254}");

  public RPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    final String decoded;
    try {
      decoded = decode(raw);
    } catch (IllegalArgumentException error) {
      return unknown(raw);
    }
    String normalized = trimSlashes(decoded);
    if (normalized.isEmpty()) return path(RPath.Kind.ROOT, raw, "", null, null, null, null);
    if (normalized.length() > 4096 || normalized.contains("//")) return unknown(raw);
    String[] segments = normalized.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
          || !SAFE_SEGMENT.matcher(segment).matches()) {
        return unknown(raw);
      }
    }
    String filename = segments[segments.length - 1];
    String namespace = segments.length == 1
        ? "" : normalized.substring(0, normalized.length() - filename.length() - 1);
    String extension = packageExtension(namespace);
    if (extension != null) {
      if ("PACKAGES".equals(filename)) {
        return path(RPath.Kind.PACKAGES, raw, normalized, namespace, filename, null, null);
      }
      if ("PACKAGES.gz".equals(filename)) {
        return path(RPath.Kind.PACKAGES_GZIP, raw, normalized, namespace, filename, null, null);
      }
      if ("PACKAGES.rds".equals(filename)) {
        return path(RPath.Kind.PACKAGES_RDS, raw, normalized, namespace, filename, null, null);
      }
      Matcher source = PACKAGE_FILE.matcher(filename);
      if (source.matches() && extension.equals(source.group(3)) && RVersions.isValid(source.group(2))) {
        return path(
            SOURCE_NAMESPACE.equals(namespace) ? RPath.Kind.SOURCE_PACKAGE : RPath.Kind.BINARY_PACKAGE,
            raw, normalized, namespace, filename,
            source.group(1), source.group(2));
      }
    }
    if (segments.length == 5 && "src".equals(segments[0]) && "contrib".equals(segments[1])
        && "Archive".equals(segments[2]) && validPackageName(segments[3])) {
      Matcher archived = SOURCE_FILE.matcher(filename);
      if (archived.matches() && archived.group(1).equals(segments[3])
          && RVersions.isValid(archived.group(2))) {
        return path(
            RPath.Kind.ARCHIVE_PACKAGE, raw, normalized, namespace, filename,
            archived.group(1), archived.group(2));
      }
    }
    if (normalized.startsWith("bin/windows/contrib/")
        || normalized.startsWith("bin/macosx/")) {
      return path(RPath.Kind.BINARY, raw, normalized, namespace, filename, null, null);
    }
    if (filename.endsWith(".gz")) {
      return path(RPath.Kind.OTHER_GZIP, raw, normalized, namespace, filename, null, null);
    }
    return path(RPath.Kind.STATIC, raw, normalized, namespace, filename, null, null);
  }

  public RPath parseBrowseProjection(String rawPath) {
    RPath browse = parse(rawPath);
    if (browse.normalized() == null) return unknown(rawPath);
    String[] segments = browse.normalized().split("/", -1);
    if (segments.length < 3) return unknown(rawPath);
    int coordinateOffset = segments.length - 3;
    String namespace = coordinateOffset == 0 ? SOURCE_NAMESPACE
        : String.join("/", java.util.Arrays.copyOf(segments, coordinateOffset));
    RPath candidate = parse(namespace + "/" + segments[coordinateOffset + 2]);
    if (!candidate.hostedPackage()
        || !segments[coordinateOffset].equals(candidate.packageName())
        || !segments[coordinateOffset + 1].equals(candidate.version())) {
      return unknown(rawPath);
    }
    return candidate;
  }

  public static boolean validPackageName(String value) {
    return value != null && value.length() <= 255 && PACKAGE_NAME.matcher(value).matches();
  }

  public static String packageExtension(String namespace) {
    if (SOURCE_NAMESPACE.equals(namespace)) return ".tar.gz";
    if (namespace == null) return null;
    if (WINDOWS_NAMESPACE.matcher(namespace).matches()) {
      return ".zip";
    }
    if (MACOS_NAMESPACE.matcher(namespace).matches()) {
      return ".tgz";
    }
    if (LINUX_NAMESPACE.matcher(namespace).matches()) {
      return ".tar.gz";
    }
    return null;
  }

  public static String packageType(String namespace) {
    if (packageExtension(namespace) == null) throw new IllegalArgumentException("Invalid R namespace");
    return SOURCE_NAMESPACE.equals(namespace) ? "source" : "binary";
  }

  public static String packageFilename(String namespace, String packageName, String version) {
    String source = sourceFilename(packageName, version);
    String extension = packageExtension(namespace);
    if (extension == null) throw new IllegalArgumentException("Invalid R namespace");
    return source.substring(0, source.length() - ".tar.gz".length()) + extension;
  }

  public static String sourceFilename(String packageName, String version) {
    if (!validPackageName(packageName) || !RVersions.isValid(version)) {
      throw new IllegalArgumentException("Invalid R package identity");
    }
    return packageName + "_" + version + ".tar.gz";
  }

  private static RPath path(
      RPath.Kind kind,
      String raw,
      String normalized,
      String namespace,
      String filename,
      String packageName,
      String version) {
    return new RPath(kind, raw, normalized, namespace, filename, packageName, version);
  }

  private static RPath unknown(String raw) {
    return path(RPath.Kind.UNKNOWN, raw, null, null, null, null, null);
  }

  private static String trimSlashes(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '/') start++;
    while (end > start && value.charAt(end - 1) == '/') end--;
    return value.substring(start, end);
  }

  private static String decode(String raw) {
    if (raw.indexOf('?') >= 0 || raw.indexOf('#') >= 0 || raw.indexOf('\\') >= 0
        || raw.chars().anyMatch(value -> value == 0 || value < 0x20 || value == 0x7f)) {
      throw new IllegalArgumentException("Unsafe R repository path");
    }
    String decoded = UriPathDecoder.decodePath(raw);
    if (decoded.indexOf('%') >= 0) {
      throw new IllegalArgumentException("Encoded percent sign is not allowed in this protocol path");
    }
    return decoded;
  }

}
