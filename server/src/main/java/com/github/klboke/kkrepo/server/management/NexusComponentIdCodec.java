package com.github.klboke.kkrepo.server.management;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Stateless opaque component identifiers backed by the durable repository and component keys. */
@Component
public class NexusComponentIdCodec {
  private static final Pattern REPOSITORY_NAME =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}");
  private static final Pattern PAYLOAD =
      Pattern.compile("([A-Za-z0-9][A-Za-z0-9_.-]{0,199}):([0-9a-f]{16})");
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  public String encode(String repositoryName, long componentId) {
    if (repositoryName == null || !REPOSITORY_NAME.matcher(repositoryName).matches()
        || componentId <= 0) {
      throw new IllegalArgumentException("Repository name and a positive component ID are required");
    }
    return encode(repositoryName + ":" + String.format("%016x", componentId));
  }

  public Decoded decode(String encoded) {
    try {
      Matcher matcher = PAYLOAD.matcher(decodeCanonical(encoded));
      if (!matcher.matches()) throw new IllegalArgumentException("Invalid payload");
      long id = new BigInteger(matcher.group(2), 16).longValueExact();
      if (id <= 0) throw new ArithmeticException("not positive");
      return new Decoded(matcher.group(1), id);
    } catch (IllegalArgumentException | ArithmeticException error) {
      throw new InvalidComponentIdException("Invalid Nexus component ID", error);
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

  public record Decoded(String repositoryName, long componentId) {
  }

  public static final class InvalidComponentIdException extends RuntimeException {
    public InvalidComponentIdException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
