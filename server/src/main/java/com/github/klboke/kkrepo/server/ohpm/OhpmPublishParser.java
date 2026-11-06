package com.github.klboke.kkrepo.server.ohpm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPublishMetadata;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OhpmPublishParser {
  private static final int MAX_METADATA_CHARS = 2 * 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder()
          .maxNestingDepth(64).maxStringLength(MAX_METADATA_CHARS)
          .maxNumberLength(32).maxNameLength(1024).maxTokenCount(100_000).build())
      .build());

  private final long maxFileBytes;
  private final Path directory;

  OhpmPublishParser(long maxFileBytes, Path directory) {
    if (maxFileBytes < 1 || maxFileBytes > Long.MAX_VALUE / 8) {
      throw new IllegalArgumentException("Invalid OHPM upload size limit");
    }
    this.maxFileBytes = maxFileBytes;
    this.directory = directory;
  }

  Upload parse(OhpmPackageId packageId, InputStream body) throws IOException {
    List<Path> files = new ArrayList<>();
    Map<String, Attachment> attachments = new LinkedHashMap<>();
    ObjectNode root = JSON.createObjectNode();
    Budget budget = new Budget();
    try (JsonParser parser = JSON.createParser(limited(body))) {
      require(parser.nextToken(), JsonToken.START_OBJECT, "Expected an OHPM publish object");
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        require(parser.currentToken(), JsonToken.FIELD_NAME, "Truncated OHPM publish object");
        String field = parser.currentName();
        budget.use(field.length());
        JsonToken value = parser.nextToken();
        if ("_attachments".equals(field)) {
          require(value, JsonToken.START_OBJECT, "Expected OHPM attachments");
          while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken(), JsonToken.FIELD_NAME, "Truncated OHPM attachments");
            if (attachments.size() >= 2) throw invalid("Too many OHPM attachments");
            String name = parser.currentName();
            parser.nextToken();
            attachments.put(name, attachment(parser, files, budget));
          }
        } else {
          root.set(field, metadataValue(parser, budget));
        }
      }
      if (parser.nextToken() != null) throw invalid("Trailing OHPM publish content");
      OhpmPublishMetadata metadata = OhpmPublishMetadata.parse(packageId, root);
      String basename = packageId.id() + "-" + metadata.version();
      int expected = metadata.isHsp() ? 2 : 1;
      if (attachments.size() != expected) throw invalid("Unexpected OHPM attachment count");
      Attachment har = verified(attachments.get(basename + ".har"), metadata.harIntegrity());
      Attachment hsp = metadata.isHsp()
          ? verified(attachments.get(basename + ".hsp"), metadata.hspIntegrity()) : null;
      return new Upload(metadata, har, hsp);
    } catch (JsonProcessingException | IllegalArgumentException error) {
      files.forEach(TempBlobFiles::deleteQuietly);
      throw invalid("Invalid OHPM publish JSON or metadata");
    } catch (IOException | RuntimeException error) {
      files.forEach(TempBlobFiles::deleteQuietly);
      throw error;
    }
  }

  private Attachment attachment(JsonParser parser, List<Path> files, Budget budget) throws IOException {
    require(parser.currentToken(), JsonToken.START_OBJECT, "Expected an OHPM attachment object");
    Long length = null;
    Path file = null;
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      require(parser.currentToken(), JsonToken.FIELD_NAME, "Truncated OHPM attachment");
      String field = parser.currentName();
      budget.use(field.length());
      JsonToken value = parser.nextToken();
      if ("data".equals(field)) {
        require(value, JsonToken.VALUE_STRING, "OHPM attachment data must be base64");
        file = directory == null ? Files.createTempFile("kkrepo-ohpm-", ".upload")
            : Files.createTempFile(directory, "kkrepo-ohpm-", ".upload");
        files.add(file);
        try (OutputStream output = Files.newOutputStream(file)) {
          parser.readBinaryValue(new OutputStream() {
            private long written;

            @Override
            public void write(int value) throws IOException {
              reserve(1);
              output.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int count) throws IOException {
              reserve(count);
              output.write(bytes, offset, count);
            }

            private void reserve(int count) {
              if (count > maxFileBytes - written) throw new OhpmException(413, "OHPM attachment exceeds upload limit");
              written += count;
            }
          });
        }
      } else if ("length".equals(field)) {
        require(value, JsonToken.VALUE_NUMBER_INT, "OHPM attachment length must be an integer");
        length = parser.getLongValue();
        if (length < 1 || length > maxFileBytes) throw invalid("Invalid OHPM attachment length");
      } else {
        metadataValue(parser, budget);
      }
    }
    if (file == null || length == null || Files.size(file) != length) {
      throw invalid("OHPM attachment data and length do not match");
    }
    return new Attachment(file, length);
  }

  OhpmPublishMetadata metadata(OhpmPackageId packageId, InputStream body) throws IOException {
    try (JsonParser parser = JSON.createParser(body)) {
      require(parser.nextToken(), JsonToken.START_OBJECT, "Expected OHPM metadata");
      JsonNode root = metadataValue(parser, new Budget());
      if (parser.nextToken() != null || root.has("_attachments")) {
        throw invalid("Stream metadata must not contain attachments or trailing content");
      }
      return OhpmPublishMetadata.parse(packageId, root);
    } catch (JsonProcessingException | IllegalArgumentException error) {
      throw invalid("Invalid OHPM stream metadata");
    }
  }

  static Attachment verified(Attachment attachment, OhpmIntegrity integrity) throws IOException {
    if (attachment == null) throw invalid("Missing expected OHPM attachment");
    try (InputStream input = Files.newInputStream(attachment.file())) {
      if (!integrity.matches(input)) throw invalid("OHPM attachment integrity mismatch");
    }
    return attachment;
  }

  private static JsonNode metadataValue(JsonParser parser, Budget budget) throws IOException {
    JsonToken token = parser.currentToken();
    if (token == null) throw invalid("Truncated OHPM metadata");
    budget.use(1);
    if (token == JsonToken.START_OBJECT) {
      ObjectNode object = JSON.createObjectNode();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        require(parser.currentToken(), JsonToken.FIELD_NAME, "Truncated OHPM metadata object");
        String field = parser.currentName();
        budget.use(field.length());
        parser.nextToken();
        object.set(field, metadataValue(parser, budget));
      }
      return object;
    }
    if (token == JsonToken.START_ARRAY) {
      var array = JSON.createArrayNode();
      while (parser.nextToken() != JsonToken.END_ARRAY) array.add(metadataValue(parser, budget));
      return array;
    }
    if (!token.isScalarValue()) throw invalid("Invalid OHPM metadata value");
    budget.use(parser.getTextLength());
    return JSON.readTree(parser);
  }

  private InputStream limited(InputStream body) {
    return new FilterInputStream(body) {
      private long remaining = maxFileBytes * 4 + MAX_METADATA_CHARS * 4L;

      @Override
      public int read() throws IOException {
        int value = super.read();
        if (value >= 0) consume(1);
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
        if (count > 0) consume(count);
        return count;
      }

      private void consume(int count) {
        remaining -= count;
        if (remaining < 0) throw new OhpmException(413, "OHPM publish body exceeds upload limit");
      }
    };
  }

  private static void require(JsonToken actual, JsonToken expected, String message) {
    if (actual != expected) throw invalid(message);
  }

  private static OhpmException invalid(String message) {
    return new OhpmException(400, message);
  }

  private static final class Budget {
    private int remaining = MAX_METADATA_CHARS;

    void use(int count) {
      remaining -= count;
      if (remaining < 0) throw new OhpmException(413, "OHPM metadata exceeds upload limit");
    }
  }

  record Attachment(Path file, long size) {}

  record Upload(OhpmPublishMetadata metadata, Attachment har, Attachment hsp) implements AutoCloseable {
    @Override
    public void close() {
      TempBlobFiles.deleteQuietly(har.file());
      if (hsp != null) TempBlobFiles.deleteQuietly(hsp.file());
    }
  }
}
