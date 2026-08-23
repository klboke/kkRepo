package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RRepositoryDataMigrationWriterTest {
  private static final String PATH = "src/contrib/demo_1.0.0.tar.gz";
  private final RService service = mock(RService.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final RepositoryRuntime runtime = runtime();
  private RRepositoryDataMigrationWriter writer;

  @BeforeEach
  void setUp() {
    writer = new RRepositoryDataMigrationWriter(
        new RSourcePackageInspector(), service, runtimes, registry, assets);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime));
  }

  @Test
  void restoresVerifiedHostedPackageThroughNormalImporter() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.0.0");
    String sha256 = sha256(bytes);
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), any(), any()))
        .thenReturn(new RService.PublishedPackage(
            PATH, "demo", "1.0.0", "a".repeat(32), sha256, bytes.length));
    when(registry.findPackageByPath(1L, PATH)).thenReturn(Optional.of(
        record(sha256, bytes.length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, sha256)));

    RRepositoryDataMigrationWriter.MigratedAsset migrated = writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "1.0.0", (long) bytes.length,
            Map.of("checksums", Map.of("sha-256", sha256))),
        new ByteArrayInputStream(bytes),
        true);

    assertEquals(20L, migrated.componentId());
    assertEquals(10L, migrated.assetId());
    assertEquals(30L, migrated.assetBlobId());
    assertEquals("object", migrated.assetBlobObjectKey());
  }

  @Test
  void acceptsTheSha1ChecksumStoredByNexusR394() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.0.0");
    String sha256 = digest("SHA-256", bytes);
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), any(), any()))
        .thenReturn(new RService.PublishedPackage(
            PATH, "demo", "1.0.0", "a".repeat(32), sha256, bytes.length));
    when(registry.findPackageByPath(1L, PATH)).thenReturn(Optional.of(
        record(sha256, bytes.length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, sha256)));

    RRepositoryDataMigrationWriter.MigratedAsset migrated = writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "1.0.0", (long) bytes.length,
            Map.of("checksums", Map.of("sha1", digest("SHA-1", bytes)))),
        new ByteArrayInputStream(bytes),
        true);

    assertEquals(30L, migrated.assetBlobId());
  }

  @Test
  void recognizesOnlyCanonicalHostedSourcePaths() {
    assertTrue(RRepositoryDataMigrationWriter.isMigratableRPath(PATH));
    assertTrue(RRepositoryDataMigrationWriter.isMigratableRPath("/" + PATH + "/"));
    assertFalse(RRepositoryDataMigrationWriter.isMigratableRPath("src/contrib/PACKAGES.gz"));
    assertFalse(RRepositoryDataMigrationWriter.isMigratableRPath(
        "src/contrib/Archive/demo/demo_1.0.0.tar.gz"));
    assertFalse(RRepositoryDataMigrationWriter.isMigratableRPath(
        "bin/windows/contrib/4.6/demo_1.0.0.zip"));
    assertFalse(RRepositoryDataMigrationWriter.isMigratableRPath(null));
  }

  @Test
  void failsClosedForWrongTargetMissingChecksumAndIdentityDrift() throws Exception {
    InputStream body = mock(InputStream.class);
    doThrow(new IOException("close failed")).when(body).close();
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED),
        source(PATH, null, null, null, Map.of()), body, false));
    verify(body).close();

    byte[] bytes = RTestPackage.source("demo", "1.0.0");
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "1.0.0", null, Map.of()),
        new ByteArrayInputStream(bytes), false));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "other", "1.0.0", null, Map.of("sha256", sha256(bytes))),
        new ByteArrayInputStream(bytes), false));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "2.0.0", null, Map.of("sha256", sha256(bytes))),
        new ByteArrayInputStream(bytes), false));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "1.0.0", (long) bytes.length + 1,
            Map.of("sha256", sha256(bytes))),
        new ByteArrayInputStream(bytes), true));
  }

  @Test
  void rejectsUnavailableRuntimeMissingBindingsAndInvalidBlobProjection() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.0.0");
    String sha256 = sha256(bytes);
    RepositoryDataMigrationAssetRecord source = source(
        PATH, "demo", "1.0.0", (long) bytes.length, Map.of("sha256", sha256));

    when(runtimes.resolveById(1L)).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(bytes), true));

    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime));
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), any(), any()))
        .thenReturn(new RService.PublishedPackage(
            PATH, "demo", "1.0.0", "a".repeat(32), sha256, bytes.length));
    when(registry.findPackageByPath(1L, PATH)).thenReturn(Optional.of(
        record(sha256, bytes.length, null, null)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(bytes), true));

    when(registry.findPackageByPath(1L, PATH)).thenReturn(Optional.of(
        record(sha256, bytes.length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, null)));
    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(bytes), true));
  }

  @Test
  void findsIterableChecksumsAndSurfacesBodyCloseFailures() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.0.0");
    String sha256 = sha256(bytes);
    when(service.restoreHostedPackageForMigration(
        eq(runtime), any(), any(), any(), any()))
        .thenReturn(new RService.PublishedPackage(
            PATH, "demo", "1.0.0", "a".repeat(32), sha256, bytes.length));
    when(registry.findPackageByPath(1L, PATH)).thenReturn(Optional.of(
        record(sha256, bytes.length, 10L, 20L)));
    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L)));
    when(assets.findBlobById(30L)).thenReturn(Optional.of(blob(30L, sha256)));
    InputStream body = new ByteArrayInputStream(bytes) {
      @Override
      public void close() throws IOException {
        super.close();
        throw new IOException("close failed");
      }
    };

    IllegalStateException failure = assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "1.0.0", (long) bytes.length,
            Map.of("nested", List.of(Map.of("SHA-256", sha256)))),
        body,
        true));

    assertTrue(failure.getMessage().contains("Failed reading R migration package"));
  }

  @Test
  void rejectsSourceChecksumMismatch() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.0.0");

    assertThrows(IllegalStateException.class, () -> writer.write(
        repository(RepositoryFormat.R, RepositoryType.HOSTED),
        source(PATH, "demo", "1.0.0", (long) bytes.length,
            Map.of("sha256", "0".repeat(64))),
        new ByteArrayInputStream(bytes),
        true));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "r", RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted", true,
        1L, "ALLOW_ONCE", null, null, true, null, null, null, null, null, List.of());
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        1L, "r", format, type, "r-hosted", true, 1L, null, null,
        null, null, "ALLOW_ONCE", true, Map.of());
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, String name, String version, Long size, Map<String, Object> metadata) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 1L, "source", "component", path, new byte[32], RepositoryFormat.R,
        null, name, version, "r-source-package", "application/x-gzip", size,
        "blob", null, null, null, null, "nexus", "127.0.0.1", "PENDING", 0,
        null, null, null, null, null, null, metadata, Instant.EPOCH);
  }

  private static RRegistryDao.PackageRecord record(
      String sha256, long size, Long assetId, Long componentId) {
    return new RRegistryDao.PackageRecord(
        1L, 1L, "src/contrib", "source", "source", "demo", "1.0.0",
        new byte[] {1}, "source", "demo_1.0.0.tar.gz", PATH, Map.of(),
        "a".repeat(32), sha256, sha256, size, assetId, componentId,
        RRegistryDao.SOURCE_HOSTED, 1L, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetRecord asset(long id, Long blobId) {
    return new AssetRecord(
        id, 1L, 20L, blobId, RepositoryFormat.R, PATH, new byte[32],
        "demo_1.0.0.tar.gz", "r", "application/x-gzip", 1L, null, Instant.EPOCH,
        Map.of());
  }

  private static AssetBlobRecord blob(long id, String sha256) {
    return new AssetBlobRecord(
        id, 1L, "blob", new byte[32], "object", new byte[32], "b".repeat(40), sha256,
        "a".repeat(32), 1L, "application/x-gzip", "migration", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static String sha256(byte[] bytes) throws Exception {
    return digest("SHA-256", bytes);
  }

  private static String digest(String algorithm, byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
  }
}
