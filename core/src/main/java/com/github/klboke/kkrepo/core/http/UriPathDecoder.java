package com.github.klboke.kkrepo.core.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * One-pass URI decoding, never HTML form decoding: a literal {@code +} stays a plus.
 *
 * <p>Uses the Spring Core decoder that {@code UriUtils.decode} delegates to, without adding a Web
 * dependency to protocol modules. Call only at a raw URI boundary; decoded paths must be reused by
 * authorization, dispatch and storage rather than decoded again. This utility has no mutable state
 * or node-local identity, so every replica derives the same path from the same request.
 */
public final class UriPathDecoder {
  private static final Pattern ENCODED_BYTES = Pattern.compile("(?:%[0-9a-fA-F]{2})+");

  private UriPathDecoder() {}

  /** Decodes an opaque URI component; its protocol still owns separator and identity validation. */
  public static String decodeComponent(String raw) {
    String decoded;
    try {
      decoded = StringUtils.uriDecode(raw, StandardCharsets.UTF_8);
      // Spring replaces malformed UTF-8. Preserve strict rejection in protocols that already
      // require it, without rejecting an actual, correctly encoded U+FFFD in a filename.
      if (decoded.indexOf('\uFFFD') >= 0) {
        var matcher = ENCODED_BYTES.matcher(raw);
        while (matcher.find()) {
          byte[] bytes = StringUtils.uriDecode(matcher.group(), StandardCharsets.ISO_8859_1)
              .getBytes(StandardCharsets.ISO_8859_1);
          StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes));
        }
      }
    } catch (IllegalArgumentException | CharacterCodingException e) {
      // Do not include the raw component: some protocols carry credentials in URI segments.
      throw new IllegalArgumentException("Invalid URI percent-encoding or UTF-8");
    }
    return decoded;
  }

  /** Decodes one ordinary path segment, preserving literal percent signs after a single pass. */
  public static String decodeSegment(String raw) {
    String decoded = decodeComponent(raw);
    if (decoded.equals(".") || decoded.equals("..")
        || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0
        || decoded.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
      throw new IllegalArgumentException("Invalid URI path segment");
    }
    return decoded;
  }

  /** Splits before decoding so encoded separators cannot introduce new path segments. */
  public static String decodePath(String raw) {
    String[] segments = raw.split("/", -1);
    for (int i = 0; i < segments.length; i++) {
      segments[i] = decodeSegment(segments[i]);
    }
    return String.join("/", segments);
  }
}
