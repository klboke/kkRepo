package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Strict streaming parser for the scanner's two-part Conan package input. */
@Component
final class ConanMultipartInput {
  static final long MAX_CONANINFO_BYTES = 1024L * 1024;
  private static final int MAX_HEADER_LINE_BYTES = 8 * 1024;
  private static final int MAX_HEADERS = 8;
  private static final Pattern BOUNDARY = Pattern.compile("[A-Za-z0-9_-]{16,70}");
  private static final Pattern PART_NAME = Pattern.compile(
      "(?:^|;)\\s*name=\\\"(package|conaninfo)\\\"(?:;|$)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

  Parts parse(
      InputStream input,
      String contentType,
      Path packageTarget,
      Path conanInfoTarget,
      String packageSha256,
      long packageSize,
      String conanInfoSha256,
      long conanInfoSize,
      ResourceLimits limits,
      ScanDeadline deadline) {
    String boundary = boundary(contentType);
    if (packageSize < 0 || conanInfoSize < 0
        || conanInfoSize > Math.min(MAX_CONANINFO_BYTES, limits.maxSingleFileBytes())
        || packageSize > limits.maxInputBytes()
        || packageSize > limits.maxInputBytes() - conanInfoSize) {
      throw rejected("CONAN_INPUT_SIZE_INVALID", "Conan composite input exceeds its limit", 413);
    }
    requireSha(packageSha256);
    requireSha(conanInfoSha256);
    try (PushbackInputStream source = new PushbackInputStream(input, 128)) {
      String opening = readLine(source, deadline);
      if (!("--" + boundary).equals(opening)) {
        throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart opening boundary is invalid", 400);
      }
      byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.US_ASCII);
      Set<String> names = new HashSet<>();
      ScannerInput.Verified packagePart = null;
      ScannerInput.Verified infoPart = null;
      boolean finished = false;
      while (!finished) {
        Map<String, String> headers = headers(source, deadline);
        String name = partName(headers.get("content-disposition"));
        if (!names.add(name)) {
          throw rejected("CONAN_MULTIPART_INVALID", "Duplicate Conan multipart part", 400);
        }
        boolean packageInput = "package".equals(name);
        if (!packageInput && !"conaninfo".equals(name)) {
          throw rejected("CONAN_MULTIPART_INVALID", "Unknown Conan multipart part", 400);
        }
        PartWriter writer = new PartWriter(
            packageInput ? packageTarget : conanInfoTarget,
            packageInput ? packageSha256 : conanInfoSha256,
            packageInput ? packageSize : conanInfoSize,
            packageInput ? limits.maxInputBytes()
                : Math.min(MAX_CONANINFO_BYTES, limits.maxSingleFileBytes()));
        try (writer) {
          finished = streamPart(source, delimiter, writer, deadline);
        }
        ScannerInput.Verified verified = writer.verified();
        if (packageInput) packagePart = verified;
        else infoPart = verified;

        if (finished) {
          requireCrlf(source);
          if (source.read() != -1) {
            throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart epilogue is not empty", 400);
          }
        }
      }
      if (packagePart == null || infoPart == null || names.size() != 2) {
        throw rejected(
            "CONAN_MULTIPART_INVALID", "Conan package and conaninfo parts are required", 400);
      }
      return new Parts(packagePart, infoPart);
    } catch (ScannerRequestException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new ScannerRequestException(
          "CONAN_INPUT_IO", "Unable to read Conan composite input", 503, true, failure);
    }
  }

  private static Map<String, String> headers(InputStream input, ScanDeadline deadline)
      throws IOException {
    Map<String, String> result = new HashMap<>();
    for (int count = 0; count <= MAX_HEADERS; count++) {
      String line = readLine(input, deadline);
      if (line.isEmpty()) return result;
      if (count == MAX_HEADERS) {
        throw rejected("CONAN_MULTIPART_INVALID", "Too many Conan multipart headers", 400);
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        throw rejected("CONAN_MULTIPART_INVALID", "Malformed Conan multipart header", 400);
      }
      String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      if (result.putIfAbsent(key, value) != null) {
        throw rejected("CONAN_MULTIPART_INVALID", "Duplicate Conan multipart header", 400);
      }
    }
    throw rejected("CONAN_MULTIPART_INVALID", "Malformed Conan multipart headers", 400);
  }

  private static String partName(String disposition) {
    if (disposition == null || !disposition.toLowerCase(Locale.ROOT).startsWith("form-data")) {
      throw rejected("CONAN_MULTIPART_INVALID", "Missing Conan part disposition", 400);
    }
    Matcher matcher = PART_NAME.matcher(disposition);
    if (!matcher.find()) {
      throw rejected("CONAN_MULTIPART_INVALID", "Missing Conan multipart part name", 400);
    }
    return matcher.group(1).toLowerCase(Locale.ROOT);
  }

