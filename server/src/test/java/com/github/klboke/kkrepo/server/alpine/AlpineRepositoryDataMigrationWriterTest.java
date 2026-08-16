package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlpineRepositoryDataMigrationWriterTest {
  private final AlpineService service = mock(AlpineService.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final RepositoryRuntime runtime = runtime();
  private AlpineRepositoryDataMigrationWriter writer;

  @BeforeEach
  void setUp() {
    writer = new AlpineRepositoryDataMigrationWriter(
        new AlpinePackageInspector(), service, runtimes, registry, assets);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime));
  }

  @Test
  void restoresVerifiedPackageAndReturnsDurableBindings() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String path = "v3.23/main/x86_64/demo-1.0-r0.apk";
    String sha256 = AlpineTestPackage.sha256(fixture.bytes());
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), eq("nexus-migration"), eq("127.0.0.1")))
        .thenReturn(new AlpineService.PublishedPackage(
            path, "demo", "1.0-r0", "x86_64", "Q1" + "A".repeat(27) + "=",
            sha256, fixture.bytes().length));
    when(registry.findPackageByPath(1L, path)).thenReturn(Optional.of(
        record(path, sha256, fixture.bytes().length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, sha256)));

    AlpineRepositoryDataMigrationWriter.MigratedAsset migrated = writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source("/" + path + "/", "demo", "1.0-r0", (long) fixture.bytes().length,
            Map.of("checksums", Map.of("sha-256", sha256))),
        new ByteArrayInputStream(fixture.bytes()),
        true);

    assertEquals(20L, migrated.componentId());
    assertEquals(10L, migrated.assetId());
    assertEquals(30L, migrated.assetBlobId());
    assertEquals("object", migrated.assetBlobObjectKey());
  }

  @Test
  void rejectsWrongTargetGeneratedMetadataAndBadSourceEvidence() throws Exception {
    InputStream body = mock(InputStream.class);
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED),
        source("v3.23/main/x86_64/demo-1.0-r0.apk", null, null, null, Map.of()),
        body,
        true));
    verify(body).close();
    assertFalse(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath(
        "v3.23/main/x86_64/APKINDEX.tar.gz"));
    assertFalse(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath(
        "v3.23/main/x86_64/Packages.adb"));
    assertFalse(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath("../demo.apk"));

    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String path = "v3.23/main/x86_64/demo-1.0-r0.apk";
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, "demo", "1.0-r0", null, Map.of()),
        new ByteArrayInputStream(fixture.bytes()),
        false));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, "other", "1.0-r0", null,
            Map.of("sha256", AlpineTestPackage.sha256(fixture.bytes()))),
        new ByteArrayInputStream(fixture.bytes()),
        false));
  }

  @Test
  void recognizesOnlyCanonicalApkV2PackagePaths() {
    assertTrue(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath(
        "v3.23/main/x86_64/demo-1.0-r0.apk"));
    assertTrue(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath(
        "/edge/community/aarch64/lib-demo-2.0_alpha1-r2.apk/"));
    assertFalse(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath(null));
    assertFalse(AlpineRepositoryDataMigrationWriter.isMigratableAlpinePath(
        "v3.23/main/x86_64/demo.apk/extra"));
  }

  @Test
  void rejectsUnavailableTargetsAndEverySourceIdentityMismatch() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String path = "v3.23/main/x86_64/demo-1.0-r0.apk";
    String sha256 = AlpineTestPackage.sha256(fixture.bytes());

    InputStream wrongTypeBody = mock(InputStream.class);
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.PROXY),
        source(path, null, null, null, Map.of()), wrongTypeBody, false));
    verify(wrongTypeBody).close();

    when(runtimes.resolveById(1L)).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, null, null, null, Map.of()),
        new ByteArrayInputStream(fixture.bytes()), false));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime));

    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, "demo", "1.0-r0", (long) fixture.bytes().length + 1,
            Map.of("sha256", sha256)), new ByteArrayInputStream(fixture.bytes()), true));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, "demo", "1.0-r0", null, Map.of("sha256", "0".repeat(64))),
        new ByteArrayInputStream(fixture.bytes()), false));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, "demo", "2.0-r0", null, Map.of("sha256", sha256)),
        new ByteArrayInputStream(fixture.bytes()), false));
  }

  @Test
  void rejectsMissingProjectionAssetBlobAndChecksumBindings() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String path = "v3.23/main/x86_64/demo-1.0-r0.apk";
    String sha256 = AlpineTestPackage.sha256(fixture.bytes());
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), any(), any()))
        .thenReturn(new AlpineService.PublishedPackage(
            path, "demo", "1.0-r0", "x86_64", "Q1" + "A".repeat(27) + "=",
            sha256, fixture.bytes().length));
    RepositoryDataMigrationAssetRecord source = source(
        path, "demo", "1.0-r0", null, Map.of("checksum.sha256", sha256));

    when(registry.findPackageByPath(1L, path)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(fixture.bytes()), false));

    when(registry.findPackageByPath(1L, path)).thenReturn(Optional.of(
        record(path, sha256, fixture.bytes().length, null, 20L)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(fixture.bytes()), false));

    when(registry.findPackageByPath(1L, path)).thenReturn(Optional.of(
        record(path, sha256, fixture.bytes().length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(fixture.bytes()), false));

    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, null)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(fixture.bytes()), false));

    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, "0".repeat(64))));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(fixture.bytes()), false));
  }

  @Test
  void wrapsMigrationBodyCloseFailureAfterSuccessfulRestore() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String path = "v3.23/main/x86_64/demo-1.0-r0.apk";
    String sha256 = AlpineTestPackage.sha256(fixture.bytes());
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), any(), any()))
        .thenReturn(new AlpineService.PublishedPackage(
            path, "demo", "1.0-r0", "x86_64", "Q1" + "A".repeat(27) + "=",
            sha256, fixture.bytes().length));
    when(registry.findPackageByPath(1L, path)).thenReturn(Optional.of(
        record(path, sha256, fixture.bytes().length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, sha256)));
    InputStream body = new ByteArrayInputStream(fixture.bytes()) {
      @Override
      public void close() throws IOException {
        throw new IOException("fixture close failure");
      }
    };

    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.ALPINE, RepositoryType.HOSTED),
        source(path, "demo", "1.0-r0", null, Map.of("sha256", sha256)), body, false));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1, "alpine", RepositoryFormat.ALPINE, RepositoryType.HOSTED, "alpine-hosted",
        true, 1L, "ALLOW_ONCE", null, null, true, null, null, null, null, null, List.of());
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L, "alpine", format, type, "alpine-hosted", true, 1L, null, null,
        null, null, "ALLOW_ONCE", true, Map.of());
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, String name, String version, Long size, Map<String, Object> metadata) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 1L, "source", "component", path, new byte[32], RepositoryFormat.ALPINE,
        null, name, version, "package", "application/vnd.alpine.apk", size,
        "blob", null, null, null, null, " ", "127.0.0.1", "PENDING", 0,
        null, null, null, null, null, null, metadata, Instant.EPOCH);
  }

  private static AlpineRegistryDao.PackageRecord record(
      String path, String sha256, long size, Long assetId, Long componentId) {
    return new AlpineRegistryDao.PackageRecord(
        1L, 1L, "v3.23/main/x86_64", "main", "x86_64", "demo", "1.0-r0",
        "x86_64", "demo-1.0-r0.apk", path, Map.of(), "Q1" + "A".repeat(27) + "=",
        "a".repeat(64), sha256, size, assetId, componentId,
        AlpineRegistryDao.SOURCE_HOSTED, 1, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetRecord asset(long id, Long blobId) {
    return new AssetRecord(
        id, 1, 20L, blobId, RepositoryFormat.ALPINE,
        "v3.23/main/x86_64/demo-1.0-r0.apk", new byte[32], "demo-1.0-r0.apk",
        "package", "application/vnd.alpine.apk", 1L, null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(long id, String sha256) {
    return new AssetBlobRecord(
        id, 1, "blob", new byte[32], "object", new byte[32], "b".repeat(40), sha256,
        "a".repeat(32), 1, "application/vnd.alpine.apk", "migration", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }
}
