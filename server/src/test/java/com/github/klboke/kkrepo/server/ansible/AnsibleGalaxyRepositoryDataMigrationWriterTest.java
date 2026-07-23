package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnsibleGalaxyRepositoryDataMigrationWriterTest {
  private static final Instant SOURCE_TIME = Instant.parse("2026-01-02T03:04:05Z");

  @Test
  void restoresVerifiedHostedCollectionThroughTheProtocolImporter() throws Exception {
    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.2.3");
    String sha256 = AnsibleCollectionTestArchive.sha256(archive);
    Fixture fixture = fixture(RepositoryType.HOSTED, sha256, archive.length);
    RepositoryDataMigrationAssetRecord source = source(
        "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz",
        "acme", "acme.tools", "1.2.3", archive.length,
        Map.of("attributes", Map.of("checksums", Map.of("sha256", sha256))));

    AnsibleGalaxyRepositoryDataMigrationWriter.MigratedAsset migrated =
        fixture.writer().write(
            fixture.repository(), source, new ByteArrayInputStream(archive), true);

    assertEquals(10L, migrated.componentId());
    assertEquals(20L, migrated.assetId());
    assertEquals(30L, migrated.assetBlobId());
    assertEquals("ansible/acme-tools-1.2.3.tar.gz", migrated.assetBlobObjectKey());
    ArgumentCaptor<AnsibleCollectionArchiveInspector.InspectedCollection> inspected =
        ArgumentCaptor.forClass(AnsibleCollectionArchiveInspector.InspectedCollection.class);
    verify(fixture.service()).restoreCollectionForMigration(
        eq(fixture.runtime()), inspected.capture(), eq(SOURCE_TIME),
        eq("nexus-user"), eq("10.0.0.1"));
    assertEquals("acme", inspected.getValue().namespace());
    assertEquals("tools", inspected.getValue().name());
    assertEquals("1.2.3", inspected.getValue().version());
    assertEquals(sha256, inspected.getValue().sha256());
  }

  @Test
  void acceptsOnlyCanonicalArchivesWithMatchingSourceIdentitySizeAndSha() throws Exception {
    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.2.3");
    String sha256 = AnsibleCollectionTestArchive.sha256(archive);
    Fixture fixture = fixture(RepositoryType.HOSTED, sha256, archive.length);

    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("acme/tools/1.2.3/not-canonical.tar.gz", "acme", "acme.tools",
            "1.2.3", archive.length, Map.of("sha256", sha256)),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("acme/tools/1.2.3/acme-tools-1.2.3.tar.gz", "other", "acme.tools",
            "1.2.3", archive.length, Map.of("sha256", sha256)),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("acme/tools/1.2.3/acme-tools-1.2.3.tar.gz", "acme", "acme.tools",
            "1.2.3", archive.length + 1L, Map.of("sha256", sha256)),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("acme/tools/1.2.3/acme-tools-1.2.3.tar.gz", "acme", "acme.tools",
            "1.2.3", archive.length, Map.of("sha256", "f".repeat(64))),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("acme/tools/1.2.3/acme-tools-1.2.3.tar.gz", "acme", "acme.tools",
            "1.2.3", archive.length, Map.of()),
        new ByteArrayInputStream(archive), true));

    verify(fixture.service(), never()).restoreCollectionForMigration(
        any(), any(), any(), any(), any());
  }

  @Test
  void supportsExplicitProxyCacheButRejectsGroupsAndUnrelatedFormats() throws Exception {
    byte[] archive = AnsibleCollectionTestArchive.valid("acme", "tools", "1.2.3");
    String sha256 = AnsibleCollectionTestArchive.sha256(archive);
    Fixture proxy = fixture(RepositoryType.PROXY, sha256, archive.length);
    RepositoryDataMigrationAssetRecord source = source(
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz",
        "acme", "acme.tools", "1.2.3", archive.length,
        Map.of("checksums", Map.of("sha256", sha256)));

    AnsibleGalaxyRepositoryDataMigrationWriter.MigratedAsset migrated = proxy.writer().write(
        proxy.repository(), source, new ByteArrayInputStream(archive), true);
    assertEquals(20L, migrated.assetId());

    Fixture group = fixture(RepositoryType.GROUP, sha256, archive.length);
    assertThrows(IllegalArgumentException.class, () -> group.writer().write(
        group.repository(), source, new ByteArrayInputStream(archive), true));
    RepositoryRecord raw = new RepositoryRecord(
        7L, "raw-hosted", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted",
        true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
    assertThrows(IllegalArgumentException.class, () -> proxy.writer().write(
        raw, source, new ByteArrayInputStream(archive), true));

    assertTrue(AnsibleGalaxyRepositoryDataMigrationWriter.isMigratableAnsiblePath(
        "acme/tools/1.2.3/acme-tools-1.2.3.tar.gz"));
    assertTrue(AnsibleGalaxyRepositoryDataMigrationWriter.isMigratableAnsiblePath(
        source.sourcePath()));
  }

  private static Fixture fixture(
      RepositoryType type, String sha256, long archiveSize) {
    AnsibleCollectionArchiveInspector inspector = new AnsibleCollectionArchiveInspector(
        new ObjectMapper(), 1024 * 1024, 1024 * 1024, 100);
    AnsibleGalaxyService service = mock(AnsibleGalaxyService.class);
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AssetDao assets = mock(AssetDao.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "ansible-" + type.name().toLowerCase(), RepositoryFormat.ANSIBLEGALAXY, type,
        "ansiblegalaxy-" + type.name().toLowerCase(), true, 1L, "ALLOW_ONCE",
        null, null, true,
        type == RepositoryType.PROXY ? "https://galaxy.ansible.com/" : null,
        60, 60, List.of());
    RepositoryRecord repository = new RepositoryRecord(
        7L, runtime.name(), RepositoryFormat.ANSIBLEGALAXY, type, runtime.recipeName(),
        true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
    AnsibleGalaxyRegistryDao.CollectionVersion restored = new AnsibleGalaxyRegistryDao.CollectionVersion(
        1L, 7L, 10L, 20L, "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
        "acme-tools-1.2.3.tar.gz", sha256, archiveSize,
        Map.of(), Map.of(), null,
        type == RepositoryType.PROXY ? "MIGRATION_PROXY" : "MIGRATION_HOSTED",
        1L, AnsibleGalaxyRegistryDao.VERSION_READY, SOURCE_TIME, SOURCE_TIME, SOURCE_TIME);
    when(runtimes.resolveById(repository.id())).thenReturn(Optional.of(runtime));
    when(service.restoreCollectionForMigration(
        eq(runtime), any(), any(), any(), any())).thenReturn(restored);
    AssetRecord asset = new AssetRecord(
        20L, 7L, 10L, 30L, RepositoryFormat.ANSIBLEGALAXY,
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz",
        new byte[32], "acme-tools-1.2.3.tar.gz", "ansible-collection",
        "application/octet-stream", archiveSize, null, SOURCE_TIME, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        30L, 1L, "blob-ref", new byte[32], "ansible/acme-tools-1.2.3.tar.gz",
        new byte[32], null, sha256, null, archiveSize, "application/octet-stream",
        "nexus-user", "10.0.0.1", SOURCE_TIME, SOURCE_TIME, Map.of());
    when(assets.findAssetById(asset.id())).thenReturn(Optional.of(asset));
    when(assets.findBlobById(blob.id())).thenReturn(Optional.of(blob));
    return new Fixture(
        new AnsibleGalaxyRepositoryDataMigrationWriter(
            inspector, service, registry, runtimes, assets),
        service, runtime, repository);
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path,
      String namespace,
      String name,
      String version,
      long size,
      Map<String, Object> metadata) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "asset-1", "component-1", path, new byte[32],
        RepositoryFormat.ANSIBLEGALAXY, namespace, name, version,
        "collection_package", "application/octet-stream", size,
        "source-blob-ref", SOURCE_TIME, null, SOURCE_TIME, SOURCE_TIME,
        "nexus-user", "10.0.0.1", "PENDING", 0, null, null,
        null, null, null, null, metadata, SOURCE_TIME);
  }

  private record Fixture(
      AnsibleGalaxyRepositoryDataMigrationWriter writer,
      AnsibleGalaxyService service,
      RepositoryRuntime runtime,
      RepositoryRecord repository) {
  }
}
