package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationSourceMetadataTest {
  @Test
  void readsNestedNexusChecksumsAndNormalizesCaseAndSeparators() {
    Object metadata = Map.of("attributes", List.of(Map.of("checksum", Map.of("SHA-256", " " + "A".repeat(64) + " "))));
    assertEquals("a".repeat(64), MigrationSourceMetadata.checksum(metadata, 64, "sha256"));
  }

  @Test
  void preservesExplicitAliasAndDigestRequirements() {
    Object metadata = Map.of("checksum.sha256", "b".repeat(64), "sha1", "c".repeat(40));
    assertNull(MigrationSourceMetadata.checksum(metadata, 64, "sha256"));
    assertEquals("b".repeat(64), MigrationSourceMetadata.checksum(metadata, 64, "sha256", "checksum.sha256"));
    assertEquals("c".repeat(40), MigrationSourceMetadata.checksum(metadata, 40, "sha1"));
  }

  @Test
  void malformedPreferredValueDoesNotSilentlyFallBackToAnotherAlias() {
    Object metadata = Map.of("sha256", "bad", "checksum.sha256", "d".repeat(64));
    assertNull(MigrationSourceMetadata.checksum(metadata, 64, "sha256", "checksum.sha256"));
    assertNull(MigrationSourceMetadata.checksum(Map.of("sha256", "z".repeat(64)), 64, "sha256"));
    assertNull(MigrationSourceMetadata.checksum(null, 64, "sha256"));
  }

  @Test
  void directKeysTakePrecedenceOverNestedValues() {
    Object metadata = Map.of("sha256", "a".repeat(64), "nested", Map.of("sha256", "b".repeat(64)));
    assertEquals("a".repeat(64), MigrationSourceMetadata.checksum(metadata, 64, "sha256"));
  }
}
