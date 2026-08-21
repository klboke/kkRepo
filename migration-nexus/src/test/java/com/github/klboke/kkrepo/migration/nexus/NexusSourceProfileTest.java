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
  private static final Map<String, Object> VERIFIED_ANSIBLE_SHAPE = Map.of(
      "collectionAssetPath", true,
      "collectionAttributes", true,
      "sha256Checksum", true,
      "inspectedAssetCount", 1);
  private static final Map<String, Object> VERIFIED_CONDA_SHAPE = Map.of(
      "packageAssetPath", true,
      "sha256Checksum", true,
      "inspectedAssetCount", 2,
      "packageAssetCount", 1);
  private static final Map<String, Object> VERIFIED_CONAN_SHAPE = Map.of(
      "revisionAssetPath", true,
      "manifestObserved", true,
      "sha1Checksum", true,
      "inspectedAssetCount", 4,
      "revisionAssetCount", 4,
      "packageRevisionAssetCount", 2);
  private static final Map<String, Object> VERIFIED_APT_SHAPE = Map.of(
      "packageAssetPath", true,
      "aptAssetAttributes", true,
      "sha256Checksum", true,
      "inspectedAssetCount", 2,
      "packageAssetCount", 1);
  private static final Map<String, Object> VERIFIED_ALPINE_SHAPE = Map.of(
      "packageAssetPath", true,
      "alpineAssetAttributes", true,
      "componentIdentity", true,
      "sha256Checksum", true,
      "inspectedAssetCount", 2,
      "packageAssetCount", 1);
  private static final Map<String, Object> VERIFIED_R_SHAPE = Map.of(
      "sourcePackageAssetPath", true,
      "rAssetAttributes", true,
      "componentIdentity", true,
      "sourceChecksum", true,
      "inspectedAssetCount", 2,
      "sourcePackageAssetCount", 1);
  private static final Map<String, Object> VERIFIED_HUGGINGFACE_SHAPE = Map.of(
      "resolveAssetPath", true,
      "componentCommit", true,
      "sha256Checksum", true,
      "inspectedAssetCount", 3,
      "resolveAssetCount", 2);

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

  @Test
  void enablesAnsibleHostedContentOnlyForNexus393And394WithVerifiedShape() {
    for (String version : List.of("3.93.0-01", "3.93.1-01", "3.94.0-01")) {
      NexusSourceProfile profile = ansibleProfile(version, VERIFIED_ANSIBLE_SHAPE);

      assertTrue(profile.formatCapabilities().get("ansiblegalaxy").contentMigration(), version);
      assertEquals(SupportStatus.FULL, ansibleHostedStatus(profile), version);
    }
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsAnsibleMigrationManual() {
    for (String version : List.of("unknown", "3.92.2-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = ansibleProfile(version, VERIFIED_ANSIBLE_SHAPE);
      assertFalse(profile.formatCapabilities().get("ansiblegalaxy").contentMigration(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, ansibleHostedStatus(profile), version);
    }
    for (String missing : List.of(
        "collectionAssetPath", "collectionAttributes", "sha256Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_ANSIBLE_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = ansibleProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("ansiblegalaxy").contentMigration(), missing);
      assertEquals(
          "ansiblegalaxy-content-shape-incomplete",
          profile.formatCapabilities().get("ansiblegalaxy").evidence(),
          missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, ansibleHostedStatus(profile), missing);
    }
  }

  @Test
  void enablesCondaHostedContentOnlyForKnownVersionsAndVerifiedShape() {
    for (String version : List.of("3.92.0-01", "3.93.1-01", "3.94.0-01")) {
      NexusSourceProfile profile = condaProfile(version, VERIFIED_CONDA_SHAPE);

      assertTrue(profile.formatCapabilities().get("conda").contentMigration(), version);
      assertEquals(SupportStatus.FULL, condaHostedStatus(profile), version);
    }
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsCondaHostedMigrationManual() {
    for (String version : List.of("unknown", "3.91.2-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = condaProfile(version, VERIFIED_CONDA_SHAPE);
      assertFalse(profile.formatCapabilities().get("conda").contentMigration(), version);
      assertEquals("conda-source-version-unverified",
          profile.formatCapabilities().get("conda").evidence(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, condaHostedStatus(profile), version);
    }
    for (String missing : List.of("packageAssetPath", "sha256Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_CONDA_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = condaProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("conda").contentMigration(), missing);
      assertEquals("conda-content-shape-incomplete",
          profile.formatCapabilities().get("conda").evidence(), missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, condaHostedStatus(profile), missing);
    }
  }

  @Test
  void enablesConanContentOnlyForNexus394AndVerifiedShape() {
    NexusSourceProfile profile = conanProfile("3.94.0-01", VERIFIED_CONAN_SHAPE);

    assertTrue(profile.formatCapabilities().get("conan").contentMigration());
    assertEquals(SupportStatus.FULL, conanHostedStatus(profile));
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsConanMigrationManual() {
    for (String version : List.of("unknown", "3.93.1-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = conanProfile(version, VERIFIED_CONAN_SHAPE);
      assertFalse(profile.formatCapabilities().get("conan").contentMigration(), version);
      assertEquals("conan-source-version-unverified",
          profile.formatCapabilities().get("conan").evidence(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, conanHostedStatus(profile), version);
    }
    for (String missing : List.of("revisionAssetPath", "manifestObserved", "sha1Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_CONAN_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = conanProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("conan").contentMigration(), missing);
      assertEquals("conan-content-shape-incomplete",
          profile.formatCapabilities().get("conan").evidence(), missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, conanHostedStatus(profile), missing);
    }
  }

  @Test
  void enablesAptHostedContentOnlyForKnownVersionsAndVerifiedShape() {
    for (String version : List.of("3.92.0-01", "3.93.1-01", "3.94.0-01")) {
      NexusSourceProfile profile = aptProfile(version, VERIFIED_APT_SHAPE);

      assertTrue(profile.formatCapabilities().get("apt").contentMigration(), version);
      assertEquals(SupportStatus.FULL, aptHostedStatus(profile), version);
    }
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsAptHostedMigrationManual() {
    for (String version : List.of("unknown", "3.91.2-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = aptProfile(version, VERIFIED_APT_SHAPE);
      assertFalse(profile.formatCapabilities().get("apt").contentMigration(), version);
      assertEquals("apt-source-version-unverified",
          profile.formatCapabilities().get("apt").evidence(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, aptHostedStatus(profile), version);
    }
    for (String missing : List.of("packageAssetPath", "aptAssetAttributes", "sha256Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_APT_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = aptProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("apt").contentMigration(), missing);
      assertEquals("apt-content-shape-incomplete",
          profile.formatCapabilities().get("apt").evidence(), missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, aptHostedStatus(profile), missing);
    }
  }

  @Test
  void enablesAlpineContentOnlyForExactNexus394Shape() {
    NexusSourceProfile profile = alpineProfile("3.94.0-01", VERIFIED_ALPINE_SHAPE);

    assertTrue(profile.formatCapabilities().get("alpine").contentMigration());
    assertEquals(SupportStatus.FULL, alpineHostedStatus(profile));
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsAlpineMigrationManual() {
    for (String version : List.of("unknown", "3.93.1-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = alpineProfile(version, VERIFIED_ALPINE_SHAPE);
      assertFalse(profile.formatCapabilities().get("alpine").contentMigration(), version);
      assertEquals("alpine-source-version-unverified",
          profile.formatCapabilities().get("alpine").evidence(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, alpineHostedStatus(profile), version);
    }
    for (String missing : List.of(
        "packageAssetPath", "alpineAssetAttributes", "componentIdentity", "sha256Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_ALPINE_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = alpineProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("alpine").contentMigration(), missing);
      assertEquals("alpine-content-shape-incomplete",
          profile.formatCapabilities().get("alpine").evidence(), missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, alpineHostedStatus(profile), missing);
    }
  }

  @Test
  void enablesRContentOnlyForExactNexus394Shape() {
    NexusSourceProfile profile = rProfile("3.94.0-01", VERIFIED_R_SHAPE);

    assertTrue(profile.formatCapabilities().get("r").contentMigration());
    assertEquals(SupportStatus.FULL, rHostedStatus(profile));
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsRMigrationManual() {
    for (String version : List.of("unknown", "3.93.1-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = rProfile(version, VERIFIED_R_SHAPE);
      assertFalse(profile.formatCapabilities().get("r").contentMigration(), version);
      assertEquals("r-source-version-unverified",
          profile.formatCapabilities().get("r").evidence(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, rHostedStatus(profile), version);
    }
    for (String missing : List.of(
        "sourcePackageAssetPath", "rAssetAttributes", "componentIdentity", "sourceChecksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_R_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = rProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("r").contentMigration(), missing);
      assertEquals("r-content-shape-incomplete",
          profile.formatCapabilities().get("r").evidence(), missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, rHostedStatus(profile), missing);
    }
  }

  @Test
  void enablesHuggingFaceProxyContentOnlyForExactNexus394Shape() {
    NexusSourceProfile profile = huggingFaceProfile("3.94.0-01", VERIFIED_HUGGINGFACE_SHAPE);

    assertTrue(profile.formatCapabilities().get("huggingface").contentMigration());
    assertEquals(SupportStatus.FULL, huggingFaceProxyStatus(profile));
  }

  @Test
  void unknownVersionOrDriftedShapeKeepsHuggingFaceMigrationManual() {
    for (String version : List.of("unknown", "3.93.1-01", "3.95.0-01", "4.0.0")) {
      NexusSourceProfile profile = huggingFaceProfile(version, VERIFIED_HUGGINGFACE_SHAPE);
      assertFalse(profile.formatCapabilities().get("huggingface").contentMigration(), version);
      assertEquals("huggingface-source-version-unverified",
          profile.formatCapabilities().get("huggingface").evidence(), version);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, huggingFaceProxyStatus(profile), version);
    }
    for (String missing : List.of("resolveAssetPath", "componentCommit", "sha256Checksum")) {
      Map<String, Object> drifted = new LinkedHashMap<>(VERIFIED_HUGGINGFACE_SHAPE);
      drifted.remove(missing);
      NexusSourceProfile profile = huggingFaceProfile("3.94.0-01", drifted);
      assertFalse(profile.formatCapabilities().get("huggingface").contentMigration(), missing);
      assertEquals("huggingface-content-shape-incomplete",
          profile.formatCapabilities().get("huggingface").evidence(), missing);
      assertEquals(SupportStatus.NEEDS_MANUAL_ACTION, huggingFaceProxyStatus(profile), missing);
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

  private static SupportStatus ansibleHostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("ansible-hosted"), false, false))
        .items().stream()
        .filter(item -> "ansible-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static SupportStatus condaHostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("conda-hosted"), false, false))
        .items().stream()
        .filter(item -> "conda-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static SupportStatus aptHostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("apt-hosted"), false, false))
        .items().stream()
        .filter(item -> "apt-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static SupportStatus conanHostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("conan-hosted"), false, false))
        .items().stream()
        .filter(item -> "conan-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static SupportStatus alpineHostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("alpine-hosted"), false, false))
        .items().stream()
        .filter(item -> "alpine-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static SupportStatus rHostedStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("r-hosted"), false, false))
        .items().stream()
        .filter(item -> "r-hosted".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static SupportStatus huggingFaceProxyStatus(NexusSourceProfile profile) {
    return new MigrationPlanBuilder().build(
            profile, new MigrationScope(List.of("huggingface-proxy"), false, true))
        .items().stream()
        .filter(item -> "huggingface-proxy".equals(item.name()))
        .findFirst()
        .orElseThrow()
        .status();
  }

  private static NexusSourceProfile huggingFaceProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("huggingface", Map.of(
            "prefix", "HUGGINGFACE",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "HUGGINGFACE_CONTENT_REPOSITORY",
                "asset", "HUGGINGFACE_ASSET",
                "assetBlob", "HUGGINGFACE_ASSET_BLOB",
                "component", "HUGGINGFACE_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "huggingface-proxy",
            "format", "huggingface",
            "type", "proxy",
            "online", true),
        Map.of(
            "storage", Map.of("blobStoreName", "default"),
            "proxy", Map.of("remoteUrl", "https://huggingface.co")));
    return NexusSourceProfile.fromInventory(
        new NexusInventory(
            List.of(Map.of("name", "default", "type", "File")),
            List.of(repository),
            NexusSecurityExport.empty(),
            List.of(),
            probe),
        null);
  }

  private static NexusSourceProfile alpineProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("alpine", Map.of(
            "prefix", "ALPINE",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "ALPINE_CONTENT_REPOSITORY",
                "asset", "ALPINE_ASSET",
                "assetBlob", "ALPINE_ASSET_BLOB",
                "component", "ALPINE_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "alpine-hosted",
            "format", "alpine",
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
        null);
  }

  private static NexusSourceProfile rProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("r", Map.of(
            "prefix", "R",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "R_CONTENT_REPOSITORY",
                "asset", "R_ASSET",
                "assetBlob", "R_ASSET_BLOB",
                "component", "R_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "r-hosted",
            "format", "r",
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
        null);
  }

  private static NexusSourceProfile conanProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("conan", Map.of(
            "prefix", "CONAN",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "CONAN_CONTENT_REPOSITORY",
                "asset", "CONAN_ASSET",
                "assetBlob", "CONAN_ASSET_BLOB",
                "component", "CONAN_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "conan-hosted",
            "format", "conan",
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
        null);
  }

  private static NexusSourceProfile aptProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("apt", Map.of(
            "prefix", "APT",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "APT_CONTENT_REPOSITORY",
                "asset", "APT_ASSET",
                "assetBlob", "APT_ASSET_BLOB",
                "component", "APT_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "apt-hosted",
            "format", "apt",
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
        null);
  }

  private static NexusSourceProfile condaProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("conda", Map.of(
            "prefix", "CONDA",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "CONDA_CONTENT_REPOSITORY",
                "asset", "CONDA_ASSET",
                "assetBlob", "CONDA_ASSET_BLOB",
                "component", "CONDA_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "conda-hosted",
            "format", "conda",
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
        null);
  }

  private static NexusSourceProfile ansibleProfile(
      String probedVersion,
      Map<String, Object> formatShape) {
    SourceProbe probe = new SourceProbe(
        probedVersion,
        true,
        true,
        true,
        "text/plain",
        "ok",
        "DATASTORE_POSTGRESQL",
        "PostgreSQL",
        "jdbc:postgresql://nexus/nexus",
        Map.of("datastoreContentModels", Map.of("ansiblegalaxy", Map.of(
            "prefix", "ANSIBLEGALAXY",
            "tablesPresent", true,
            "requiredColumnsPresent", true,
            "tables", Map.of(
                "contentRepository", "ANSIBLEGALAXY_CONTENT_REPOSITORY",
                "asset", "ANSIBLEGALAXY_ASSET",
                "assetBlob", "ANSIBLEGALAXY_ASSET_BLOB",
                "component", "ANSIBLEGALAXY_COMPONENT"),
            "columns", Map.of(),
            "formatShape", formatShape))),
        List.of());
    RepositoryDocument repository = new RepositoryDocument(
        Map.of(
            "name", "ansible-hosted",
            "format", "ansiblegalaxy",
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
        null);
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
