package com.github.klboke.kkrepo.protocol.huggingface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Strict one-pass parser for the Models routes used by huggingface_hub. */
public final class HuggingFacePathParser {
  private static final String API_PREFIX = "api/models/";
  private static final Pattern REPO_SEGMENT =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,94}[A-Za-z0-9])?");
  private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40,64}");
  private static final int MAX_PATH = 8 * 1024;

  public HuggingFacePath parse(String rawPath) {
    String raw = trimLeadingSlash(rawPath == null ? "" : rawPath);
    if (raw.isEmpty()) return path(HuggingFacePath.Kind.ROOT, raw, "", null, null, null);
    if (!safeRaw(raw)) return unsupported(raw);
    if (raw.startsWith(API_PREFIX)) return parseApi(raw);
    return parseResolve(raw);
  }

  public static boolean isCommit(String revision) {
    return revision != null && COMMIT.matcher(revision).matches();
  }

  private HuggingFacePath parseApi(String raw) {
    String tail = raw.substring(API_PREFIX.length());
    for (String marker : new String[] {
        "/xet-read-token/", "/paths-info/", "/revision/", "/tree/"
    }) {
      int markerAt = tail.indexOf(marker);
      if (markerAt > 0) {
        String repo = decodeRepoId(tail.substring(0, markerAt));
        if (repo == null) return unsupported(raw);
        String after = tail.substring(markerAt + marker.length());
        int slash = after.indexOf('/');
        String rawRevision = slash < 0 ? after : after.substring(0, slash);
        String revision = decodeRevision(rawRevision);
        if (revision == null) return unsupported(raw);
        String nested = slash < 0 ? null : decodeFilePath(after.substring(slash + 1));
        if (slash >= 0 && nested == null) return unsupported(raw);
        HuggingFacePath.Kind kind = switch (marker) {
          case "/xet-read-token/" -> HuggingFacePath.Kind.XET_TOKEN;
          case "/paths-info/" -> HuggingFacePath.Kind.PATHS_INFO;
          case "/revision/" -> HuggingFacePath.Kind.REVISION_INFO;
          case "/tree/" -> HuggingFacePath.Kind.TREE;
          default -> HuggingFacePath.Kind.UNSUPPORTED;
        };
        if ((kind == HuggingFacePath.Kind.PATHS_INFO
            || kind == HuggingFacePath.Kind.REVISION_INFO
            || kind == HuggingFacePath.Kind.XET_TOKEN) && nested != null) {
          return unsupported(raw);
        }
        return path(kind, raw, raw, repo, revision, nested);
      }
    }
    if (tail.endsWith("/refs")) {
      String repo = decodeRepoId(tail.substring(0, tail.length() - "/refs".length()));
      return repo == null ? unsupported(raw)
          : path(HuggingFacePath.Kind.REFS, raw, raw, repo, null, null);
    }
    String repo = decodeRepoId(tail);
    return repo == null ? unsupported(raw)
        : path(HuggingFacePath.Kind.MODEL_INFO, raw, raw, repo, null, null);
  }

  private HuggingFacePath parseResolve(String raw) {
    int markerAt = raw.indexOf("/resolve/");
    if (markerAt <= 0) return unsupported(raw);
    String repo = decodeRepoId(raw.substring(0, markerAt));
    String after = raw.substring(markerAt + "/resolve/".length());
    int slash = after.indexOf('/');
    if (repo == null || slash <= 0 || slash == after.length() - 1) return unsupported(raw);
    String revision = decodeRevision(after.substring(0, slash));
    String file = decodeFilePath(after.substring(slash + 1));
    if (revision == null || file == null) return unsupported(raw);
    return path(HuggingFacePath.Kind.RESOLVE, raw, raw, repo, revision, file);
  }

  private static String decodeRepoId(String raw) {
    String[] segments = raw.split("/", -1);
    if (segments.length < 1 || segments.length > 2) return null;
    StringBuilder result = new StringBuilder();
    for (String segment : segments) {
      String value = decodeSegment(segment, false);
      if (value == null || !REPO_SEGMENT.matcher(value).matches()
          || value.contains("--") || value.contains("..")) return null;
      if (!result.isEmpty()) result.append('/');
      result.append(value);
    }
    return result.toString();
  }

  private static String decodeRevision(String raw) {
    String value = decodeSegment(raw, true);
    if (value == null || value.isBlank() || value.length() > 255
        || value.startsWith("/") || value.endsWith("/") || value.contains("//")) return null;
    for (String segment : value.split("/", -1)) {
      if (!safeValueSegment(segment, 128)) return null;
    }
    return value;
  }

  private static String decodeFilePath(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > MAX_PATH) return null;
    StringBuilder result = new StringBuilder(raw.length());
    for (String segment : raw.split("/", -1)) {
      String value = decodeSegment(segment, false);
      if (!safeValueSegment(value, 255)) return null;
      if (!result.isEmpty()) result.append('/');
      result.append(value);
    }
    return result.toString();
  }

  private static boolean safeValueSegment(String value, int maxLength) {
    return value != null && !value.isBlank() && value.length() <= maxLength
        && !value.equals(".") && !value.equals("..")
        && value.chars().noneMatch(character -> character == 0 || character < 0x20
            || character == 0x7f || character == '\\');
  }

  private static String decodeSegment(String raw, boolean allowEncodedSlash) {
    if (raw == null || raw.isEmpty()) return null;
    byte[] source = raw.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(source.length);
    for (int index = 0; index < source.length; index++) {
      int value = source[index] & 0xff;
      if (value != '%') {
        decoded.write(value);
        continue;
      }
      if (index + 2 >= source.length) return null;
      int high = Character.digit((char) source[++index], 16);
      int low = Character.digit((char) source[++index], 16);
      if (high < 0 || low < 0) return null;
      int octet = (high << 4) | low;
      if (octet == '%' || octet == '\\' || octet == 0 || octet < 0x20
          || (octet == '/' && !allowEncodedSlash)) return null;
      decoded.write(octet);
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
    } catch (CharacterCodingException error) {
      return null;
    }
  }

  private static boolean safeRaw(String raw) {
    return raw.length() <= MAX_PATH && raw.indexOf('?') < 0 && raw.indexOf('#') < 0
        && raw.indexOf('\\') < 0
        && raw.chars().noneMatch(value -> value == 0 || value < 0x20 || value == 0x7f);
  }

  private static String trimLeadingSlash(String value) {
    int start = 0;
    while (start < value.length() && value.charAt(start) == '/') start++;
    return value.substring(start);
  }

  private static HuggingFacePath path(
      HuggingFacePath.Kind kind, String raw, String canonical, String repo,
      String revision, String file) {
    return new HuggingFacePath(kind, raw, canonical, repo, revision, file);
  }

  private static HuggingFacePath unsupported(String raw) {
    return path(HuggingFacePath.Kind.UNSUPPORTED, raw, null, null, null, null);
  }
}
