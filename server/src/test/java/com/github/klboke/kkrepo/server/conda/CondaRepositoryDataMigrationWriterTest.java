package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CondaRepositoryDataMigrationWriterTest {

  @Test
  void selectsOnlyCondaPackageArchives() {
    assertTrue(CondaRepositoryDataMigrationWriter.isMigratableCondaPath(
        "/team/release/linux-64/demo-1.0-0.tar.bz2"));
    assertTrue(CondaRepositoryDataMigrationWriter.isMigratableCondaPath(
        "noarch/demo-1.0-0.conda"));
    assertFalse(CondaRepositoryDataMigrationWriter.isMigratableCondaPath(
        "main/linux-64/repodata.json"));
    assertFalse(CondaRepositoryDataMigrationWriter.isMigratableCondaPath(
        "main/linux-64/../../escape.conda"));
    assertFalse(CondaRepositoryDataMigrationWriter.isMigratableCondaPath(null));
  }

  @Test
  void validatesAndRestoresHostedPackageThroughTheNormalImporter() throws Exception {
    Fixture fixture = fixture();
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    String sha256 = sha256(archive);
    String path = "team/release/linux-64/demo-1.0-0.tar.bz2";
    Instant publishedAt = Instant.parse("2026-08-01T02:03:04Z");
    RepositoryDataMigrationAssetRecord source = source(
        "/" + path + "/", (long) archive.length, sha256, publishedAt);
    CondaRegistryDao.PackageRecord restored = new CondaRegistryDao.PackageRecord(
        1L, fixture.runtime().id(), "team/release", "linux-64", "demo-1.0-0.tar.bz2",
        "demo", "1.0", "0", 0, "tar.bz2", Map.of(), sha256, "a".repeat(32), sha256,
        archive.length, 101L, 201L, CondaRegistryDao.SOURCE_HOSTED, 1,
        publishedAt, publishedAt);
    when(fixture.service().restoreHostedPackageForMigration(
        eq(fixture.runtime()), any(), any(), eq("application/x-conda-test"),
        eq("nexus-user"), eq("10.0.0.8"), eq(publishedAt)))
        .thenReturn(restored);
    AssetRecord asset = new AssetRecord(
        101L, fixture.runtime().id(), 201L, 102L, RepositoryFormat.CONDA, path,
        new byte[32], "demo-1.0-0.tar.bz2", "package", "application/x-conda-test",
        (long) archive.length, null, publishedAt, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        102L, 1L, "blob", new byte[32], "conda-object", new byte[32], "sha1", sha256,
        "a".repeat(32), archive.length, "application/x-conda-test", "nexus-user",
        "10.0.0.8", publishedAt, publishedAt, Map.of());
    when(fixture.assets().findAssetById(101L)).thenReturn(Optional.of(asset));
    when(fixture.assets().findBlobById(102L)).thenReturn(Optional.of(blob));

    CondaRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(archive),
        "application/x-conda-test", true);

    assertEquals(201L, migrated.componentId());
    assertEquals(101L, migrated.assetId());
    assertEquals(102L, migrated.assetBlobId());
    assertEquals("conda-object", migrated.assetBlobObjectKey());
    ArgumentCaptor<CondaPath> pathCaptor = ArgumentCaptor.forClass(CondaPath.class);
    verify(fixture.service()).restoreHostedPackageForMigration(
        eq(fixture.runtime()), pathCaptor.capture(), any(), any(), any(), any(), eq(publishedAt));
    assertEquals("team/release", pathCaptor.getValue().channel());
    assertEquals("linux-64", pathCaptor.getValue().subdir());
  }

  @Test
  void rejectsChecksumSizeAndRepositoryShapeMismatches() throws Exception {
    Fixture fixture = fixture();
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    String path = "main/linux-64/demo-1.0-0.tar.bz2";
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(), source(path, (long) archive.length, "f".repeat(64), Instant.EPOCH),
        new ByteArrayInputStream(archive), null, true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(), source(path, (long) archive.length + 1, sha256(archive), Instant.EPOCH),
        new ByteArrayInputStream(archive), null, true));

    RepositoryRecord proxy = new RepositoryRecord(
        9L, "conda-proxy", RepositoryFormat.CONDA, RepositoryType.PROXY, "conda-proxy",
        true, 1L, null, "https://repo.example/", null, null, null, true, Map.of());
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        proxy, source(path, (long) archive.length, sha256(archive), Instant.EPOCH),
        new ByteArrayInputStream(archive), null, true));
  }

  @Test
  void rejectsUnavailableRuntimeMissingDigestAndCoordinateDisagreement() throws Exception {
    Fixture fixture = fixture();
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    String path = "main/linux-64/demo-1.0-0.tar.bz2";
    RepositoryRecord unavailable = new RepositoryRecord(
        99L, "missing", RepositoryFormat.CONDA, RepositoryType.HOSTED, "conda-hosted",
        true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        unavailable, source(path, (long) archive.length, sha256(archive), Instant.EPOCH),
        new ByteArrayInputStream(archive), null, true));

    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(), source(path, (long) archive.length, null, Instant.EPOCH),
        new ByteArrayInputStream(archive), null, true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(), source(
            path, (long) archive.length, sha256(archive), Instant.EPOCH, "other", "1.0"),
        new ByteArrayInputStream(archive), null, true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(), source(
            path, (long) archive.length, sha256(archive), Instant.EPOCH, "demo", "2.0"),
        new ByteArrayInputStream(archive), null, true));

    RepositoryRecord proxy = new RepositoryRecord(
        9L, "conda-proxy", RepositoryFormat.CONDA, RepositoryType.PROXY, "conda-proxy",
        true, 1L, null, "https://repo.example/", null, null, null, true, Map.of());
    ByteArrayInputStream closeFailure = new ByteArrayInputStream(archive) {
      @Override
      public void close() throws IOException {
        throw new IOException("close failed");
      }
    };
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        proxy, source(path, (long) archive.length, sha256(archive), Instant.EPOCH),
        closeFailure, null, true));
  }

  @Test
  void validatesRestoredAssetAndBlobBindings() throws Exception {
    Fixture fixture = fixture();
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    String digest = sha256(archive);
    String path = "main/linux-64/demo-1.0-0.tar.bz2";
    RepositoryDataMigrationAssetRecord source = source(
        path, -1L, digest, null, null, null,
        Map.of("nested", List.of(Map.of("checksum_sha256", digest))));
    CondaRegistryDao.PackageRecord noAsset = migratedRecord(fixture.runtime().id(), digest, null);
    CondaRegistryDao.PackageRecord bound = migratedRecord(fixture.runtime().id(), digest, 101L);
    when(fixture.service().restoreHostedPackageForMigration(
        eq(fixture.runtime()), any(), any(), any(), any(), any(), any()))
        .thenReturn(noAsset, bound, bound, bound);
    AssetRecord noBlob = new AssetRecord(
        101L, fixture.runtime().id(), 201L, null, RepositoryFormat.CONDA, path,
        new byte[32], "demo-1.0-0.tar.bz2", "package", "application/x-tar",
        (long) archive.length, null, Instant.EPOCH, Map.of());
    AssetRecord asset = new AssetRecord(
        101L, fixture.runtime().id(), 201L, 102L, RepositoryFormat.CONDA, path,
        new byte[32], "demo-1.0-0.tar.bz2", "package", "application/x-tar",
        (long) archive.length, null, Instant.EPOCH, Map.of());
    when(fixture.assets().findAssetById(101L)).thenReturn(
        Optional.empty(), Optional.of(noBlob), Optional.of(asset));
    when(fixture.assets().findBlobById(102L)).thenReturn(Optional.of(new AssetBlobRecord(
        102L, 1L, "blob", new byte[32], "object", new byte[32], "sha1", "f".repeat(64),
        "a".repeat(32), archive.length, "application/x-tar", "nexus", null,
        Instant.EPOCH, Instant.EPOCH, Map.of())));

    for (int attempt = 0; attempt < 4; attempt++) {
      assertThrows(IllegalStateException.class, () -> fixture.writer().write(
          fixture.repository(), source, new ByteArrayInputStream(archive), " ", false));
    }
  }

  private static Fixture fixture() {
    CondaService service = mock(CondaService.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    AssetDao assets = mock(AssetDao.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "conda-hosted", RepositoryFormat.CONDA, RepositoryType.HOSTED, "conda-hosted",
        true, 1L, "ALLOW_ONCE", null, null, true, null, null, null, null, null, List.of());
    RepositoryRecord repository = new RepositoryRecord(
        runtime.id(), runtime.name(), RepositoryFormat.CONDA, RepositoryType.HOSTED,
        "conda-hosted", true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
    when(runtimes.resolveById(runtime.id())).thenReturn(Optional.of(runtime));
    return new Fixture(
        new CondaRepositoryDataMigrationWriter(
            new CondaArchiveInspector(new ObjectMapper()), service, runtimes, assets),
        service, runtimes, assets, runtime, repository);
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, Long size, String sha256, Instant publishedAt) {
    return source(path, size, sha256, publishedAt, "demo", "1.0");
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, Long size, String sha256, Instant publishedAt, String name, String version) {
    Map<String, Object> metadata = sha256 == null
        ? Map.of()
        : Map.of("checksum", Map.of("sha256", sha256));
    return source(path, size, sha256, publishedAt, name, version, metadata);
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path,
      Long size,
      String sha256,
      Instant publishedAt,
      String name,
      String version,
      Map<String, Object> metadata) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "asset-1", "component-1", path, new byte[32], RepositoryFormat.CONDA,
        "main", name, version, "package", "application/x-tar", size, "blob-ref",
        publishedAt, null, publishedAt, publishedAt, "nexus-user", "10.0.0.8",
        "PENDING", 0, null, null, null, null, null, null,
        metadata, Instant.now());
  }

  private static CondaRegistryDao.PackageRecord migratedRecord(
      long repositoryId, String sha256, Long assetId) {
    return new CondaRegistryDao.PackageRecord(
        1L, repositoryId, "main", "linux-64", "demo-1.0-0.tar.bz2",
        "demo", "1.0", "0", 0L, "tar.bz2", Map.of(), sha256,
        "a".repeat(32), sha256, 1L, assetId, 201L,
        CondaRegistryDao.SOURCE_HOSTED, 1L, Instant.EPOCH, Instant.EPOCH);
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record Fixture(
      CondaRepositoryDataMigrationWriter writer,
      CondaService service,
      RepositoryRuntimeRegistry runtimes,
      AssetDao assets,
      RepositoryRuntime runtime,
      RepositoryRecord repository) {
  }
}
