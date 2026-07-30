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
    deadline.check();
    if (expectedSha256 == null
        || !expectedSha256.toLowerCase(java.util.Locale.ROOT).matches("[0-9a-f]{64}")) {
      throw new ScannerRequestException(
          "EXPECTED_SHA256_INVALID", "A valid expected SHA-256 is required", 400, false);
    }
    long maximum = limits.maxInputBytes();
    if (expectedSize != null && (expectedSize < 0 || expectedSize > maximum)) {
      throw new ScannerRequestException(
          "INPUT_SIZE_INVALID", "Declared input size exceeds the configured limit", 413, false);
    }
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
          size += count;
          if (size > maximum) {
            throw new ScannerRequestException(
                "INPUT_TOO_LARGE", "Input exceeded the configured limit", 413, false);
          }
          output.write(buffer, 0, count);
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
        throw new ScannerRequestException(
            "INPUT_SIZE_MISMATCH", "Input size did not match the declared size", 422, false);
      }
      return new Verified(target, actual, size);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "INPUT_IO", "Unable to persist scanner input", 503, true, e);
    }
  }

  public record Verified(Path path, String sha256, long size) {}
}
