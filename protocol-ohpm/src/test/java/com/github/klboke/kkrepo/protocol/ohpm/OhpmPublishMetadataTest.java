package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class OhpmPublishMetadataTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/pkg");

  @Test
  void acceptsHarAndKeepsUnknownClientMetadata() throws Exception {
    ObjectNode body = har();
    body.put("client-extension", "preserved");
    OhpmPublishMetadata parsed = OhpmPublishMetadata.parse(PACKAGE, body);
    assertEquals("1.2.3", parsed.version());
    assertFalse(parsed.isHsp());
    assertEquals("preserved", parsed.metadata().path("client-extension").textValue());
    body.put("client-extension", "changed");
    assertEquals("preserved", parsed.metadata().path("client-extension").textValue());
  }

  @Test
  void acceptsHspTypeAtRootAndDoesNotRequireServerGeneratedDownloadUrl() throws Exception {
    ObjectNode body = hsp();
    assertTrue(OhpmPublishMetadata.parse(PACKAGE, body).isHsp());
    ObjectNode manifest = manifest(body);
    manifest.put("hspType", body.remove("hspType").textValue());
    assertTrue(OhpmPublishMetadata.parse(PACKAGE, body).isHsp());
  }

  @Test
  void rejectsMetadataThatChangesPackageOrVersionIdentity() throws Exception {
    for (String field : new String[] {"name", "_id"}) {
      ObjectNode body = har();
      body.put(field, "@scope/other");
      assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, body));
    }
    for (String field : new String[] {"name", "version", "_id"}) {
      ObjectNode body = har();
      manifest(body).put(field, "other");
      assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, body));
    }
    ObjectNode multiple = har();
    ((ObjectNode) multiple.path("versions")).set("2.0.0", manifest(multiple).deepCopy());
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, multiple));
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, MAPPER.readTree("[]")));
  }

  @Test
  void customPublishTagDoesNotRequireLatestAndCannotReferenceAnotherVersion() throws Exception {
    ObjectNode body = har();
    ObjectNode tags = (ObjectNode) body.path("dist-tags");
    tags.remove("latest");
    tags.put("beta", "1.2.3");
    assertEquals("1.2.3", OhpmPublishMetadata.parse(PACKAGE, body).version());
    tags.put("latest", "4.5.6");
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, body));
  }

  @Test
  void importAcceptsUntaggedHistoricalVersionsWithoutChangingPublishContract() throws Exception {
    ObjectNode body = har();
    body.putObject("dist-tags");
    assertEquals("1.2.3", OhpmPublishMetadata.forImport(PACKAGE, body).version());
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, body));
    ((ObjectNode) body.path("dist-tags")).put("latest", "2.0.0");
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.forImport(PACKAGE, body));
    body.putObject("dist-tags");
    ((ObjectNode) manifest(body).path("dist")).put("integrity", "invalid");
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.forImport(PACKAGE, body));
  }

  @Test
  void rejectsIncompleteOrConflictingHspMetadata() throws Exception {
    for (String field : new String[] {"types", "packageType"}) {
      ObjectNode body = hsp();
      manifest(body).remove(field);
      if (field.equals("packageType")) body.remove(field);
      assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, body));
    }
    ObjectNode missingIntegrity = hsp();
    ((ObjectNode) manifest(missingIntegrity).path("dist")).remove("integrity_hsp");
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, missingIntegrity));
    ObjectNode conflict = hsp();
    manifest(conflict).put("hspType", "other");
    assertThrows(IllegalArgumentException.class, () -> OhpmPublishMetadata.parse(PACKAGE, conflict));
  }

  @Test
  void versionsFollowSemverIncludingPrereleaseAndBuildIdentity() {
    for (String version : new String[] {"0.0.0", "v1.2.3", "1.2.3-beta.1", "1.2.3+build.001",
        "1.2.3-0.a-b+build", "9007199254740991.0.0"}) {
      assertTrue(OhpmVersions.isValid(version), version);
    }
    for (String version : new String[] {"1", "1.2", "01.2.3", "1.2.3-01", "1.2.3-", "1.2.3+", "1.2.3 a",
        "9007199254740992.0.0", "99999999999999999999.0.0"}) {
      assertFalse(OhpmVersions.isValid(version), version);
    }
  }

  private static ObjectNode har() throws Exception {
    ObjectNode body = (ObjectNode) MAPPER.readTree("""
        {"_id":"@scope/pkg","name":"@scope/pkg","dist-tags":{"latest":"1.2.3"},
         "versions":{"1.2.3":{"name":"@scope/pkg","version":"1.2.3","main":"Index.ets","dist":{}}}}
        """);
    ((ObjectNode) manifest(body).path("dist")).put("integrity",
        OhpmIntegrity.compute(new ByteArrayInputStream(new byte[] {1, 2, 3})).value());
    return body;
  }

  private static ObjectNode hsp() throws Exception {
    ObjectNode body = har();
    body.put("packageType", "InterfaceHar");
    body.put("hspType", "bundle_app");
    manifest(body).put("packageType", "InterfaceHar");
    manifest(body).put("types", "Index.d.ets");
    ((ObjectNode) manifest(body).path("dist")).put("integrity_hsp",
        manifest(body).path("dist").path("integrity").textValue());
    return body;
  }

  private static ObjectNode manifest(ObjectNode body) {
    return (ObjectNode) body.path("versions").path("1.2.3");
  }
}
