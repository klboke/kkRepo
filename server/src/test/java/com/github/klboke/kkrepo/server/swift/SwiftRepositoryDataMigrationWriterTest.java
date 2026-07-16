package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SwiftRepositoryDataMigrationWriterTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final Instant PUBLISHED_AT = Instant.parse("2025-02-03T04:05:06Z");

  @Test
  void restoresHostedArchiveAndPreservesMetadataSignaturesAndTimestamp() throws Exception {
    Fixture fixture = fixture(1024 * 1024);
    byte[] archive = "PK-swift-package-archive".getBytes(StandardCharsets.UTF_8);
    byte[] sourceSignature = {1, 2, 3};
    byte[] metadataSignature = {4, 5, 6};
    Map<String, Object> originalMetadata = Map.of(
        "repositoryURLs", List.of("https://github.com/acme/demo"),
        "readmeURL", "https://github.com/acme/demo/blob/main/README.md",
        "originalPublicationTime", PUBLISHED_AT.toString());
    Map<String, Object> metadata = Map.of(
        "attributes", Map.of(
            "checksums", Map.of("sha256", sha256(archive)),
            "originalMetadata", originalMetadata,
            "sourceArchiveSignature", Base64.getEncoder().encodeToString(sourceSignature),
            "metadataSignature", Base64.getEncoder().encodeToString(metadataSignature),
            "signatureFormat", "cms-1.0.0"));
    RepositoryDataMigrationAssetRecord source = source(
        "Acme/Demo/1.2.3.zip", (long) archive.length, metadata);
    SwiftRegistryDao.Release restored = release(sha256(archive));
    AtomicReference<byte[]> replayedArchive = new AtomicReference<>();
    AtomicReference<RestoreCall> restoreCall = new AtomicReference<>();
    when(fixture.service().restoreHostedReleaseForMigration(
        any(), anyString(), anyString(), anyString(), any(InputStream.class),
        anyString(), any(), any(), anyString(), any(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          InputStream replay = invocation.getArgument(4);
          replayedArchive.set(replay.readAllBytes());
          restoreCall.set(new RestoreCall(
              invocation.getArgument(1),
              invocation.getArgument(2),
              invocation.getArgument(3),
              invocation.getArgument(5),
              invocation.getArgument(6),
              invocation.getArgument(7),
              invocation.getArgument(8),
              invocation.getArgument(9),
              invocation.getArgument(10),
              invocation.getArgument(11)));
          return restored;
        });
    stubStoredArchive(fixture, restored, archive.length);

    SwiftRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(archive), true);

    assertEquals(60L, migrated.componentId());
    assertEquals(70L, migrated.assetId());
    assertEquals(80L, migrated.assetBlobId());
    assertEquals("swift/archive/object", migrated.assetBlobObjectKey());
    assertArrayEquals(archive, replayedArchive.get());
    RestoreCall call = restoreCall.get();
    assertEquals("Acme", call.scope());
    assertEquals("Demo", call.name());
    assertEquals("1.2.3", call.version());
    assertEquals(originalMetadata, MAPPER.readValue(
        call.metadataJson(), new TypeReference<Map<String, Object>>() {}));
    assertArrayEquals(sourceSignature, call.sourceSignature());
    assertArrayEquals(metadataSignature, call.metadataSignature());
    assertEquals("cms-1.0.0", call.signatureFormat());
    assertEquals(PUBLISHED_AT, call.publishedAt());
    assertEquals("nexus-user", call.actor());
    assertEquals("10.0.0.1", call.ip());
  }

  @Test
  void unwrapsProductionWorkerMetadataContainingOnlyDescriptionAndAuthor() throws Exception {
    Fixture fixture = fixture(1024 * 1024);
    byte[] archive = "PK-worker-wrapped-metadata".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> author = Map.of(
        "name", "Alice",
        "email", "alice@example.test");
    Map<String, Object> workerMetadata = Map.of(
        "format", "swift",
        "attributes", Map.of(
            "checksums", Map.of("sha256", sha256(archive)),
            "sourceAssetAttributes", Map.of(
                "description", "Migrated from Nexus")),
        "componentAttributes", Map.of(
            "swift", Map.of("author", author)));
    SwiftRegistryDao.Release restored = release(sha256(archive));
    AtomicReference<String> restoredMetadata = new AtomicReference<>();
    when(fixture.service().restoreHostedReleaseForMigration(
        any(), anyString(), anyString(), anyString(), any(InputStream.class),
        anyString(), any(), any(), any(), any(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          restoredMetadata.set(invocation.getArgument(5));
          return restored;
        });
    stubStoredArchive(fixture, restored, archive.length);

    fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length, workerMetadata),
        new ByteArrayInputStream(archive),
        true);

    assertEquals(
        Map.of("description", "Migrated from Nexus", "author", author),
        MAPPER.readValue(
            restoredMetadata.get(), new TypeReference<Map<String, Object>>() {}));
  }

  @Test
  void doesNotFabricateOptionalFieldsMissingFromNativeNexusMetadata() throws Exception {
    Fixture fixture = fixture(1024 * 1024);
    byte[] archive = "PK-native-nexus-metadata".getBytes(StandardCharsets.UTF_8);
    Map<String, Object> workerMetadata = Map.of(
        "format", "swift",
        "attributes", Map.of(
            "checksums", Map.of("sha256", sha256(archive)),
            "sourceAssetAttributes", Map.of(
                "swift", Map.of(
                    "name", "Demo",
                    "scope", "Acme",
                    "version", "1.2.3",
                    "asset_kind", "PACKAGE_ARCHIVE"))),
        "componentAttributes", Map.of("swift", Map.of("scope", "Acme")));
    SwiftRegistryDao.Release restored = release(sha256(archive));
    AtomicReference<RestoreCall> restoreCall = new AtomicReference<>();
    when(fixture.service().restoreHostedReleaseForMigration(
        any(), anyString(), anyString(), anyString(), any(InputStream.class),
        anyString(), any(), any(), any(), any(), anyString(), anyString()))
        .thenAnswer(invocation -> {
          restoreCall.set(new RestoreCall(
              invocation.getArgument(1),
              invocation.getArgument(2),
              invocation.getArgument(3),
              invocation.getArgument(5),
              invocation.getArgument(6),
              invocation.getArgument(7),
              invocation.getArgument(8),
              invocation.getArgument(9),
              invocation.getArgument(10),
              invocation.getArgument(11)));
          return restored;
        });
    stubStoredArchive(fixture, restored, archive.length);

    fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length, workerMetadata),
        new ByteArrayInputStream(archive),
        true);

    RestoreCall call = restoreCall.get();
    assertEquals("{}", call.metadataJson());
    assertNull(call.sourceSignature());
    assertNull(call.metadataSignature());
    assertNull(call.signatureFormat());
    assertEquals(Instant.parse("2025-01-01T00:00:00Z"), call.publishedAt());
  }

  @Test
  void supportsOnlyHostedSwiftRepositoriesAndSourceArchivePaths() throws Exception {
    Fixture fixture = fixture(1024);
    byte[] archive = "PK-archive".getBytes(StandardCharsets.UTF_8);
    RepositoryDataMigrationAssetRecord source = source(
        "Acme/Demo/1.2.3.zip", (long) archive.length,
        Map.of("sha256", sha256(archive)));

    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        repository(RepositoryFormat.SWIFT, RepositoryType.PROXY), source,
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        repository(RepositoryFormat.RAW, RepositoryType.HOSTED), source,
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        fixture.repository(), source("Acme/Demo/1.2.3", (long) archive.length,
            Map.of("sha256", sha256(archive))),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalArgumentException.class, () -> fixture.writer().write(
        fixture.repository(), source(
            "Acme/Demo/1.2.3.zip", "Other", "Demo", "1.2.3",
            (long) archive.length, Map.of("sha256", sha256(archive))),
        new ByteArrayInputStream(archive), true));

    verify(fixture.service(), never()).restoreHostedReleaseForMigration(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void validatesArchiveSizeShaAndConfiguredLimitBeforeRestore() throws Exception {
    byte[] archive = "PK-archive-validation".getBytes(StandardCharsets.UTF_8);
    Fixture fixture = fixture(archive.length);

    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length + 1,
            Map.of("sha256", sha256(archive))),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length,
            Map.of("sha256", differentSha(archive))),
        new ByteArrayInputStream(archive), true));

    Fixture limited = fixture(archive.length - 1L);
    assertThrows(IllegalStateException.class, () -> limited.writer().write(
        limited.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length,
            Map.of("sha256", sha256(archive))),
        new ByteArrayInputStream(archive), true));

    verify(fixture.service(), never()).restoreHostedReleaseForMigration(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(limited.service(), never()).restoreHostedReleaseForMigration(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectsMissingOrInvalidSourceShaBeforePublishing() throws Exception {
    byte[] archive = "PK-untrusted-migration-source".getBytes(StandardCharsets.UTF_8);
    Fixture fixture = fixture(archive.length);

    IllegalStateException missing = assertThrows(IllegalStateException.class, () ->
        fixture.writer().write(
            fixture.repository(),
            source("Acme/Demo/1.2.3.zip", (long) archive.length, Map.of()),
            new ByteArrayInputStream(archive),
            true));
    IllegalStateException malformed = assertThrows(IllegalStateException.class, () ->
        fixture.writer().write(
            fixture.repository(),
            source("Acme/Demo/1.2.3.zip", (long) archive.length,
                Map.of("checksums", Map.of("sha256", "not-a-sha256"))),
            new ByteArrayInputStream(archive),
            true));

    assertTrue(missing.getMessage().contains("requires a valid source SHA-256"));
    assertTrue(malformed.getMessage().contains("requires a valid source SHA-256"));
    verify(fixture.service(), never()).restoreHostedReleaseForMigration(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void reusesExistingRestoreIdempotentlyAndValidatesStoredArchive() throws Exception {
    Fixture fixture = fixture(1024);
    byte[] archive = "PK-existing-archive".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(archive);
    SwiftRegistryDao.Release existing = release(sha256);
    when(fixture.registry().findRelease(7L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(existing));
    stubStoredArchive(fixture, existing, archive.length);
    RepositoryDataMigrationAssetRecord source = source(
        "Acme/Demo/1.2.3.zip", (long) archive.length, Map.of("sha256", sha256));
    CloseTrackingInputStream body = new CloseTrackingInputStream(archive);

    SwiftRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, body, true);

    assertTrue(body.closed());
    assertEquals(60L, migrated.componentId());
    assertEquals(70L, migrated.assetId());
    assertEquals(80L, migrated.assetBlobId());
    verify(fixture.service(), never()).restoreHostedReleaseForMigration(
        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length + 1, Map.of("sha256", sha256)),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length,
            Map.of("sha256", differentSha(archive))),
        new ByteArrayInputStream(archive), true));
    assertThrows(IllegalStateException.class, () -> fixture.writer().write(
        fixture.repository(),
        source("Acme/Demo/1.2.3.zip", (long) archive.length, Map.of()),
        new ByteArrayInputStream(archive), true));
  }

  private static Fixture fixture(long maxArchiveBytes) {
    SwiftService service = mock(SwiftService.class);
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    AssetDao assets = mock(AssetDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, "swift-hosted", RepositoryFormat.SWIFT, RepositoryType.HOSTED,
        "swift-hosted", true, 1L, "ALLOW_ONCE", null, null, true,
        null, null, null, true, null, List.of());
    RepositoryRecord repository = repository(RepositoryFormat.SWIFT, RepositoryType.HOSTED);
    when(runtimes.resolveById(repository.id())).thenReturn(Optional.of(runtime));
    return new Fixture(
        new SwiftRepositoryDataMigrationWriter(
            service, registry, assets, runtimes, MAPPER, maxArchiveBytes),
        service, registry, assets, runtime, repository);
  }

  private static RepositoryRecord repository(RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        7L, "swift-" + type.name().toLowerCase(), format, type, "swift-hosted",
        true, 1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, Long size, Map<String, Object> metadata) {
    return source(path, "Acme", "Demo", "1.2.3", size, metadata);
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path,
      String scope,
      String name,
      String version,
      Long size,
      Map<String, Object> metadata) {
    Instant fallback = Instant.parse("2025-01-01T00:00:00Z");
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "asset-1", "component-1", path, new byte[32], RepositoryFormat.SWIFT,
        scope, name, version, "swift-source-archive", "application/zip", size,
        "source-blob-ref", fallback, null, fallback, fallback,
        "nexus-user", "10.0.0.1", "PENDING", 0, null, null,
        null, null, null, null, metadata, fallback);
  }

  private static SwiftRegistryDao.Release release(String archiveSha256) {
    return new SwiftRegistryDao.Release(
        50L, 7L, 60L, "acme", "Acme", "demo", "Demo", "1.2.3",
        PUBLISHED_AT, "{}", archiveSha256, 70L, "cms-1.0.0", 71L, 72L,
        "NEXUS_MIGRATION", 1L, SwiftRegistryDao.RELEASE_READY, PUBLISHED_AT, PUBLISHED_AT);
  }

  private static void stubStoredArchive(
      Fixture fixture, SwiftRegistryDao.Release release, long archiveSize) {
    AssetRecord asset = new AssetRecord(
        70L, 7L, 60L, 80L, RepositoryFormat.SWIFT,
        ".swift/archives/acme/demo/1.2.3.zip", new byte[32], "1.2.3.zip",
        "swift-source-archive", "application/zip", archiveSize,
        null, PUBLISHED_AT, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        80L, 1L, "blob-ref", new byte[32], "swift/archive/object", new byte[32],
        null, release.archiveSha256(), null, archiveSize, "application/zip",
        "nexus-user", "10.0.0.1", PUBLISHED_AT, PUBLISHED_AT, Map.of());
    when(fixture.assets().findAssetById(release.archiveAssetId())).thenReturn(Optional.of(asset));
    when(fixture.assets().findBlobById(asset.assetBlobId())).thenReturn(Optional.of(blob));
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String differentSha(byte[] value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
    digest[0] ^= (byte) 0xff;
    return HexFormat.of().formatHex(digest);
  }

  private record Fixture(
      SwiftRepositoryDataMigrationWriter writer,
      SwiftService service,
      SwiftRegistryDao registry,
      AssetDao assets,
      RepositoryRuntime runtime,
      RepositoryRecord repository) {}

  private record RestoreCall(
      String scope,
      String name,
      String version,
      String metadataJson,
      byte[] sourceSignature,
      byte[] metadataSignature,
      String signatureFormat,
      Instant publishedAt,
      String actor,
      String ip) {}

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private CloseTrackingInputStream(byte[] body) {
      super(Arrays.copyOf(body, body.length));
    }

    @Override
    public void close() {
      closed = true;
    }

    private boolean closed() {
      return closed;
    }
  }
}
