package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Streams an untrusted request body to an isolated file while verifying its declared identity. */
@Component
public class ScannerInput {
  public Verified copy(
      InputStream input,
      Path target,
      String expectedSha256,
      Long expectedSize,
      ResourceLimits limits,
      ScanDeadline deadline) {
    return copy(input, target, expectedSha256, expectedSize, limits.maxInputBytes(),
        "INPUT_TOO_LARGE", "Input exceeded the configured limit", deadline);
  }

  Verified copySbom(
      InputStream input,
      Path target,
      String expectedSha256,
      ResourceLimits limits,
      long maxSbomBytes,
      ScanDeadline deadline) {
    if (limits.maxInputBytes() <= maxSbomBytes) {
      return copy(input, target, expectedSha256, null, limits, deadline);
    }
    return copy(input, target, expectedSha256, null, maxSbomBytes,
        "SBOM_TOO_LARGE", "CycloneDX input exceeded the output limit", deadline);
  }

  private Verified copy(
      InputStream input,
      Path target,
      String expectedSha256,
      Long expectedSize,
      long maximum,
      String tooLargeCode,
      String tooLargeMessage,
      ScanDeadline deadline) {
    deadline.check();
    if (expectedSha256 == null
        || !expectedSha256.toLowerCase(java.util.Locale.ROOT).matches("[0-9a-f]{64}")) {
      throw new ScannerRequestException(
          "EXPECTED_SHA256_INVALID", "A valid expected SHA-256 is required", 400, false);
    }
    if (expectedSize != null) requireValidExpectedSize(expectedSize, maximum);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long size = 0;
      byte[] buffer = new byte[64 * 1024];
      try (DigestInputStream source = new DigestInputStream(input, digest);
          var output = Files.newOutputStream(target)) {
        int count;
        while (true) {
          deadline.check();
          count = source.read(buffer);
          deadline.check();
          if (count < 0) break;
          if (count == 0) continue;
          if (count > maximum - size) {
            throw new ScannerRequestException(tooLargeCode, tooLargeMessage, 413, false);
          }
          if (expectedSize != null && count > expectedSize - size) {
            throw sizeMismatch();
          }
          output.write(buffer, 0, count);
          size += count;
          deadline.check();
        }
      }
      deadline.check();
      String actual = HexFormat.of().formatHex(digest.digest());
      if (!MessageDigest.isEqual(
          actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          expectedSha256.toLowerCase(java.util.Locale.ROOT)
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
        throw new ScannerRequestException(
            "INPUT_SHA256_MISMATCH", "Input SHA-256 did not match the declared identity", 422, false);
      }
      if (expectedSize != null && expectedSize != size) {
        throw sizeMismatch();
      }
      return new Verified(target, actual, size);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "INPUT_IO", "Unable to persist scanner input", 503, true, e);
    }
  }

  static void requireValidExpectedSize(long expectedSize, long maximum) {
    if (expectedSize < 0 || expectedSize > maximum) {
      throw new ScannerRequestException(
          "INPUT_SIZE_INVALID", "Declared input size exceeds the configured limit", 413, false);
    }
  }

  private static ScannerRequestException sizeMismatch() {
    return new ScannerRequestException(
        "INPUT_SIZE_MISMATCH", "Input size did not match the declared size", 422, false);
  }

  public record Verified(Path path, String sha256, long size) {}
}
