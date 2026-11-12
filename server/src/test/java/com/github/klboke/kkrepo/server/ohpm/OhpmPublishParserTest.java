package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmPublishParserTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/example");
  private static final String FILE = "@scope/example-1.2.3.har";
  private static final byte[] CONTENT = "har content".getBytes(StandardCharsets.UTF_8);
  @TempDir Path directory;

  @Test
  void validatesAndStagesScopedHarWithoutRetainingAttachmentData() throws Exception {
    ObjectNode root = publication(CONTENT);
    root.putObject("custom").put("preserved", true);
    root.withObject("/custom").putArray("platforms").add("phone").addObject().put("minimumVersion", 12);
    Path staged;
    try (var upload = parser(1024).parse(PACKAGE, input(root))) {
      assertEquals("1.2.3", upload.metadata().version());
      assertTrue(upload.metadata().metadata().path("custom").path("preserved").booleanValue());
      assertEquals(root.path("custom").path("platforms"), upload.metadata().metadata().path("custom").path("platforms"));
      assertFalse(upload.metadata().metadata().has("_attachments"));
      assertArrayEquals(CONTENT, Files.readAllBytes(upload.har().file()));
      assertEquals(CONTENT.length, upload.har().size());
      assertNull(upload.hsp());
      staged = upload.har().file();
      try (InputStream retry = Files.newInputStream(staged)) {
        assertTrue(upload.metadata().harIntegrity().matches(retry));
      }
    }
    assertFalse(Files.exists(staged));
    assertEmpty();
  }

  @Test
  void invalidSizeConfigurationIsRejectedBeforeAllocatingStagingFiles() throws Exception {
    for (long limit : new long[] {0, -1, Long.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> parser(limit));
    }
    assertEmpty();
  }

  @Test
  void streamsAttachmentLargerThanMetadataStringLimit() throws Exception {
    byte[] content = new byte[6 * 1024 * 1024];
    new Random(91).nextBytes(content);
    try (var upload = parser(content.length).parse(PACKAGE, input(publication(content)))) {
      assertArrayEquals(content, Files.readAllBytes(upload.har().file()));
    }
    assertEmpty();
  }

  @Test
  void acceptsAttachmentsBeforeMetadataAndLengthBeforeData() throws Exception {
    ObjectNode root = publication(CONTENT);
    ObjectNode reordered = JSON.createObjectNode();
    reordered.set("_attachments", root.remove("_attachments"));
    reordered.setAll(root);
    try (var upload = parser(1024).parse(PACKAGE, input(reordered))) {
      assertEquals(CONTENT.length, upload.har().size());
    }
    assertEmpty();
  }

  @Test
  void rejectsWrongIdentityLengthFilenameIntegrityAndAttachmentCount() throws Exception {
    reject(root -> root.put("name", "another"));
    reject(root -> attachment(root).put("length", CONTENT.length + 1));
    reject(root -> attachment(root).put("length", -1));
    reject(root -> attachment(root).put("length", 1.5));
    reject(root -> attachment(root).put("length", "11"));
    reject(root -> attachment(root).remove("length"));
    reject(root -> attachment(root).remove("data"));
    reject(root -> attachment(root).put("data", "%"));
    reject(root -> attachment(root).putNull("data"));
    reject(root -> ((ObjectNode) root.path("_attachments")).set("../escape.har",
        ((ObjectNode) root.path("_attachments")).remove(FILE)));
    reject(root -> ((ObjectNode) root.path("_attachments")).set("extra.har", attachment(root).deepCopy()));
    reject(root -> ((ObjectNode) root.path("versions").path("1.2.3").path("dist"))
        .put("integrity", "sha512-" + Base64.getEncoder().encodeToString(new byte[64])));
  }

  @Test
  void rejectsTruncationTrailingContentAndDuplicateKeysAndCleansFiles() throws Exception {
    String body = JSON.writeValueAsString(publication(CONTENT));
    for (String malformed : new String[] {
        body.substring(0, body.length() - 1), body + "{}",
        body.replace("\"data\":", "\"data\":\"aGFy\", \"data\":"),
        body.replace("\"name\":", "\"name\":\"different\", \"name\":"),
        body.replace("\"_attachments\":", "\"_attachments\":{}, \"_attachments\":")}) {
      assertEquals(400, assertThrows(OhpmException.class,
          () -> parser(1024).parse(PACKAGE, bytes(malformed))).status());
      assertEmpty();
    }
  }

  @Test
  void limitsDecodedBytesEvenWhenDeclaredLengthIsSmall() throws Exception {
    ObjectNode root = publication(new byte[8192]);
    attachment(root).put("length", 1);
    assertEquals(413, assertThrows(OhpmException.class,
        () -> parser(1024).parse(PACKAGE, input(root))).status());
    assertEmpty();
  }

  @Test
  void limitsAggregateMetadata() throws Exception {
    ObjectNode root = publication(CONTENT);
    for (int index = 0; index < 4; index++) root.put("extra" + index, "a".repeat(600_000));
    assertEquals(413, assertThrows(OhpmException.class,
        () -> parser(1024).parse(PACKAGE, input(root))).status());
    assertEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void validatesBothHspFilesAndCleansBothOnFailure(boolean corrupt) throws Exception {
    ObjectNode root = publication(CONTENT);
    root.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
    ObjectNode manifest = (ObjectNode) root.path("versions").path("1.2.3");
    manifest.put("types", "Index.d.ets");
    ((ObjectNode) manifest.path("dist")).put("integrity_hsp", integrity(CONTENT));
    ObjectNode hsp = attachment(root).deepCopy();
    if (corrupt) hsp.put("data", Base64.getEncoder().encodeToString(new byte[CONTENT.length]));
    ((ObjectNode) root.path("_attachments")).set("@scope/example-1.2.3.hsp", hsp);
    if (corrupt) {
      assertEquals(400, assertThrows(OhpmException.class,
          () -> parser(1024).parse(PACKAGE, input(root))).status());
    } else {
      try (var upload = parser(1024).parse(PACKAGE, input(root))) {
        assertNotNull(upload.hsp());
        assertArrayEquals(CONTENT, Files.readAllBytes(upload.hsp().file()));
      }
    }
    assertEmpty();
  }

  @Test
  void cleansPartialFileWhenInputDisconnects() throws Exception {
    byte[] body = JSON.writeValueAsBytes(publication(new byte[32_768]));
    InputStream interrupted = new ByteArrayInputStream(body) {
      @Override
      public synchronized int read(byte[] buffer, int offset, int length) {
        if (pos > body.length / 2) throw new java.io.UncheckedIOException(new IOException("Disconnected"));
        return super.read(buffer, offset, Math.min(length, 128));
      }
    };
    assertThrows(java.io.UncheckedIOException.class, () -> parser(40_000).parse(PACKAGE, interrupted));
    assertEmpty();
  }

  private void reject(Consumer<ObjectNode> mutation) throws Exception {
    ObjectNode root = publication(CONTENT);
    mutation.accept(root);
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser(1024).parse(PACKAGE, input(root))).status());
    assertEmpty();
  }

  private OhpmPublishParser parser(long maxBytes) {
    return new OhpmPublishParser(maxBytes, directory);
  }

  private void assertEmpty() throws IOException {
    try (var files = Files.list(directory)) {
      assertEquals(0, files.count());
    }
  }

  private static ObjectNode publication(byte[] content) throws IOException {
    ObjectNode root = JSON.createObjectNode().put("name", PACKAGE.id()).put("_id", PACKAGE.id());
    root.putObject("dist-tags").put("latest", "1.2.3");
    ObjectNode manifest = root.putObject("versions").putObject("1.2.3");
    manifest.put("name", PACKAGE.id()).put("version", "1.2.3").put("main", "Index.ets");
    manifest.putObject("dist").put("integrity", integrity(content));
    root.putObject("_attachments").putObject(FILE)
        .put("length", content.length).put("data", Base64.getEncoder().encodeToString(content));
    return root;
  }

  private static ObjectNode attachment(ObjectNode root) {
    return (ObjectNode) root.path("_attachments").path(FILE);
  }

  private static String integrity(byte[] content) throws IOException {
    return OhpmIntegrity.compute(new ByteArrayInputStream(content)).value();
  }

  private static InputStream input(ObjectNode root) throws IOException {
    return new ByteArrayInputStream(JSON.writeValueAsBytes(root));
  }

  private static InputStream bytes(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
