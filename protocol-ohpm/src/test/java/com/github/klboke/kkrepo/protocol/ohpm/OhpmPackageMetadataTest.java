package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmPackageMetadataTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/pkg");
  private static final String HAR_PATH = "@scope/pkg/-/@scope/pkg-1.0.0.har";
  private static final String HSP_PATH = "@scope/pkg/-/@scope/pkg-1.0.0.hsp";

  @Test
  void rewritesScopedDownloadsIndependentlyOfUpstreamFilenamesAndKeepsOriginalPrivate() throws Exception {
    ObjectNode body = har();
    body.put("description", "preserved");
    var metadata = OhpmPackageMetadata.read(PACKAGE, new ByteArrayInputStream(JSON.writeValueAsBytes(body)));
    String upstream = metadata.download(HAR_PATH).orElseThrow().remoteUrl();
    assertEquals("https://upstream.example/@scope/pkg/-/pkg-1.0.0.har?signature=fixture", upstream);
    var rewritten = metadata.rewritten("https://repo.example/repository/proxy");
    assertEquals("https://repo.example/repository/proxy/" + HAR_PATH,
        rewritten.path("versions").path("1.0.0").path("dist").path("tarball").asText());
    assertEquals("preserved", rewritten.path("description").asText());
    assertFalse(rewritten.toString().contains("signature"));
    assertTrue(metadata.original().toString().contains("signature"));
    body.remove("versions");
    rewritten.remove("versions");
    metadata.original().remove("versions");
    metadata.manifest("1.0.0").remove("dist");
    assertEquals(upstream, metadata.manifest("1.0.0").path("dist").path("tarball").asText());
    assertTrue(metadata.download("@scope/pkg/-/pkg-1.0.0.har").isEmpty());
  }

  @Test
  void mapsHspPairAndHarVersionsWithoutMixingTheirMetadata() throws Exception {
    var body = hsp();
    ObjectNode second = ((ObjectNode) har().path("versions").path("1.0.0")).deepCopy().put("version", "2.0.0");
    body.withObject("/versions").set("2.0.0", second);
    var metadata = OhpmPackageMetadata.parse(PACKAGE, body);
    assertEquals(3, metadata.downloads().size());
    assertFalse(metadata.download(HAR_PATH).orElseThrow().hsp());
    assertTrue(metadata.download(HSP_PATH).orElseThrow().hsp());
    assertTrue(metadata.download("@scope/pkg/-/@scope/pkg-2.0.0.hsp").isEmpty());
    assertEquals("https://repo.example/" + HSP_PATH,
        metadata.rewritten("https://repo.example/").path("versions").path("1.0.0")
            .path("dist").path("resolved_hsp").asText());
  }

  @Test
  void rejectsMissingHspDownloadOrIntegrityAndInvalidHspType() throws Exception {
    for (String field : List.of("integrity_hsp", "resolved_hsp")) {
      var body = hsp();
      body.withObject("/versions/1.0.0/dist").remove(field);
      assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, body));
    }
    var body = hsp();
    body.put("hspType", "unknown");
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, body));
  }

  @Test
  void rejectsPackageVersionTagAndIntegrityConflicts() throws Exception {
    for (String field : List.of("name", "_id")) {
      var body = har().put(field, "@scope/other");
      assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, body));
    }
    for (String field : List.of("name", "version", "_id")) {
      var body = har();
      body.withObject("/versions/1.0.0").put(field, "different");
      assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, body));
    }
    var tag = har();
    tag.withObject("/dist-tags").put("latest", "2.0.0");
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, tag));
    var integrity = har();
    integrity.withObject("/versions/1.0.0/dist").put("integrity", "sha256-invalid");
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, integrity));
    var attachment = har();
    attachment.putObject("_attachments");
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, attachment));
  }

  @ParameterizedTest
  @ValueSource(strings = {"file:///tmp/pkg.har", "ftp://upstream.example/pkg.har", "//upstream.example/pkg.har",
      "https://user:password@upstream.example/pkg.har", "https://upstream.example/pkg.har#fragment",
      "https://upstream.example:99999/pkg.har", "http://upstream.example:0/pkg.har"})
  void rejectsUnsafeDownloadUrlSyntax(String url) throws Exception {
    var body = har();
    body.withObject("/versions/1.0.0/dist").put("tarball", url);
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.parse(PACKAGE, body));
  }

  @Test
  void rejectsDuplicateFieldsTrailingContentAndOversizedDocuments() throws Exception {
    String valid = JSON.writeValueAsString(har());
    for (String body : List.of(valid + "{}", valid.replace("\"versions\":", "\"versions\":{},\"versions\":"))) {
      assertThrows(IOException.class, () -> OhpmPackageMetadata.read(PACKAGE,
          new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageMetadata.read(PACKAGE,
        new ByteArrayInputStream(new byte[16 * 1024 * 1024 + 1])));
  }

  private static ObjectNode har() throws IOException {
    var root = JSON.createObjectNode().put("name", PACKAGE.id()).put("_id", PACKAGE.id());
    root.putObject("dist-tags").put("latest", "1.0.0");
    root.putObject("versions").putObject("1.0.0").put("name", PACKAGE.id()).put("version", "1.0.0")
        .putObject("dist").put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(new byte[] {1, 2})).value())
        .put("tarball", "https://upstream.example/@scope/pkg/-/pkg-1.0.0.har?signature=fixture");
    return root;
  }

  private static ObjectNode hsp() throws IOException {
    var root = har().put("packageType", "InterfaceHar").put("hspType", "bundle_app");
    var dist = root.withObject("/versions/1.0.0/dist");
    dist.put("integrity_hsp", dist.path("integrity").asText())
        .put("resolved_hsp", "https://cdn.example/opaque/pkg.hsp");
    return root;
  }
}
