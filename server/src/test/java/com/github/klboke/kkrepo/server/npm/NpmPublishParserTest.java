package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpmPublishParserTest {
  private static final long ABOVE_DEFAULT_STRING_LIMIT_BYTES = 15_000_001L;

  @Test
  void streamsAttachmentBeyondJacksonStringLimitAndDeletesItOnClose(@TempDir Path tempDir)
      throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    NpmPublishParser parser = new NpmPublishParser(mapper, tempDir);
    long base64Characters = ((ABOVE_DEFAULT_STRING_LIMIT_BYTES + 2L) / 3L) * 4L;
    assertTrue(base64Characters > mapper.getFactory().streamReadConstraints().getMaxStringLength());

    Path staged;
    try (InputStream body = zeroFilledPublishBody(ABOVE_DEFAULT_STRING_LIMIT_BYTES);
        NpmPublishParser.PublishRequest request = parser.parse(body)) {
      assertEquals("demo", request.packageRoot().get("name"));
      assertFalse(request.packageRoot().containsKey(NpmMetadata.ATTACHMENTS));
      assertEquals(1, request.attachments().size());
      NpmPublishParser.Attachment attachment = request.attachments().get(0);
      assertEquals("demo-1.0.0.tgz", attachment.tarballName());
      assertEquals("application/octet-stream", attachment.contentType());
      staged = attachment.file();
      assertEquals(ABOVE_DEFAULT_STRING_LIMIT_BYTES, Files.size(staged));
      assertArrayEquals(
          digestZeros(ABOVE_DEFAULT_STRING_LIMIT_BYTES),
          digest(staged));
    }

    assertFalse(Files.exists(staged));
    assertDirectoryEmpty(tempDir);
  }

  @Test
  void deletesStagedAttachmentWhenJsonIsTruncated(@TempDir Path tempDir) {
    NpmPublishParser parser = new NpmPublishParser(new ObjectMapper(), tempDir);
    String truncated = """
        {"name":"demo","_attachments":{"demo-1.0.0.tgz":{"data":"AAAA"
        """;

    assertThrows(
        IOException.class,
        () -> parser.parse(new ByteArrayInputStream(truncated.getBytes(StandardCharsets.UTF_8))));

    assertDirectoryEmpty(tempDir);
  }

  @Test
  void deletesPartialAttachmentWhenBase64IsInvalid(@TempDir Path tempDir) {
    NpmPublishParser parser = new NpmPublishParser(new ObjectMapper(), tempDir);
    String invalid = """
        {"name":"demo","_attachments":{"demo-1.0.0.tgz":{"data":"AAAA*"}}}
        """;

    assertThrows(
        IOException.class,
        () -> parser.parse(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8))));

    assertDirectoryEmpty(tempDir);
  }

  private static InputStream zeroFilledPublishBody(long decodedBytes) {
    byte[] prefix = """
        {"name":"demo",
         "versions":{"1.0.0":{"name":"demo","version":"1.0.0",
           "dist":{"tarball":"demo-1.0.0.tgz"}}},
         "_attachments":{"demo-1.0.0.tgz":{
           "content_type":"application/octet-stream","data":"
        """.stripTrailing().getBytes(StandardCharsets.UTF_8);
    long completeGroups = decodedBytes / 3L;
    int remainder = (int) (decodedBytes % 3L);
    long aCharacters = completeGroups * 4L + (remainder == 0 ? 0 : remainder + 1L);
    int padding = remainder == 0 ? 0 : 3 - remainder;
    byte[] suffix = ("=".repeat(padding) + "\"}}}").getBytes(StandardCharsets.UTF_8);
    return new SequenceInputStream(Collections.enumeration(List.of(
        new ByteArrayInputStream(prefix),
        new RepeatingByteInputStream((byte) 'A', aCharacters),
        new ByteArrayInputStream(suffix))));
  }

  private static byte[] digestZeros(long size) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] zeros = new byte[64 * 1024];
    long remaining = size;
    while (remaining > 0) {
      int count = (int) Math.min(zeros.length, remaining);
      digest.update(zeros, 0, count);
      remaining -= count;
    }
    return digest.digest();
  }

  private static byte[] digest(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = input.read(buffer)) >= 0;) {
        if (read > 0) {
          digest.update(buffer, 0, read);
        }
      }
    }
    return digest.digest();
  }

  private static void assertDirectoryEmpty(Path directory) {
    try (var files = Files.list(directory)) {
      assertEquals(0L, files.count());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static final class RepeatingByteInputStream extends InputStream {
    private final byte value;
    private long remaining;

    private RepeatingByteInputStream(byte value, long remaining) {
      this.value = value;
      this.remaining = remaining;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      remaining--;
      return value & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      if (remaining == 0) {
        return -1;
      }
      int count = (int) Math.min(length, remaining);
      Arrays.fill(target, offset, offset + count, value);
      remaining -= count;
      return count;
    }
  }
}
