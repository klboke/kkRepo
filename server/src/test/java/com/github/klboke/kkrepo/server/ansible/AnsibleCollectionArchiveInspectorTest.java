package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class AnsibleCollectionArchiveInspectorTest {

  @Test
  void validatesManifestInventoryIdentityDependenciesAndRequiresAnsible() throws Exception {
    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.2.3");
    AnsibleCollectionArchiveInspector inspector =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);

    AnsibleCollectionArchiveInspector.InspectedCollection inspected =
        inspector.inspect(new ByteArrayInputStream(archive));
    try {
      assertEquals("acme", inspected.namespace());
      assertEquals("tools", inspected.name());
      assertEquals("1.2.3", inspected.version());
      assertEquals("acme-tools-1.2.3.tar.gz", inspected.filename());
      assertEquals(archive.length, inspected.size());
      assertEquals(AnsibleCollectionTestArchive.sha256(archive), inspected.sha256());
      assertEquals(">=1.0.0", inspected.dependencies().get("acme.base"));
      assertEquals(">=2.15", inspected.requiresAnsible());
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  @Test
  void rejectsChecksumDriftUnsafePathsAndCompressedLimit() throws Exception {
    AnsibleCollectionArchiveInspector inspector =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);

    AnsibleGalaxyExceptions.BadRequest mismatch = assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class,
        () -> inspector.inspect(new ByteArrayInputStream(
            AnsibleCollectionTestArchive.checksumMismatch())));
    assertTrue(mismatch.getMessage().contains("checksum mismatch"));

    assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class,
        () -> inspector.inspect(new ByteArrayInputStream(AnsibleCollectionTestArchive.unsafePath())));

    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.0.0");
    AnsibleCollectionArchiveInspector bounded =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), archive.length - 1L, 1024 * 1024, 100);
    assertThrows(
        AnsibleGalaxyExceptions.ContentTooLarge.class,
        () -> bounded.inspect(new ByteArrayInputStream(archive)));
  }

  @Test
  void acceptsSafeCollectionSymlinksAndRejectsArchiveEscapes() throws Exception {
    AnsibleCollectionArchiveInspector inspector =
        new AnsibleCollectionArchiveInspector(new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);

    AnsibleCollectionArchiveInspector.InspectedCollection inspected =
        inspector.inspect(new ByteArrayInputStream(AnsibleCollectionTestArchive.withSymbolicLink()));
    Files.deleteIfExists(inspected.file());

    AnsibleGalaxyExceptions.BadRequest escape = assertThrows(
        AnsibleGalaxyExceptions.BadRequest.class,
        () -> inspector.inspect(new ByteArrayInputStream(
            AnsibleCollectionTestArchive.escapingSymbolicLink())));
    assertTrue(escape.getMessage().contains("escapes the archive"));
  }
}