  private static boolean streamPart(
      PushbackInputStream input,
      byte[] delimiter,
      OutputStream output,
      ScanDeadline deadline) throws IOException {
    byte[] pending = new byte[delimiter.length];
    int length = 0;
    while (true) {
      deadline.check();
      int value = input.read();
      if (value < 0) {
        throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart body is truncated", 400);
      }
      pending[length++] = (byte) value;
      while (length > 0 && !prefix(pending, length, delimiter)) {
        output.write(pending[0]);
        System.arraycopy(pending, 1, pending, 0, --length);
      }
      if (length == delimiter.length) {
        int first = input.read();
        int second = input.read();
        if (first == '-' && second == '-') return true;
        if (first == '\r' && second == '\n') return false;
        if (second >= 0) input.unread(second);
        if (first >= 0) input.unread(first);
        output.write(pending[0]);
        System.arraycopy(pending, 1, pending, 0, --length);
      }
    }
  }

  private static boolean prefix(byte[] candidate, int length, byte[] expected) {
    if (length > expected.length) return false;
    for (int index = 0; index < length; index++) {
      if (candidate[index] != expected[index]) return false;
    }
    return true;
  }

  private static String readLine(InputStream input, ScanDeadline deadline) throws IOException {
    byte[] bytes = new byte[MAX_HEADER_LINE_BYTES];
    int length = 0;
    while (length < bytes.length) {
      deadline.check();
      int value = input.read();
      if (value < 0) {
        throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart header is truncated", 400);
      }
      if (value == '\r') {
        if (input.read() != '\n') {
          throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart requires CRLF", 400);
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
      }
      if (value == '\n' || value > 0x7f || value == 0) {
        throw rejected("CONAN_MULTIPART_INVALID", "Invalid Conan multipart header", 400);
      }
      bytes[length++] = (byte) value;
    }
    throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart header is too large", 400);
  }

  private static void requireCrlf(InputStream input) throws IOException {
    if (input.read() != '\r' || input.read() != '\n') {
      throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart closing boundary is malformed", 400);
    }
  }

  private static String boundary(String contentType) {
    if (contentType == null) {
      throw rejected("CONAN_MULTIPART_INVALID", "Conan catalog requires multipart/form-data", 415);
    }
    String[] values = contentType.split(";");
    if (!"multipart/form-data".equalsIgnoreCase(values[0].trim())) {
      throw rejected("CONAN_MULTIPART_INVALID", "Conan catalog requires multipart/form-data", 415);
    }
    for (int index = 1; index < values.length; index++) {
      String value = values[index].trim();
      if (value.regionMatches(true, 0, "boundary=", 0, 9)) {
        String candidate = value.substring(9).trim();
        if (candidate.startsWith("\"") && candidate.endsWith("\"")
            && candidate.length() >= 2) {
          candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (BOUNDARY.matcher(candidate).matches()) return candidate;
      }
    }
    throw rejected("CONAN_MULTIPART_INVALID", "Conan multipart boundary is invalid", 400);
  }

  private static void requireSha(String value) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw rejected("EXPECTED_SHA256_INVALID", "A valid expected SHA-256 is required", 400);
    }
  }

  private static ScannerRequestException rejected(String code, String message, int status) {
    return new ScannerRequestException(code, message, status, false);
  }

  record Parts(ScannerInput.Verified packageArchive, ScannerInput.Verified conanInfo) {}

  private static final class PartWriter extends OutputStream {
    private final Path path;
    private final String expectedSha256;
    private final long expectedSize;
    private final long maximumSize;
    private final MessageDigest digest;
    private final OutputStream output;
    private long size;
    private ScannerInput.Verified verified;

    private PartWriter(Path path, String expectedSha256, long expectedSize, long maximumSize)
        throws IOException {
      this.path = path;
      this.expectedSha256 = expectedSha256.toLowerCase(Locale.ROOT);
      this.expectedSize = expectedSize;
      this.maximumSize = maximumSize;
      try {
        this.digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 is unavailable", impossible);
      }
      this.output = Files.newOutputStream(path);
    }

    @Override
    public void write(int value) throws IOException {
      if (++size > maximumSize) {
        throw rejected("CONAN_INPUT_TOO_LARGE", "Conan multipart part exceeds its limit", 413);
      }
      output.write(value);
      digest.update((byte) value);
    }

    @Override
    public void close() throws IOException {
      output.close();
      String actual = HexFormat.of().formatHex(digest.digest());
      if (size != expectedSize) {
        throw rejected("INPUT_SIZE_MISMATCH", "Conan multipart part size did not match", 422);
      }
      if (!MessageDigest.isEqual(
          actual.getBytes(StandardCharsets.US_ASCII),
          expectedSha256.getBytes(StandardCharsets.US_ASCII))) {
        throw rejected("INPUT_SHA256_MISMATCH", "Conan multipart part SHA-256 did not match", 422);
      }
      verified = new ScannerInput.Verified(path, actual, size);
    }

    private ScannerInput.Verified verified() {
      return verified;
    }
  }
}
