package com.github.klboke.kkrepo.protocol.conda;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict parser for paths below one Nexus-style Conda repository base URL. */
public final class CondaPathParser {
  private static final Pattern CHANNEL_SEGMENT =
      Pattern.compile("[a-z0-9_][a-z0-9._-]{0,127}");
  private static final Pattern CHANNEL_LABEL =
      Pattern.compile("[A-Za-z][0-9A-Za-z_./-]{0,127}");
  private static final Pattern SUBDIR =
      Pattern.compile("(?:noarch|(?=.{1,32}$)[a-z0-9]+-[a-z0-9]+)");

  public CondaPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    final String decoded;
    try {
      decoded = decode(raw);
    } catch (IllegalArgumentException e) {
      return unknown(raw);
    }
    String path = trimSlashes(decoded);
    if (path.isEmpty()) {
      return new CondaPath(
          CondaPath.Kind.ROOT, raw, "", null, null, CondaPath.Encoding.NONE);
    }
    if (path.contains("//")) return unknown(raw);
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return unknown(raw);
    }
    String filename = segments[segments.length - 1];
    if (filename.equals("channeldata.json") || filename.equals("notices.json")) {
      String channel = channel(segments, segments.length - 1);
      if (channel == null) return unknown(raw);
      CondaPath.Kind kind = filename.equals("channeldata.json")
          ? CondaPath.Kind.CHANNELDATA
          : CondaPath.Kind.NOTICES;
      return new CondaPath(kind, raw, channel, null, filename, CondaPath.Encoding.JSON);
    }
    if (segments.length < 2) return unknown(raw);
    String subdir = segments[segments.length - 2];
    if (!SUBDIR.matcher(subdir).matches()) return unknown(raw);
    String channel = channel(segments, segments.length - 2);
    if (channel == null) return unknown(raw);

    CondaPath metadata = metadata(raw, channel, subdir, filename);
    if (metadata != null) return metadata;
    if (isPackage(filename)) {
      return new CondaPath(
          CondaPath.Kind.PACKAGE, raw, channel, subdir, filename, CondaPath.Encoding.NONE);
    }
    return unknown(raw);
  }

  public static boolean isPackage(String filename) {
    if (filename == null || filename.isBlank()
        || !CondaPackageIdentifiers.isFilename(filename)
        || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
        || containsControl(filename)) {
      return false;
    }
    return filename.endsWith(".conda") || filename.endsWith(".tar.bz2");
  }

  public static boolean isSubdir(String value) {
    return value != null && SUBDIR.matcher(value).matches();
  }

  private static CondaPath metadata(
      String raw, String channel, String subdir, String filename) {
    return switch (filename) {
      case "repodata.json" -> path(
          CondaPath.Kind.REPODATA, raw, channel, subdir, filename, CondaPath.Encoding.JSON);
      case "repodata.json.bz2" -> path(
          CondaPath.Kind.REPODATA, raw, channel, subdir, filename, CondaPath.Encoding.BZIP2);
      case "repodata.json.zst" -> path(
          CondaPath.Kind.REPODATA, raw, channel, subdir, filename, CondaPath.Encoding.ZSTD);
      case "current_repodata.json" -> path(
          CondaPath.Kind.CURRENT_REPODATA, raw, channel, subdir, filename, CondaPath.Encoding.JSON);
      case "current_repodata.json.bz2" -> path(
          CondaPath.Kind.CURRENT_REPODATA, raw, channel, subdir, filename, CondaPath.Encoding.BZIP2);
      case "current_repodata.json.zst" -> path(
          CondaPath.Kind.CURRENT_REPODATA, raw, channel, subdir, filename, CondaPath.Encoding.ZSTD);
      case "repodata_shards.msgpack.zst" -> path(
          CondaPath.Kind.SHARDED_REPODATA, raw, channel, subdir, filename,
          CondaPath.Encoding.MSGPACK_ZSTD);
      default -> null;
    };
  }

  private static CondaPath path(
      CondaPath.Kind kind,
      String raw,
      String channel,
      String subdir,
      String filename,
      CondaPath.Encoding encoding) {
    return new CondaPath(kind, raw, channel, subdir, filename, encoding);
  }

  private static String channel(String[] segments, int endExclusive) {
    if (endExclusive == 0) return "";
    String[] parts = Arrays.copyOfRange(segments, 0, endExclusive);
    for (int index = 0; index < parts.length; index++) {
      String part = parts[index];
      if (part.equals("label")) {
        if (index + 1 >= parts.length) return null;
        String label = String.join("/", Arrays.copyOfRange(parts, index + 1, parts.length));
        if (!CHANNEL_LABEL.matcher(label).matches()) return null;
        break;
      }
      if (!CHANNEL_SEGMENT.matcher(part).matches()
          || !part.equals(part.toLowerCase(Locale.ROOT))) {
        return null;
      }
    }
    String channel = String.join("/", parts);
    return channel.length() <= 256 ? channel : null;
  }

  private static String trimSlashes(String value) {
    String result = value;
    while (result.startsWith("/")) result = result.substring(1);
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  private static String decode(String raw) {
    if (raw.indexOf('?') >= 0 || raw.indexOf('#') >= 0 || raw.indexOf('\\') >= 0
        || containsControl(raw)) {
      throw new IllegalArgumentException("Unsafe Conda path");
    }
    byte[] source = raw.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(source.length);
    for (int i = 0; i < source.length; i++) {
      int current = source[i] & 0xff;
      if (current != '%') {
        decoded.write(current);
        continue;
      }
      if (i + 2 >= source.length) throw new IllegalArgumentException("Incomplete percent encoding");
      int high = hex(source[++i]);
      int low = hex(source[++i]);
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

  private static CondaPath unknown(String raw) {
    return new CondaPath(
        CondaPath.Kind.UNKNOWN, raw, null, null, null, CondaPath.Encoding.NONE);
  }
}
