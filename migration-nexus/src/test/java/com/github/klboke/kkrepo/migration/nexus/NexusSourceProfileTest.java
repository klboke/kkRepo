package com.github.klboke.kkrepo.migration.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.migration.nexus.MigrationPlanBuilder.MigrationScope;
import com.github.klboke.kkrepo.migration.nexus.NexusMigrationPlan.SupportStatus;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.NexusInventory;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.RepositoryDocument;
import com.github.klboke.kkrepo.migration.nexus.NexusRestClient.SourceProbe;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityExport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NexusSourceProfileTest {
  private static final Map<String, Object> VERIFIED_SWIFT_SHAPE = Map.of(
      "archiveAssetPath", true,
      "manifestShape", true,
      "swiftAssetAttributes", true,
      "signatureAttributes", true,
      "sha256Checksum", true,
      "inspectedAssetCount", 4);

  @Test
  void enablesSwiftHostedContentOnlyForKnownNexusVersionsAndVerifiedShape() {
    for (String version : List.of("3.92.0-03", "3.93.1-01", "3.94.0-01")) {
      NexusSourceProfile profile = profile(version, VERIFIED_SWIFT_SHAPE);

      assertTrue(profile.formatCapabilities().get("swift").contentMigration(), version);
      assertEquals(SupportStatus.FULL, hostedStatus(profile), version);
    }
  }

  @Test
  void unknownOrOutOfRangeNexusVersionKeepsSwiftHostedManual() {
    for (String version : List.of("unknown", "3.91.2-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = profile(version, VERIFIED_SWIFT_SHAPE);

      assertFalse(profile.formatCapabilities().get("swift").contentMigration(), version);
      assertEquals(
          "swift-source-version-unverified",
          profile.formatCapabilities().get("swift").evidence(),
          version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, hostedStatus(profile), version);
    }

    NexusSourceProfile requestedVersionOnly = profile(null, "3.94.0-01", VERIFIED_SWIFT_SHAPE);
    assertFalse(requestedVersionOnly.formatCapabilities().get("swift").contentMigration());
    assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, hostedStatus(requestedVersionOnly));
  }

  @Test
  void missingOrDriftedSwiftAssetShapeKeepsHostedMigrationManual() {
    for (String missing : List.of(
        "archiveAssetPath", "manifestShape", "swiftAssetAttributes",
        "signatureAttributes", "sha256Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_SWIFT_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = profile("3.94.0-01", drifted);

      assertFalse(profile.formatCapabilities().get("swift").contentMigration(), missing);
      assertEquals(
          "swift-content-shape-incomplete",
          profile.formatCapabilities().get("swift").evidence(),
          missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, hostedStatus(profile), missing);
    }
  }

  private static SupportStatus hostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("swift-hosted"), false, false))
        .items().stream()
        .filter(item -> "swift-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static NexusSourceProfile profile(
      String probedVersion,
      Map<String, Object> formatShape) {
    return profile(probedVersion, null, formatShape);
  }

  private static NexusSourceProfile profile(
      String probedVersion,
      String requestedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_H2",
        "H2",
        "jdbc:h2:file:/nexus-data/db/nexus",
        datastoreSchema(formatShape),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "swift-hosted",
            "format", "swift",
            "type", "hosted",
            "online", true),
        Map.of("storage", Map.of("blobStoreName", "default")));
    return NexusSourceProfile.fromInventory(
        new NexusInventory(
            List.of(Map.of("name", "default", "type", "File")),
            List.of(repository),
            NexusSecurityExport.empty(),
            List.of(),
            probe),
        requestedVersion);
  }

  private static Map<String, Object> datastoreSchema(Map<String, Object> formatShape) {
    return Map.of("datastoreContentModels", Map.of("swift", Map.of(
        "prefix", "SWIFT",
        "tablesPresent", true,
        "requiredColumnsPresent", true,
        "tables", Map.of(
            "contentRepository", "SWIFT_CONTENT_REPOSITORY",
            "asset", "SWIFT_ASSET",
            "assetBlob", "SWIFT_ASSET_BLOB",
            "component", "SWIFT_COMPONENT"),
        "columns", Map.of(),
        "formatShape", formatShape)));
  }
}
