package com.github.klboke.kkrepo.server.management;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Opaque Nexus-style identifiers for staged assets and components.
 *
 * <p>Staged rows do not have live asset/component ids, so the identifier carries the durable
 * session and item identity. It is stateless, canonical, and safe to expose to clients.
 */
@Component
public final class NexusStagingIdCodec {
  private static final Pattern ASSET = Pattern.compile("sa:([0-9a-f]{16}):([0-9a-f]{16})");
  private static final Pattern COMPONENT =
      Pattern.compile("sc:([0-9a-f]{16}):([0-9a-f]{16}):([0-9a-f]{32})");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  public boolean isStagingId(String id) {
    if (id == null || id.isBlank()) return false;
    try {
      String payload = decodeCanonical(id);
      return payload.startsWith("sa:") || payload.startsWith("sc:");
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  public String encodeAsset(long sessionId, long itemId) {
    if (sessionId <= 0 || itemId <= 0) {
      throw new IllegalArgumentException("Positive staging session and item ids are required");
    }
    return encode("sa:" + hex16(sessionId) + ":" + hex16(itemId));
  }

  public DecodedAsset decodeAsset(String id) {
    try {
      Matcher matcher = ASSET.matcher(decodeCanonical(id));
      if (!matcher.matches()) throw new IllegalArgumentException("Invalid staged asset id");
      return new DecodedAsset(positive(matcher.group(1)), positive(matcher.group(2)));
    } catch (IllegalArgumentException error) {
      throw new InvalidStagingIdException("Invalid staged asset id", error);
    }
  }

  public String encodeComponent(
      long sessionId, long repositoryId, String namespace, String name, String version, String kind) {
    if (sessionId <= 0 || repositoryId <= 0) {
      throw new IllegalArgumentException("Positive staging session and repository ids are required");
    }
    return encode("sc:" + hex16(sessionId) + ":" + hex16(repositoryId) + ":"
        + componentIdentity(namespace, name, version, kind));
  }

  public DecodedComponent decodeComponent(String id) {
    try {
      Matcher matcher = COMPONENT.matcher(decodeCanonical(id));
      if (!matcher.matches()) throw new IllegalArgumentException("Invalid staged component id");
      return new DecodedComponent(
          positive(matcher.group(1)), positive(matcher.group(2)), matcher.group(3));
    } catch (IllegalArgumentException error) {
      throw new InvalidStagingIdException("Invalid staged component id", error);
    }
  }

  public static String componentIdentity(
      String namespace, String name, String version, String kind) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String value = String.join("\u0000",
          nullToEmpty(namespace), nullToEmpty(name), nullToEmpty(version), nullToEmpty(kind));
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 32);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String encode(String payload) {
    return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  private static String decodeCanonical(String encoded) {
    if (encoded == null || encoded.isBlank() || encoded.length() > 512) {
      throw new IllegalArgumentException("Invalid opaque identifier");
    }
    byte[] decoded = DECODER.decode(encoded);
    String payload = new String(decoded, StandardCharsets.UTF_8);
    if (!encode(payload).equals(encoded)) {
      throw new IllegalArgumentException("Non-canonical base64url");
    }
    return payload;
  }

  private static String hex16(long value) {
    return String.format("%016x", value);
  }

  private static long positive(String hex) {
    long value = new BigInteger(hex, 16).longValueExact();
    if (value <= 0) throw new ArithmeticException("not positive");
    return value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public record DecodedAsset(long sessionId, long itemId) {
  }

  public record DecodedComponent(long sessionId, long repositoryId, String identity) {
  }

  public static final class InvalidStagingIdException extends RuntimeException {
    public InvalidStagingIdException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
