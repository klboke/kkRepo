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

  @Test
  void rejectsInvalidMediaTypeBoundarySizeAndChecksumMetadata() throws Exception {
    byte[] empty = new byte[0];
    for (String contentType : new String[] {
        null,
        "application/octet-stream",
        "multipart/form-data",
        "multipart/form-data; boundary=short",
        "multipart/form-data; boundary=\"unterminated"
    }) {
      assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
          empty, contentType, sha256(empty), 0, sha256(empty), 0, limits())).code());
    }

    assertEquals("CONAN_INPUT_SIZE_INVALID", rejected(() -> parse(
        empty, contentType(), sha256(empty), -1, sha256(empty), 0, limits())).code());
    assertEquals("CONAN_INPUT_SIZE_INVALID", rejected(() -> parse(
        empty, contentType(), sha256(empty), 1024 * 1024 + 1L,
        sha256(empty), 0, limits())).code());
    assertEquals("CONAN_INPUT_SIZE_INVALID", rejected(() -> parse(
        empty, contentType(), sha256(empty), 1024 * 1024L,
        sha256(empty), 1, limits())).code());
    ResourceLimits tinySingleFile = new ResourceLimits(1024, 100, 1024, 4, 4, 5);
    assertEquals("CONAN_INPUT_SIZE_INVALID", rejected(() -> parse(
        empty, contentType(), sha256(empty), 0, sha256(empty), 5, tinySingleFile)).code());
    assertEquals("EXPECTED_SHA256_INVALID", rejected(() -> parse(
        empty, contentType(), null, 0, sha256(empty), 0, limits())).code());
    assertEquals("EXPECTED_SHA256_INVALID", rejected(() -> parse(
        empty, contentType(), "z".repeat(64), 0, sha256(empty), 0, limits())).code());
  }

  @Test
  void rejectsMalformedMultipartFramingAndHeaders() throws Exception {
    byte[] empty = new byte[0];
    for (String body : new String[] {
        "wrong\r\n",
        "--" + BOUNDARY,
        "--" + BOUNDARY + "\n",
        "--" + BOUNDARY + "\rX",
        "--" + BOUNDARY + "\r\nMalformed\r\n",
        "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"package\"\r\n"
            + "Content-Disposition: duplicate\r\n\r\n",
        "--" + BOUNDARY + "\r\nContent-Type: binary\r\n\r\n",
        "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\n",
        "--" + BOUNDARY + "\r\nX-Header: \u0080\r\n\r\n",
        "--" + BOUNDARY + "\r\nX-Header: value\n\n"
    }) {
      assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
          body.getBytes(StandardCharsets.ISO_8859_1), contentType(), sha256(empty), 0,
          sha256(empty), 0, limits())).code(), body);
    }

    StringBuilder headers = new StringBuilder("--").append(BOUNDARY).append("\r\n");
    for (int index = 0; index < 9; index++) headers.append("X-").append(index).append(": v\r\n");
    headers.append("\r\n");
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        headers.toString().getBytes(StandardCharsets.US_ASCII), contentType(), sha256(empty), 0,
        sha256(empty), 0, limits())).code());

    String oversized = "--" + BOUNDARY + "\r\nX: " + "a".repeat(8192);
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        oversized.getBytes(StandardCharsets.US_ASCII), contentType(), sha256(empty), 0,
        sha256(empty), 0, limits())).code());
  }

  @Test
  void rejectsDuplicateMissingTruncatedAndNonEmptyEpilogueParts() throws Exception {
    byte[] archive = "archive".getBytes(StandardCharsets.UTF_8);
    byte[] info = "info".getBytes(StandardCharsets.UTF_8);
    String packageHeaders = "Content-Disposition: form-data; name=\"package\"\r\n\r\n";
    String infoHeaders = "Content-Disposition: form-data; name=\"conaninfo\"\r\n\r\n";

    byte[] duplicate = framed(
        packageHeaders, archive, packageHeaders, archive, true, "");
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        duplicate, contentType(), sha256(archive), archive.length, sha256(info), info.length,
        limits())).code());

    byte[] missing = framed(packageHeaders, archive, null, null, true, "");
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        missing, contentType(), sha256(archive), archive.length, sha256(info), info.length,
        limits())).code());

    byte[] truncated = framed(packageHeaders, archive, infoHeaders, info, false, "");
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        truncated, contentType(), sha256(archive), archive.length, sha256(info), info.length,
        limits())).code());

    byte[] epilogue = framed(packageHeaders, archive, infoHeaders, info, true, "unexpected");
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        epilogue, contentType(), sha256(archive), archive.length, sha256(info), info.length,
        limits())).code());

    byte[] malformedClose = multipart(archive, info, false);
    malformedClose[malformedClose.length - 1] = 'x';
    assertEquals("CONAN_MULTIPART_INVALID", rejected(() -> parse(
        malformedClose, contentType(), sha256(archive), archive.length, sha256(info), info.length,
        limits())).code());
  }

  @Test
  void rejectsPartSizeLimitSizeMismatchAndOutputFailures() throws Exception {
    byte[] archive = "archive".getBytes(StandardCharsets.UTF_8);
    byte[] info = "info".getBytes(StandardCharsets.UTF_8);
    byte[] body = multipart(archive, info, false);
    assertEquals("INPUT_SIZE_MISMATCH", rejected(() -> parse(
        body, contentType(), sha256(archive), archive.length + 1L,
        sha256(info), info.length, limits())).code());

    ResourceLimits tiny = new ResourceLimits(5, 100, 5, 1024, 4, 5);
    assertEquals("CONAN_INPUT_TOO_LARGE", rejected(() -> parse(
        body, contentType(), sha256(archive), 1, sha256(info), info.length, tiny)).code());

    ScannerRequestException io = assertThrows(ScannerRequestException.class, () ->
        new ConanMultipartInput().parse(
            new ByteArrayInputStream(body), contentType(),
            temporaryDirectory.resolve("missing/package"),
            temporaryDirectory.resolve("missing/info"),
            sha256(archive), archive.length, sha256(info), info.length,
            limits(), new ScanDeadline(5)));
    assertEquals("CONAN_INPUT_IO", io.code());
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

  private ConanMultipartInput.Parts parse(
      byte[] body,
      String contentType,
      String packageSha,
      long packageSize,
      String infoSha,
      long infoSize,
      ResourceLimits resourceLimits) {
    return new ConanMultipartInput().parse(
        new ByteArrayInputStream(body), contentType,
        temporaryDirectory.resolve("package-" + System.nanoTime()),
        temporaryDirectory.resolve("info-" + System.nanoTime()),
        packageSha, packageSize, infoSha, infoSize, resourceLimits, new ScanDeadline(5));
  }

  private static ScannerRequestException rejected(ThrowingCall call) {
    return assertThrows(ScannerRequestException.class, call::run);
  }

  private static byte[] framed(
      String firstHeaders,
      byte[] first,
      String secondHeaders,
      byte[] second,
      boolean close,
      String epilogue) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    write(output, "--" + BOUNDARY + "\r\n" + firstHeaders);
    output.write(first);
    if (secondHeaders != null) {
      write(output, "\r\n--" + BOUNDARY + "\r\n" + secondHeaders);
      output.write(second);
    }
    if (close) write(output, "\r\n--" + BOUNDARY + "--\r\n" + epilogue);
    return output.toByteArray();
  }

  private static String contentType() {
    return "multipart/form-data; boundary=\"" + BOUNDARY + "\"";
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static ResourceLimits limits() {
    return new ResourceLimits(1024 * 1024, 100, 1024 * 1024, 1024 * 1024, 4, 5);
  }
}
