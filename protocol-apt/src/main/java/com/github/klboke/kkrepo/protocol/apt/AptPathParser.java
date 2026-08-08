package com.github.klboke.kkrepo.protocol.apt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Strict parser for paths below one Debian archive root. */
public final class AptPathParser {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+._-]{0,127}");
  private static final Pattern ARCHITECTURE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern PACKAGE_FILE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9+.:~_-]{0,254}\\.(?:u?deb)");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

  public AptPath parse(String rawPath) {
    return parse(rawPath, false);
  }

  public AptPath parse(String rawPath, boolean flat) {
    String raw = rawPath == null ? "" : rawPath;
    final String decoded;
    try {
      decoded = decode(raw);
    } catch (IllegalArgumentException error) {
      return unknown(raw);
    }
    String path = trimSlashes(decoded);
    if (path.length() > 2048 || path.contains("//")) return unknown(raw);
    if (path.isEmpty()) return path(AptPath.Kind.ROOT, raw, "", null, null, null, null,
        AptPath.Compression.NONE);
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
          || segment.length() > 255) {
        return unknown(raw);
      }
    }
    String filename = segments[segments.length - 1];
    if (segments.length == 1 && filename.equals("gpg.key")) {
      return path(AptPath.Kind.PUBLIC_KEY, raw, path, null, null, null, filename,
          AptPath.Compression.NONE);
    }
    if (flat) return flat(raw, path, segments, filename);
    if (segments.length >= 3 && segments[0].equals("dists")) {
      return structuredMetadata(raw, path, segments, filename);
    }
    if (segments.length >= 4 && segments[0].equals("pool") && isPackageFile(filename)) {
      return path(AptPath.Kind.PACKAGE, raw, path, null, null, packageArchitecture(filename),
          filename, AptPath.Compression.NONE);
    }
    if (isPackageFile(filename)) {
      return path(AptPath.Kind.PACKAGE, raw, path, null, null, packageArchitecture(filename),
          filename, AptPath.Compression.NONE);
    }
    return unknown(raw);
  }

  public static boolean isSafeName(String value) {
    return value != null && NAME.matcher(value).matches();
  }

  public static boolean isArchitecture(String value) {
    return value != null && ARCHITECTURE.matcher(value).matches();
  }

  public static boolean isPackageFile(String value) {
    return value != null && PACKAGE_FILE.matcher(value).matches();
  }

  private static AptPath structuredMetadata(
      String raw, String path, String[] segments, String filename) {
    String distribution = segments[1];
    if (!isSafeName(distribution)) return unknown(raw);
    if (segments.length == 3) {
      return switch (filename) {
        case "InRelease" -> path(AptPath.Kind.IN_RELEASE, raw, path, distribution,
            null, null, filename, AptPath.Compression.NONE);
        case "Release" -> path(AptPath.Kind.RELEASE, raw, path, distribution,
            null, null, filename, AptPath.Compression.NONE);
        case "Release.gpg" -> path(AptPath.Kind.RELEASE_SIGNATURE, raw, path, distribution,
            null, null, filename, AptPath.Compression.NONE);
        default -> unknown(raw);
      };
    }
    if (segments.length >= 7
        && segments[segments.length - 3].equals("by-hash")
        && segments[segments.length - 2].equals("SHA256")
        && SHA256.matcher(filename).matches()) {
      String component = segments.length > 3 ? segments[2] : null;
      if (component != null && !isSafeName(component)) return unknown(raw);
      return path(AptPath.Kind.BY_HASH, raw, path, distribution, component,
          binaryArchitecture(segments), filename.toLowerCase(java.util.Locale.ROOT),
          AptPath.Compression.NONE);
    }
    String component = segments.length > 3 ? segments[2] : null;
    if (component == null || !isSafeName(component)) return unknown(raw);
    String parent = segments.length >= 2 ? segments[segments.length - 2] : "";
    if (parent.startsWith("binary-")) {
      String architecture = parent.substring("binary-".length());
      if (!isArchitecture(architecture)) return unknown(raw);
      AptPath.Compression compression = compression(filename);
      if (baseName(filename).equals("Packages")) {
        return path(AptPath.Kind.PACKAGES, raw, path, distribution, component,
            architecture, filename, compression);
      }
    }
    if (parent.equals("source") && baseName(filename).equals("Sources")) {
      return path(AptPath.Kind.SOURCES, raw, path, distribution, component,
          "source", filename, compression(filename));
    }
    String base = baseName(filename);
    if (base.startsWith("Contents-")) {
      return path(AptPath.Kind.CONTENTS, raw, path, distribution, component,
          base.substring("Contents-".length()), filename, compression(filename));
    }
    if (base.startsWith("Translation-")) {
      return path(AptPath.Kind.TRANSLATION, raw, path, distribution, component,
          null, filename, compression(filename));
    }
    if (path.contains("/Packages.diff/") || path.contains("/Sources.diff/")) {
      return path(AptPath.Kind.PDIFF, raw, path, distribution, component,
          binaryArchitecture(segments), filename, compression(filename));
    }
    return unknown(raw);
  }

  private static AptPath flat(String raw, String path, String[] segments, String filename) {
    if (isPackageFile(filename)) {
      return path(AptPath.Kind.PACKAGE, raw, path, null, null, packageArchitecture(filename),
          filename, AptPath.Compression.NONE);
    }
    String base = baseName(filename);
    if (base.equals("InRelease")) {
      return path(AptPath.Kind.IN_RELEASE, raw, path, null, null, null, filename,
          AptPath.Compression.NONE);
    }
    if (filename.equals("Release.gpg")) {
      return path(AptPath.Kind.RELEASE_SIGNATURE, raw, path, null, null, null, filename,
          AptPath.Compression.NONE);
    }
    if (base.equals("Release")) {
      return path(AptPath.Kind.RELEASE, raw, path, null, null, null, filename,
          AptPath.Compression.NONE);
    }
    if (base.equals("Packages") || base.equals("Sources") || base.startsWith("Contents-")) {
      return path(AptPath.Kind.FLAT_METADATA, raw, path, null, null, null, filename,
          compression(filename));
    }
    if (segments.length >= 3 && segments[segments.length - 3].equals("by-hash")
        && segments[segments.length - 2].equals("SHA256") && SHA256.matcher(filename).matches()) {
      return path(AptPath.Kind.BY_HASH, raw, path, null, null, null,
          filename.toLowerCase(java.util.Locale.ROOT), AptPath.Compression.NONE);
    }
    return unknown(raw);
  }

  private static String binaryArchitecture(String[] segments) {
    for (String segment : segments) {
      if (segment.startsWith("binary-") && segment.length() > "binary-".length()) {
        return segment.substring("binary-".length());
      }
    }
    return null;
  }

  private static String packageArchitecture(String filename) {
    String stem = filename.substring(0, filename.lastIndexOf('.'));
    int separator = stem.lastIndexOf('_');
    if (separator < 0 || separator == stem.length() - 1) return null;
    String architecture = stem.substring(separator + 1);
    return isArchitecture(architecture) ? architecture : null;
  }

  private static String baseName(String filename) {
    for (String suffix : new String[]{".gz", ".bz2", ".xz", ".zst"}) {
      if (filename.endsWith(suffix)) return filename.substring(0, filename.length() - suffix.length());
    }
    return filename;
  }

  private static AptPath.Compression compression(String filename) {
    if (filename.endsWith(".gz")) return AptPath.Compression.GZIP;
    if (filename.endsWith(".bz2")) return AptPath.Compression.BZIP2;
    if (filename.endsWith(".xz")) return AptPath.Compression.XZ;
    if (filename.endsWith(".zst")) return AptPath.Compression.ZSTD;
    return AptPath.Compression.NONE;
  }

  private static AptPath path(
      AptPath.Kind kind, String raw, String normalized, String distribution, String component,
      String architecture, String filename, AptPath.Compression compression) {
    return new AptPath(kind, raw, normalized, distribution, component, architecture, filename, compression);
  }

  private static AptPath unknown(String raw) {
    return path(AptPath.Kind.UNKNOWN, raw, null, null, null, null, null, AptPath.Compression.NONE);
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
        || containsControl(raw)) {
      throw new IllegalArgumentException("Unsafe APT path");
    }
    byte[] source = raw.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(source.length);
    for (int index = 0; index < source.length; index++) {
      int value = source[index] & 0xff;
      if (value != '%') {
        decoded.write(value);
        continue;
      }
      if (index + 2 >= source.length) throw new IllegalArgumentException("Incomplete percent encoding");
      int high = Character.digit((char) source[++index], 16);
      int low = Character.digit((char) source[++index], 16);
      if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid percent encoding");
      int octet = (high << 4) | low;
      if (octet == '/' || octet == '\\' || octet == '%') {
        throw new IllegalArgumentException("Encoded separator or second encoding");
      }
      decoded.write(octet);
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("Invalid UTF-8 encoding", error);
    }
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(character -> character <= 0x1f || character == 0x7f);
  }
}
