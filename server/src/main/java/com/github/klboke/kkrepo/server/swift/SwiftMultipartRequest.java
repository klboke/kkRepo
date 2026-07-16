package com.github.klboke.kkrepo.server.swift;

import jakarta.servlet.http.Part;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict parser for the four multipart fields defined by the Swift Registry OpenAPI. */
record SwiftMultipartRequest(
    Part sourceArchive,
    byte[] sourceArchiveSignature,
    String metadataJson,
    byte[] metadataSignature) {
  private static final int MAX_PARTS = 4;

  static SwiftMultipartRequest parse(Collection<Part> parts) {
    if (parts == null || parts.isEmpty()) {
      throw new SwiftExceptions.BadRequest("Swift publish requires multipart/form-data");
    }
    if (parts.size() > MAX_PARTS) {
      throw new SwiftExceptions.BadRequest("Swift publish contains too many multipart fields");
    }
    Map<String, Part> unique = new LinkedHashMap<>();
    for (Part part : parts) {
      if (part == null || part.getName() == null || !allowed(part.getName())) {
        throw new SwiftExceptions.BadRequest("Swift publish contains an unknown multipart field");
      }
      if (unique.putIfAbsent(part.getName(), part) != null) {
        throw new SwiftExceptions.BadRequest(
            "Swift publish contains duplicate multipart field " + part.getName());
      }
    }
    Part archive = unique.get("source-archive");
    if (archive == null || archive.getSize() == 0) {
      throw new SwiftExceptions.BadRequest("source-archive is required");
    }
    requireTransferEncoding(archive);
    requireContentType(archive, "application/zip", true, false);
    requireContentType(unique.get("source-archive-signature"),
        "application/octet-stream", false, false);
    requireContentType(unique.get("metadata"), "application/json", false, true);
    requireContentType(unique.get("metadata-signature"),
        "application/octet-stream", false, false);
    byte[] sourceSignature = read(
        unique.get("source-archive-signature"), SwiftPublishLimits.MAX_SIGNATURE_BYTES);
    byte[] metadata = read(unique.get("metadata"), SwiftPublishLimits.MAX_METADATA_BYTES);
    byte[] metadataSignature = read(
        unique.get("metadata-signature"), SwiftPublishLimits.MAX_SIGNATURE_BYTES);
    return new SwiftMultipartRequest(
        archive,
        sourceSignature,
        metadata == null ? "{}" : decodeUtf8(metadata),
        metadataSignature);
  }

  InputStream openArchive() {
    try {
      return decoded(sourceArchive);
    } catch (IOException e) {
      throw new SwiftExceptions.BadRequest("Unable to read source-archive", e);
    }
  }

  private static boolean allowed(String name) {
    return "source-archive".equals(name)
        || "source-archive-signature".equals(name)
        || "metadata".equals(name)
        || "metadata-signature".equals(name);
  }

  private static void requireContentType(
      Part part, String expected, boolean allowOctetStream, boolean allowUtf8Charset) {
    if (part == null) {
      return;
    }
    String raw = part.getContentType();
    if (raw == null || raw.isBlank()) {
      throw new SwiftExceptions.UnsupportedMediaType(
          part.getName() + " content type must be " + expected);
    }
    String[] segments = raw.split(";", -1);
    String mediaType = segments[0].trim();
    boolean validType = expected.equalsIgnoreCase(mediaType)
        || (allowOctetStream && "application/octet-stream".equalsIgnoreCase(mediaType));
    if (!validType) {
      throw new SwiftExceptions.UnsupportedMediaType(
          part.getName() + " content type must be " + expected);
    }
    for (int index = 1; index < segments.length; index++) {
      String parameter = segments[index].trim();
      if (allowUtf8Charset && parameter.toLowerCase(java.util.Locale.ROOT)
          .matches("charset\\s*=\\s*\"?utf-8\"?")) {
        continue;
      }
      throw new SwiftExceptions.UnsupportedMediaType(
          part.getName() + " contains an unsupported content-type parameter");
    }
  }

  private static String decodeUtf8(byte[] metadata) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(metadata))
          .toString();
    } catch (CharacterCodingException e) {
      throw new SwiftExceptions.BadRequest("metadata must be valid UTF-8", e);
    }
  }

  private static byte[] read(Part part, int limit) {
    if (part == null) {
      return null;
    }
    requireTransferEncoding(part);
    long encodedLimit = encodedLimit(part, limit);
    if (part.getSize() > encodedLimit) {
      throw new SwiftExceptions.ContentTooLarge(part.getName() + " exceeds the size limit");
    }
    try (InputStream input = decoded(part, encodedLimit);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16 * 1024];
      for (int count; (count = input.read(buffer)) >= 0;) {
        if (output.size() + count > limit) {
          throw new SwiftExceptions.ContentTooLarge(part.getName() + " exceeds the size limit");
        }
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    } catch (IOException e) {
      throw new SwiftExceptions.BadRequest("Unable to read multipart field " + part.getName(), e);
    }
  }

  private static void requireTransferEncoding(Part part) {
    String encoding = transferEncoding(part);
    if (!encoding.equals("binary") && !encoding.equals("7bit") && !encoding.equals("8bit")
        && !encoding.equals("base64") && !encoding.equals("quoted-printable")) {
      throw new SwiftExceptions.BadRequest(
          "Unsupported Content-Transfer-Encoding for " + part.getName());
    }
  }

  private static InputStream decoded(Part part) throws IOException {
    return decoded(part, Long.MAX_VALUE);
  }

  private static InputStream decoded(Part part, long rawLimit) throws IOException {
    InputStream raw = part.getInputStream();
    if (raw == null) {
      throw new IOException("Multipart field has no body stream");
    }
    InputStream input = rawLimit == Long.MAX_VALUE
        ? raw
        : new RawLimitInputStream(raw, rawLimit, part.getName());
    return switch (transferEncoding(part)) {
      case "base64" -> Base64.getMimeDecoder().wrap(input);
      case "quoted-printable" -> new QuotedPrintableInputStream(input);
      default -> input;
    };
  }

  private static long encodedLimit(Part part, long decodedLimit) {
    return switch (transferEncoding(part)) {
      case "base64" -> Math.addExact(Math.multiplyExact(decodedLimit, 2L), 1024L);
      case "quoted-printable" -> Math.addExact(Math.multiplyExact(decodedLimit, 4L), 1024L);
      default -> decodedLimit;
    };
  }

  private static String transferEncoding(Part part) {
    String value = part.getHeader("Content-Transfer-Encoding");
    return value == null || value.isBlank()
        ? "binary"
        : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** Strict streaming decoder for MIME quoted-printable bodies, including soft line breaks. */
  private static final class QuotedPrintableInputStream extends FilterInputStream {
    private QuotedPrintableInputStream(InputStream input) {
      super(input);
    }

    @Override
    public int read() throws IOException {
      while (true) {
        int current = super.read();
        if (current != '=') {
          return current;
        }
        int first = super.read();
        if (first == '\n') {
          continue;
        }
        if (first == '\r') {
          int newline = super.read();
          if (newline != '\n') {
            throw new IOException("Invalid quoted-printable soft line break");
          }
          continue;
        }
        int second = super.read();
        int high = hex(first);
        int low = hex(second);
        if (high < 0 || low < 0) {
          throw new IOException("Invalid quoted-printable escape");
        }
        return (high << 4) | low;
      }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      int count = 0;
      while (count < length) {
        int value = read();
        if (value < 0) {
          return count == 0 ? -1 : count;
        }
        bytes[offset + count++] = (byte) value;
      }
      return count;
    }

    private static int hex(int value) {
      if (value >= '0' && value <= '9') return value - '0';
      if (value >= 'a' && value <= 'f') return value - 'a' + 10;
      if (value >= 'A' && value <= 'F') return value - 'A' + 10;
      return -1;
    }
  }

  private static final class RawLimitInputStream extends FilterInputStream {
    private final long limit;
    private final String field;
    private long count;

    private RawLimitInputStream(InputStream input, long limit, String field) {
      super(input);
      this.limit = limit;
      this.field = field;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0 && ++count > limit) {
        throw new SwiftExceptions.ContentTooLarge(field + " exceeds the encoded size limit");
      }
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = super.read(bytes, offset, length);
      if (read > 0 && (count += read) > limit) {
        throw new SwiftExceptions.ContentTooLarge(field + " exceeds the encoded size limit");
      }
      return read;
    }
  }
}
