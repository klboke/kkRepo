package com.github.klboke.kkrepo.server.management;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Stateless opaque identifiers backed by the durable repository and asset database keys. */
@Component
public class NexusAssetIdCodec {
  private static final Pattern REPOSITORY_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}");
  private static final Pattern ASSET_PAYLOAD =
      Pattern.compile("([A-Za-z0-9][A-Za-z0-9_.-]{0,199}):([0-9a-f]{32})");
  private static final Pattern CONTINUATION_PAYLOAD =
      Pattern.compile("v1:([0-9a-f]{16}):([0-9a-f]{16})");
  private static final Pattern COMPONENT_CONTINUATION_PAYLOAD =
      Pattern.compile("v2:([0-9a-f]{32}):([0-9a-f]{16})");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  public String encodeAssetId(String repositoryName, long assetId) {
    if (repositoryName == null || !REPOSITORY_NAME.matcher(repositoryName).matches() || assetId <= 0) {
      throw new IllegalArgumentException("Repository name and a positive asset ID are required");
    }
    return encodeAssetId(repositoryName, String.format("%032x", assetId));
  }

  public String encodeAssetId(String repositoryName, String opaqueId) {
    if (repositoryName == null
        || !REPOSITORY_NAME.matcher(repositoryName).matches()
        || opaqueId == null
        || !opaqueId.matches("[0-9a-f]{32}")) {
      throw new IllegalArgumentException("Repository name and a 32-character opaque ID are required");
    }
    return encode(repositoryName + ":" + opaqueId);
  }

  public DecodedAssetId decodeAssetId(String encoded) {
    try {
      Matcher matcher = ASSET_PAYLOAD.matcher(decodeCanonical(encoded));
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid payload");
      }
      return new DecodedAssetId(matcher.group(1), matcher.group(2));
    } catch (IllegalArgumentException e) {
      throw new InvalidAssetIdException("Invalid Nexus asset ID", e);
    }
  }

  public String encodeContinuation(long repositoryId, long lastAssetId) {
    if (repositoryId <= 0 || lastAssetId <= 0) {
      throw new IllegalArgumentException("Positive repository and asset IDs are required");
    }
    return encode("v1:" + String.format("%016x", repositoryId)
        + ":" + String.format("%016x", lastAssetId));
  }

  public DecodedContinuation decodeContinuation(String encoded) {
    try {
      Matcher matcher = CONTINUATION_PAYLOAD.matcher(decodeCanonical(encoded));
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid payload");
      }
      return new DecodedContinuation(
          positiveLong(matcher.group(1)), positiveLong(matcher.group(2)));
    } catch (IllegalArgumentException e) {
      throw new InvalidContinuationTokenException("Invalid continuation token", e);
    }
  }

  public String encodeComponentContinuation(String queryFingerprint, long lastComponentId) {
    if (queryFingerprint == null
        || !queryFingerprint.matches("[0-9a-f]{32}")
        || lastComponentId <= 0) {
      throw new IllegalArgumentException(
          "A 32-character query fingerprint and positive component ID are required");
    }
    return encode("v2:" + queryFingerprint + ":" + String.format("%016x", lastComponentId));
  }

  public DecodedComponentContinuation decodeComponentContinuation(String encoded) {
    try {
      Matcher matcher = COMPONENT_CONTINUATION_PAYLOAD.matcher(decodeCanonical(encoded));
      if (!matcher.matches()) {
        throw new IllegalArgumentException("Invalid payload");
      }
      return new DecodedComponentContinuation(matcher.group(1), positiveLong(matcher.group(2)));
    } catch (IllegalArgumentException e) {
      throw new InvalidContinuationTokenException("Invalid continuation token", e);
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

  private static long positiveLong(String hex) {
    try {
      long value = new BigInteger(hex, 16).longValueExact();
      if (value <= 0) {
        throw new ArithmeticException("not positive");
      }
      return value;
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Identifier is outside the supported range", e);
    }
  }

  public record DecodedAssetId(String repositoryName, String opaqueId) {}

  public record DecodedContinuation(long repositoryId, long lastAssetId) {}

  public record DecodedComponentContinuation(String queryFingerprint, long lastComponentId) {}

  public static class InvalidAssetIdException extends RuntimeException {
    public InvalidAssetIdException(String message) {
      super(message);
    }

    public InvalidAssetIdException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class InvalidContinuationTokenException extends RuntimeException {
    public InvalidContinuationTokenException(String message) {
      super(message);
    }

    public InvalidContinuationTokenException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
