package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AptRepositoryDataMigrationWriterTest {
  private final AptService service = mock(AptService.class);
  private final AptRepositorySettings settings = mock(AptRepositorySettings.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final AptRegistryDao registry = mock(AptRegistryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final RepositoryRuntime runtime = runtime();
  private AptRepositoryDataMigrationWriter writer;

  @BeforeEach
  void setUp() {
    writer = new AptRepositoryDataMigrationWriter(
        new AptDebPackageInspector(), service, settings, runtimes, registry, assets);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime));
    when(settings.get(runtime)).thenReturn(new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, null,
        "kkRepo", "kkRepo"));
  }

  @Test
  void restoresVerifiedPackageAndReturnsDurableBindings() throws Exception {
    byte[] archive = archive();
    String sha256 = sha256(archive);
    String path = "pool/d/demo/demo_1.0-1_amd64.deb";
    RepositoryDataMigrationAssetRecord source = source(
        "/" + path + "/", "demo", "1.0-1", (long) archive.length,
        Map.of("nested", List.of(Map.of("sha-256", sha256))), " ");
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), eq("stable"), eq("main"), eq("nexus-migration"),
        eq("127.0.0.1"), eq(path)))
        .thenReturn(new AptService.PublishedPackage(path, "demo", "1.0-1", "amd64",
            sha256, archive.length));
    AptRegistryDao.PackageRecord record = record(path, sha256, 10L, 20L);
    when(registry.findPackageByPath(1L, path)).thenReturn(Optional.of(record));
    AssetRecord asset = asset(10L, 30L);
    AssetBlobRecord blob = blob(30L, sha256);
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob));

    AptRepositoryDataMigrationWriter.MigratedAsset migrated = writer.write(
        repository(RepositoryFormat.APT, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(archive), true);
    assertEquals(20L, migrated.componentId());
    assertEquals(10L, migrated.assetId());
    assertEquals(30L, migrated.assetBlobId());
    assertEquals("object", migrated.assetBlobObjectKey());
  }

  @Test
  void rejectsUnsupportedTargetsPathsAndUnavailableRuntimeAndClosesBody() throws Exception {
    InputStream body = mock(InputStream.class);
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED),
        source("pool/d/demo.deb", null, null, null, Map.of(), null), body, true));
    verify(body).close();

    byte[] archive = archive();
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.APT, RepositoryType.PROXY),
        source("pool/d/demo.deb", null, null, null, Map.of(), null),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.APT, RepositoryType.HOSTED),
        source("dists/stable/Release", null, null, null, Map.of(), null),
        new ByteArrayInputStream(archive), true));

    when(runtimes.resolveById(1L)).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.APT, RepositoryType.HOSTED),
        source("pool/d/demo/demo_1.0-1_amd64.deb", null, null, null, Map.of(), null),
        new ByteArrayInputStream(archive), true));
  }

  @Test
  void validatesSourceSizeChecksumNameAndVersion() throws Exception {
    byte[] archive = archive();
    String path = "pool/d/demo/demo_1.0-1_amd64.deb";
    String sha256 = sha256(archive);
    RepositoryRecord repository = repository(RepositoryFormat.APT, RepositoryType.HOSTED);

    assertThrows(IllegalStateException.class, () -> writer.write(repository,
        source(path, "demo", "1.0-1", 1L, Map.of("sha256", sha256), "alice"),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> writer.write(repository,
        source(path, "demo", "1.0-1", null, Map.of(), "alice"),
        new ByteArrayInputStream(archive), false));
    assertThrows(IllegalStateException.class, () -> writer.write(repository,
        source(path, "demo", "1.0-1", null, Map.of("sha256", "invalid"), "alice"),
        new ByteArrayInputStream(archive), false));
    assertThrows(IllegalStateException.class, () -> writer.write(repository,
        source(path, "demo", "1.0-1", null, Map.of("sha256", "f".repeat(64)), "alice"),
        new ByteArrayInputStream(archive), false));
    assertThrows(IllegalStateException.class, () -> writer.write(repository,
        source(path, "other", "1.0-1", null, Map.of("sha256", sha256), "alice"),
        new ByteArrayInputStream(archive), false));
    assertThrows(IllegalStateException.class, () -> writer.write(repository,
        source(path, "demo", "2.0", null, Map.of("sha256", sha256), "alice"),
        new ByteArrayInputStream(archive), false));
  }

  @Test
  void rejectsMissingOrInconsistentRestoredBindings() throws Exception {
    byte[] archive = archive();
    String sha256 = sha256(archive);
    String path = "pool/d/demo/demo_1.0-1_amd64.deb";
    RepositoryDataMigrationAssetRecord source = source(
        path, "demo", "1.0-1", null, Map.of("SHA256", sha256), "alice");
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), eq("stable"), eq("main"), eq("alice"), anyString(), eq(path)))
        .thenReturn(new AptService.PublishedPackage(path, "demo", "1.0-1", "amd64",
            sha256, archive.length));
    RepositoryRecord repository = repository(RepositoryFormat.APT, RepositoryType.HOSTED);

    assertThrows(IllegalStateException.class, () -> writer.write(
        repository, source, new ByteArrayInputStream(archive), false));
    when(registry.findPackageByPath(1L, path))
        .thenReturn(Optional.of(record(path, sha256, null, 20L)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository, source, new ByteArrayInputStream(archive), false));
    when(registry.findPackageByPath(1L, path))
        .thenReturn(Optional.of(record(path, sha256, 10L, 20L)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository, source, new ByteArrayInputStream(archive), false));

    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, null)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository, source, new ByteArrayInputStream(archive), false));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, "f".repeat(64))));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository, source, new ByteArrayInputStream(archive), false));
  }

  @Test
  void onlyAcceptsCanonicalPoolDebianPackagePaths() {
    assertTrue(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "pool/main/d/demo/demo_1%3a2.0-1_amd64.deb"));
    assertTrue(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "/pool/main/libd/libdemo/libdemo_2.0-1_all.deb"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "dists/stable/main/binary-amd64/Packages.gz"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath(
        "pool/main/d/demo/demo_2.0-1_amd64.deb/extra"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath("../demo.deb"));
    assertFalse(AptRepositoryDataMigrationWriter.isMigratableAptPath(null));
  }

  private static byte[] archive() throws Exception {
    return AptTestPackage.deb("gz", AptTestPackage.control("demo", "1.0-1", "amd64"));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1, "apt", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted", true, 1L,
        "ALLOW", null, null, true, null, null, null, null, null, List.of());
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L, "apt", format, type, "apt-hosted", true, 1L, null, null, null, null,
        "ALLOW", true, Map.of());
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, String name, String version, Long size, Map<String, Object> metadata,
      String createdBy) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 1L, "source", "component", path, new byte[32], RepositoryFormat.APT,
        null, name, version, "package", "application/vnd.debian.binary-package", size,
        "blob", null, null, null, null, createdBy, "127.0.0.1", "PENDING", 0,
        null, null, null, null, null, null, metadata, Instant.EPOCH);
  }

  private static AptRegistryDao.PackageRecord record(
      String path, String sha256, Long assetId, Long componentId) {
    return new AptRegistryDao.PackageRecord(
        1L, 1L, "stable", "main", "amd64", "demo", "1.0-1", "demo",
        "demo_1.0-1_amd64.deb", path, Map.of(), "a".repeat(32), "b".repeat(40),
        sha256, 1, assetId, componentId, AptRegistryDao.SOURCE_HOSTED, 1,
        Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetRecord asset(long id, Long blobId) {
    return new AssetRecord(
        id, 1, 20L, blobId, RepositoryFormat.APT, "pool/d/demo.deb", new byte[32],
        "demo.deb", "package", "application/vnd.debian.binary-package", 1L,
        null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(long id, String sha256) {
    return new AssetBlobRecord(
        id, 1, "blob", new byte[32], "object", new byte[32], "b".repeat(40), sha256,
        "a".repeat(32), 1, "application/vnd.debian.binary-package", "migration", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }
}
