package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class OhpmGroupMetadataTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/pkg");
  private static final String HAR = "@scope/pkg/-/@scope/pkg-1.0.0.har";
  private static final String HSP = "@scope/pkg/-/@scope/pkg-1.0.0.hsp";

  @Test
  void firstMemberWinsVersionsAndTagsAndOwnsTheVersionTimestamp() throws Exception {
    var first = body("1.0.0", false, 1);
    first.put("description", "first");
    first.putObject("time").put("1.0.0", "2026-01-01T00:00:00Z").put("created", "2026-01-01T00:00:00Z");
    var second = body("1.0.0", false, 2);
    second.withObject("/versions").set("2.0.0", body("2.0.0", false, 3).path("versions").path("2.0.0"));
    second.withObject("/dist-tags").put("latest", "2.0.0").put("beta", "2.0.0");
    second.putObject("time").put("1.0.0", "2026-02-01T00:00:00Z").put("2.0.0", "2026-03-01T00:00:00Z")
        .put("created", "2025-01-01T00:00:00Z").put("modified", "2026-03-01T00:00:00Z");
    var group = merge(first, second);
    var result = group.rewritten("https://repo.example/group");
    assertEquals(2, result.path("versions").size());
    assertEquals("first", result.path("description").asText());
    assertEquals("1.0.0", result.path("dist-tags").path("latest").asText());
    assertEquals("2.0.0", result.path("dist-tags").path("beta").asText());
    assertEquals("2026-01-01T00:00:00Z", result.path("time").path("1.0.0").asText());
    assertEquals("2025-01-01T00:00:00Z", result.path("time").path("created").asText());
    assertEquals(1L, group.selection(HAR).orElseThrow().repositoryId());
    assertTrue(result.path("versions").path("1.0.0").path("dist").path("tarball").asText()
        .startsWith("https://repo.example/group/" + HAR + "?kkrepo_source="));
  }

  @Test
  void hspPairUsesOneFingerprintAndRootTypesDoNotContaminateHarVersions() throws Exception {
    var group = merge(body("1.0.0", true, 1), body("2.0.0", false, 2));
    assertEquals(group.selection(HAR).orElseThrow().fingerprint(), group.selection(HSP).orElseThrow().fingerprint());
    var result = group.rewritten("https://repo.example/group");
    assertFalse(result.has("packageType"));
    assertFalse(result.has("hspType"));
    assertEquals("bundle_app", result.path("versions").path("1.0.0").path("hspType").asText());
    assertFalse(result.path("versions").path("2.0.0").has("hspType"));
    assertDoesNotThrow(() -> OhpmPackageMetadata.parse(PACKAGE, result));
  }

  @Test
  void firstHarVersionDoesNotAcquireSecondMembersHsp() throws Exception {
    var group = merge(body("1.0.0", false, 1), body("1.0.0", true, 2));
    assertTrue(group.selection(HSP).isEmpty());
    assertFalse(group.rewritten("https://repo.example/group").path("versions").path("1.0.0").path("dist").has("resolved_hsp"));
  }

  @Test
  void memberOrderAndEitherFileIntegrityChangeThePairFingerprint() throws Exception {
    var first = body("1.0.0", true, 1);
    var second = body("1.0.0", true, 1);
    var original = merge(first, second).selection(HAR).orElseThrow().fingerprint();
    var reversed = OhpmGroupMetadata.merge(10, PACKAGE,
        List.of(source(2, second), source(1, first))).selection(HAR).orElseThrow().fingerprint();
    assertNotEquals(original, reversed);
    var changed = first.deepCopy();
    changed.withObject("/versions/1.0.0/dist").put("integrity_hsp", integrity(9));
    assertNotEquals(original, merge(changed, second).selection(HAR).orElseThrow().fingerprint());
    changed = first.deepCopy();
    changed.withObject("/versions/1.0.0/dist").put("integrity", integrity(8));
    assertNotEquals(original, merge(changed, second).selection(HSP).orElseThrow().fingerprint());
    assertEquals(original, merge(first, second).selection(HAR).orElseThrow().fingerprint());
  }

  @Test
  void returnedJsonAndInputMutationDoNotChangeSelection() throws Exception {
    var first = body("1.0.0", true, 1);
    var group = merge(first, body("2.0.0", false, 2));
    first.remove("versions");
    group.rewritten("https://repo.example/group").remove("versions");
    assertEquals(2, group.rewritten("https://another.example/group").path("versions").size());
    assertTrue(group.selection(HSP).isPresent());
  }

  @Test
  void emptyGroupHasNoVersions() {
    assertTrue(OhpmGroupMetadata.merge(1, PACKAGE, List.of()).isEmpty());
  }

  private static OhpmGroupMetadata merge(ObjectNode first, ObjectNode second) {
    return OhpmGroupMetadata.merge(10, PACKAGE, List.of(source(1, first), source(2, second)));
  }

  private static OhpmGroupMetadata.Source source(long id, ObjectNode body) {
    return new OhpmGroupMetadata.Source(id, OhpmPackageMetadata.parse(PACKAGE, body));
  }

  private static ObjectNode body(String version, boolean hsp, int content) throws Exception {
    var root = JSON.createObjectNode().put("name", PACKAGE.id());
    root.putObject("dist-tags").put("latest", version);
    var dist = root.putObject("versions").putObject(version).put("name", PACKAGE.id()).put("version", version)
        .putObject("dist").put("tarball", "https://upstream.example/" + version + ".har").put("integrity", integrity(content));
    if (hsp) {
      root.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
      dist.put("resolved_hsp", "https://upstream.example/" + version + ".hsp").put("integrity_hsp", integrity(content + 1));
    }
    return root;
  }

  private static String integrity(int value) throws Exception {
    return OhpmIntegrity.compute(new ByteArrayInputStream(new byte[] {(byte) value})).value();
  }
}
