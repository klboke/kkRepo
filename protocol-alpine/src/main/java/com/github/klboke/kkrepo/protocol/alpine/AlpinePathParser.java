package com.github.klboke.kkrepo.protocol.alpine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict one-pass path parser for Alpine v2 repository routes. */
public final class AlpinePathParser {
  private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9+._-]{0,127}");
  private static final Pattern PACKAGE_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9+._-]{0,127}");
  private static final Pattern ARCHITECTURE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
  private static final Pattern PACKAGE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9+._-]{0,127}-[^/\\\\]{1,255}\\.apk");

  public AlpinePath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    final String decoded;
    try {
      decoded = decode(raw);
    } catch (IllegalArgumentException error) {
      return unknown(raw);
    }
    String normalized = trimSlashes(decoded);
    if (normalized.isEmpty()) return path(AlpinePath.Kind.ROOT, raw, "", null, null, null, null);
    if (normalized.length() > 2048 || normalized.contains("//")) return unknown(raw);
    String[] segments = normalized.split("/", -1);
    if (segments.length != 4) return unknown(raw);
    for (int index = 0; index < 3; index++) {
      String segment = segments[index];
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
          || !SEGMENT.matcher(segment).matches()
          || !segment.equals(segment.toLowerCase(Locale.ROOT))) {
        return unknown(raw);
      }
    }
    if (!ARCHITECTURE.matcher(segments[2]).matches()) return unknown(raw);
    String filename = segments[3];
    if (filename.equals("APKINDEX.tar.gz")) {
      return path(AlpinePath.Kind.INDEX, raw, normalized, segments[0], segments[1], segments[2], filename);
    }
    if (filename.equals("Packages.adb")) {
      return path(AlpinePath.Kind.V3_INDEX, raw, normalized, segments[0], segments[1], segments[2], filename);
    }
    if (filename.startsWith(".") || filename.length() > 255 || !PACKAGE.matcher(filename).matches()) {
      return unknown(raw);
    }
    return path(AlpinePath.Kind.PACKAGE, raw, normalized, segments[0], segments[1], segments[2], filename);
  }

  public static boolean isDistribution(String value) {
    return safeSegment(value);
  }

  public static boolean isChannel(String value) {
    return safeSegment(value);
  }

  public static boolean isArchitecture(String value) {
    return value != null && value.equals(value.toLowerCase(Locale.ROOT))
        && ARCHITECTURE.matcher(value).matches();
  }

  public static String packageFilename(String name, String version) {
    if (name == null || !PACKAGE_NAME.matcher(name).matches()
        || !AlpineVersions.isValid(version)) {
      throw new IllegalArgumentException("Invalid Alpine package identity");
    }
    return name + "-" + version + ".apk";
  }

  private static boolean safeSegment(String value) {
    return value != null && value.equals(value.toLowerCase(Locale.ROOT))
        && SEGMENT.matcher(value).matches();
  }

  private static AlpinePath path(
      AlpinePath.Kind kind,
      String raw,
      String normalized,
      String distribution,
      String channel,
      String architecture,
      String filename) {
    return new AlpinePath(kind, raw, normalized, distribution, channel, architecture, filename);
  }

  private static AlpinePath unknown(String raw) {
    return path(AlpinePath.Kind.UNKNOWN, raw, null, null, null, null, null);
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
      throw new IllegalArgumentException("Unsafe Alpine path");
    }
    byte[] source = raw.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(source.length);
    for (int index = 0; index < source.length; index++) {
      int value = source[index] & 0xff;
      if (value != '%') {
        decoded.write(value);
        continue;
      }
      if (index + 2 >= source.length) throw new IllegalArgumentException("Incomplete encoding");
      int high = Character.digit((char) source[++index], 16);
      int low = Character.digit((char) source[++index], 16);
      if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid encoding");
      int octet = (high << 4) | low;
      if (octet == '/' || octet == '\\' || octet == '%' || octet == 0 || octet < 0x20) {
        throw new IllegalArgumentException("Encoded separator or control");
      }
      decoded.write(octet);
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("Invalid UTF-8", error);
    }
  }
}
