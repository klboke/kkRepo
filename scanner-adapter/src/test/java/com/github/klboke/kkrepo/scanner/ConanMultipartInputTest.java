package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConanMultipartInputTest {
  private static final String BOUNDARY = "kkrepo-conan-test-boundary";
  @TempDir Path temporaryDirectory;

  @Test
  void streamsAndVerifiesExactlyThePackageAndConanInfoParts() throws Exception {
    byte[] archive = ("binary\r\n--" + BOUNDARY + "-not-a-boundary").getBytes();
    byte[] info = "[settings]\nos=Linux\n".getBytes();
    byte[] body = multipart(archive, info, false);

    var parts = new ConanMultipartInput().parse(
        new ByteArrayInputStream(body),
        "multipart/form-data; boundary=" + BOUNDARY,
        temporaryDirectory.resolve("package"),
        temporaryDirectory.resolve("conaninfo"),
        sha256(archive),
        archive.length,
        sha256(info),
        info.length,
        limits(),
        new ScanDeadline(5));

    assertArrayEquals(archive, Files.readAllBytes(parts.packageArchive().path()));
    assertArrayEquals(info, Files.readAllBytes(parts.conanInfo().path()));
  }

  @Test
  void rejectsChecksumMismatchAndUnknownParts() throws Exception {
    byte[] archive = "archive".getBytes();
    byte[] info = "[settings]\n".getBytes();
    ScannerRequestException checksum = assertThrows(
        ScannerRequestException.class,
        () -> new ConanMultipartInput().parse(
            new ByteArrayInputStream(multipart(archive, info, false)),
            "multipart/form-data; boundary=" + BOUNDARY,
            temporaryDirectory.resolve("bad-package"),
            temporaryDirectory.resolve("bad-info"),
            "0".repeat(64),
            archive.length,
            sha256(info),
            info.length,
            limits(),
            new ScanDeadline(5)));
    assertEquals("INPUT_SHA256_MISMATCH", checksum.code());

    ScannerRequestException unknown = assertThrows(
        ScannerRequestException.class,
        () -> new ConanMultipartInput().parse(
            new ByteArrayInputStream(multipart(archive, info, true)),
            "multipart/form-data; boundary=" + BOUNDARY,
            temporaryDirectory.resolve("extra-package"),
            temporaryDirectory.resolve("extra-info"),
            sha256(archive),
            archive.length,
            sha256(info),
            info.length,
            limits(),
            new ScanDeadline(5)));
    assertEquals("CONAN_MULTIPART_INVALID", unknown.code());
  }

  private static byte[] multipart(byte[] archive, byte[] info, boolean extra) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    write(output, "--" + BOUNDARY + "\r\n"
        + "Content-Disposition: form-data; name=\"package\"\r\n\r\n");
    output.write(archive);
    write(output, "\r\n--" + BOUNDARY + "\r\n"
        + "Content-Disposition: form-data; name=\"conaninfo\"\r\n\r\n");
    output.write(info);
    if (extra) {
      write(output, "\r\n--" + BOUNDARY + "\r\n"
          + "Content-Disposition: form-data; name=\"unknown\"\r\n\r\nx");
    }
    write(output, "\r\n--" + BOUNDARY + "--\r\n");
    return output.toByteArray();
  }

  private static void write(ByteArrayOutputStream output, String value) throws Exception {
    output.write(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static ResourceLimits limits() {
    return new ResourceLimits(1024 * 1024, 100, 1024 * 1024, 1024 * 1024, 4, 5);
  }
}
