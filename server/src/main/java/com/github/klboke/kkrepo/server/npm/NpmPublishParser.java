package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streams npm publish attachments to request-scoped temporary files while materializing the
 * package metadata.
 *
 * <p>The npm client embeds each tarball as one base64 JSON string under {@code _attachments}.
 * Reading the complete document into a map would materialize that string and subject it to
 * Jackson's per-string limit. Attachment files returned by this parser are local staging only;
 * callers must close the parsed request after persisting or rejecting it.</p>
 */
final class NpmPublishParser {
  private static final String ATTACHMENT_DATA = "data";
  private static final String ATTACHMENT_CONTENT_TYPE = "content_type";

  private final ObjectMapper mapper;
  private final Path tempDirectory;

  NpmPublishParser(ObjectMapper mapper) {
    this(mapper, null);
  }

  NpmPublishParser(ObjectMapper mapper, Path tempDirectory) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.tempDirectory = tempDirectory;
  }

  PublishRequest parse(InputStream body) throws IOException {
    Map<String, Object> packageRoot = new LinkedHashMap<>();
    Map<String, Attachment> attachments = new LinkedHashMap<>();
    try (JsonParser parser = mapper.getFactory().createParser(body)) {
      requireToken(parser, parser.nextToken(), JsonToken.START_OBJECT, "npm publish body must be a JSON object");
      while (true) {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT) {
          break;
        }
        requireToken(parser, token, JsonToken.FIELD_NAME, "npm publish object is truncated");
        String fieldName = parser.currentName();
        JsonToken valueToken = parser.nextToken();
        if (valueToken == null) {
          throw invalidJson(parser, "npm publish field '" + fieldName + "' has no value");
        }
        if (NpmMetadata.ATTACHMENTS.equals(fieldName)) {
          cleanup(attachments.values());
          attachments.clear();
          parseAttachments(parser, valueToken, attachments);
        } else {
          packageRoot.put(fieldName, mapper.readValue(parser, Object.class));
        }
      }
      if (parser.nextToken() != null) {
        throw invalidJson(parser, "npm publish body contains trailing JSON content");
      }
      return new PublishRequest(packageRoot, new ArrayList<>(attachments.values()));
    } catch (IOException | RuntimeException e) {
      cleanup(attachments.values());
      throw e;
    }
  }

  private void parseAttachments(
      JsonParser parser,
      JsonToken token,
      Map<String, Attachment> attachments) throws IOException {
    if (token != JsonToken.START_OBJECT) {
      parser.skipChildren();
      return;
    }
    while (true) {
      JsonToken entryToken = parser.nextToken();
      if (entryToken == JsonToken.END_OBJECT) {
        return;
      }
      requireToken(parser, entryToken, JsonToken.FIELD_NAME, "npm _attachments object is truncated");
      String attachmentKey = parser.currentName();
      JsonToken valueToken = parser.nextToken();
      if (valueToken == null) {
        throw invalidJson(parser, "npm attachment '" + attachmentKey + "' has no value");
      }
      Attachment attachment = parseAttachment(parser, valueToken, attachmentKey);
      Attachment replaced = attachments.remove(attachmentKey);
      if (replaced != null) {
        replaced.delete();
      }
      if (attachment != null) {
        attachments.put(attachmentKey, attachment);
      }
    }
  }

  private Attachment parseAttachment(
      JsonParser parser,
      JsonToken token,
      String attachmentKey) throws IOException {
    if (token != JsonToken.START_OBJECT) {
      parser.skipChildren();
      return null;
    }
    String contentType = NpmResponseSupport.TARBALL;
    Path stagedFile = null;
    try {
      while (true) {
        JsonToken fieldToken = parser.nextToken();
        if (fieldToken == JsonToken.END_OBJECT) {
          if (stagedFile == null) {
            return null;
          }
          return new Attachment(
              NpmMetadata.extractTarballName(attachmentKey),
              contentType,
              stagedFile);
        }
        requireToken(
            parser,
            fieldToken,
            JsonToken.FIELD_NAME,
            "npm attachment '" + attachmentKey + "' is truncated");
        String fieldName = parser.currentName();
        JsonToken valueToken = parser.nextToken();
        if (valueToken == null) {
          throw invalidJson(parser, "npm attachment field '" + fieldName + "' has no value");
        }
        if (ATTACHMENT_DATA.equals(fieldName)) {
          if (valueToken == JsonToken.VALUE_NULL) {
            TempBlobFiles.deleteQuietly(stagedFile);
            stagedFile = null;
          } else {
            requireToken(
                parser,
                valueToken,
                JsonToken.VALUE_STRING,
                "npm attachment data must be a base64 JSON string");
            TempBlobFiles.deleteQuietly(stagedFile);
            stagedFile = createTempFile();
            try (OutputStream output = Files.newOutputStream(
                stagedFile,
                StandardOpenOption.TRUNCATE_EXISTING)) {
              try {
                parser.readBinaryValue(Base64Variants.getDefaultVariant(), output);
              } catch (IllegalArgumentException e) {
                throw new JsonParseException(parser, "npm attachment data is invalid base64", e);
              }
            }
          }
        } else if (ATTACHMENT_CONTENT_TYPE.equals(fieldName)) {
          Object value = mapper.readValue(parser, Object.class);
          contentType = NpmMetadata.stringValue(value, NpmResponseSupport.TARBALL);
        } else {
          parser.skipChildren();
        }
      }
    } catch (IOException | RuntimeException e) {
      TempBlobFiles.deleteQuietly(stagedFile);
      throw e;
    }
  }

  private Path createTempFile() throws IOException {
    if (tempDirectory == null) {
      return Files.createTempFile("kkrepo-npm-publish-", ".tgz");
    }
    Files.createDirectories(tempDirectory);
    return Files.createTempFile(tempDirectory, "kkrepo-npm-publish-", ".tgz");
  }

  private static void requireToken(
      JsonParser parser,
      JsonToken actual,
      JsonToken expected,
      String message) throws JsonParseException {
    if (actual != expected) {
      throw invalidJson(parser, message);
    }
  }

  private static JsonParseException invalidJson(JsonParser parser, String message) {
    return new JsonParseException(parser, message);
  }

  private static void cleanup(Iterable<Attachment> attachments) {
    for (Attachment attachment : attachments) {
      attachment.delete();
    }
  }

  static final class PublishRequest implements AutoCloseable {
    private final Map<String, Object> packageRoot;
    private final List<Attachment> attachments;

    private PublishRequest(
        Map<String, Object> packageRoot,
        List<Attachment> attachments) {
      this.packageRoot = packageRoot;
      this.attachments = List.copyOf(attachments);
    }

    Map<String, Object> packageRoot() {
      return packageRoot;
    }

    List<Attachment> attachments() {
      return attachments;
    }

    @Override
    public void close() {
      cleanup(attachments);
    }
  }

  record Attachment(String tarballName, String contentType, Path file) {
    InputStream openStream() throws IOException {
      return Files.newInputStream(file);
    }

    private void delete() {
      TempBlobFiles.deleteQuietly(file);
    }
  }
}
