package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerInputTest {
  @TempDir Path temporaryDirectory;

  @Test
  void streamsAndVerifiesTheDeclaredIdentity() throws Exception {
    byte[] value = "verified scanner input".getBytes();
    String sha256 = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value));
    Path target = temporaryDirectory.resolve("input.jar");

    ScannerInput.Verified verified = new ScannerInput().copy(
        new ByteArrayInputStream(value), target, sha256.toUpperCase(), (long) value.length,
        limits(1024), deadline());

    assertEquals(sha256, verified.sha256());
    assertEquals(value.length, verified.size());
    assertEquals("verified scanner input", Files.readString(target));

    ScannerInput.Verified sbom = new ScannerInput().copySbom(
        new ByteArrayInputStream(value), temporaryDirectory.resolve("sbom.json"), sha256,
        limits(value.length), 1024, deadline());
    assertEquals(value.length, sbom.size());
  }

  @Test
  void rejectsInvalidDeclarationsAndMismatches() {
    ScannerInput input = new ScannerInput();
    assertCode(
        "EXPECTED_SHA256_INVALID",
        () -> input.copy(
            new ByteArrayInputStream(new byte[0]), temporaryDirectory.resolve("bad"),
            "invalid", 0L, limits(1024), deadline()));
    assertCode(
        "INPUT_SIZE_INVALID",
        () -> input.copy(
            new ByteArrayInputStream(new byte[0]), temporaryDirectory.resolve("large"),
            "a".repeat(64), 2048L, limits(1024), deadline()));
    assertCode(
        "INPUT_TOO_LARGE",
        () -> input.copy(
            new ByteArrayInputStream(new byte[1025]), temporaryDirectory.resolve("body"),
            "a".repeat(64), null, limits(1024), deadline()));
    assertCode(
        "INPUT_SHA256_MISMATCH",
        () -> input.copy(
            new ByteArrayInputStream("body".getBytes()), temporaryDirectory.resolve("sha"),
            "a".repeat(64), 4L, limits(1024), deadline()));

    byte[] body = "body".getBytes();
    String sha = ScannerDocumentMapper.sha256(body);
    assertCode(
        "INPUT_SIZE_MISMATCH",
        () -> input.copy(
            new ByteArrayInputStream(body), temporaryDirectory.resolve("size"),
            sha, 3L, limits(1024), deadline()));
    assertCode(
        "INPUT_SIZE_MISMATCH",
        () -> input.copy(
            new ByteArrayInputStream(body), temporaryDirectory.resolve("short"),
            sha, 5L, limits(1024), deadline()));
    assertCode(
        "INPUT_IO",
        () -> input.copy(
            new ByteArrayInputStream(body),
            temporaryDirectory.resolve("missing").resolve("input"),
            sha,
            4L,
            limits(1024),
            deadline()));
  }

  @Test
  void stopsBeforeWritingPastTheCatalogReservationOrSbomLimit() throws Exception {
    ScannerInput input = new ScannerInput();
    byte[] value = new byte[64 * 1024 + 2];
    String sha = ScannerDocumentMapper.sha256(value);
    long reserved = value.length - 1L;
    Path catalogTarget = temporaryDirectory.resolve("bounded-catalog");
    Path sbomTarget = temporaryDirectory.resolve("bounded-sbom");

    assertCode("INPUT_SIZE_MISMATCH", () -> input.copy(
        new ByteArrayInputStream(value), catalogTarget, sha, reserved, limits(value.length),
        deadline()));
    assertCode("SBOM_TOO_LARGE", () -> input.copySbom(
        new ByteArrayInputStream(value), sbomTarget, sha, limits(value.length), reserved,
        deadline()));
    assertEquals(64 * 1024, Files.size(catalogTarget));
    assertEquals(64 * 1024, Files.size(sbomTarget));
    assertCode("INPUT_TOO_LARGE", () -> input.copySbom(
        new ByteArrayInputStream(value), sbomTarget, sha, limits(reserved), value.length,
        deadline()));
  }

  @Test
  void stopsCopyingWhenTheSharedDeadlineExpires() {
    byte[] body = "body".getBytes();
    String sha = ScannerDocumentMapper.sha256(body);
    AtomicLong clock = new AtomicLong();
    ByteArrayInputStream delayed = new ByteArrayInputStream(body) {
      @Override
      public int read(byte[] buffer, int offset, int length) {
        int count = super.read(buffer, offset, length);
        clock.set(2);
        return count;
      }
    };

    assertCode(
        "SCANNER_TIMEOUT",
        () -> new ScannerInput().copy(
            delayed,
            temporaryDirectory.resolve("timed-out"),
            sha,
            (long) body.length,
            limits(1024),
            new ScanDeadline(Duration.ofNanos(1), clock::get)));
  }

  private static ResourceLimits limits(long maxInputBytes) {
    return new ResourceLimits(maxInputBytes, 10, maxInputBytes * 2, maxInputBytes, 1, 10);
  }

  private static ScanDeadline deadline() {
    return new ScanDeadline(10);
  }

  private static void assertCode(String expected, Runnable invocation) {
    ScannerRequestException error =
        assertThrows(ScannerRequestException.class, invocation::run);
    assertEquals(expected, error.code());
  }
}
