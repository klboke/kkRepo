package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPath;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TerraformRepositoryDataMigrationWriterTest {
  @Test
  void selectsOnlyNexusModuleAndProviderArchivesForMigration() {
    assertTrue(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/modules/kkrepo/network/aws/1.2.3/kkrepo-network-aws_1.2.3.zip"));
    assertTrue(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/terraform-provider-fixture_1.2.3_linux_amd64.zip"));

    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/SHA256SUMS"));
    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/versions.json"));
    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(
        "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/../../escape.zip"));
    assertFalse(TerraformRepositoryDataMigrationWriter.isMigratableTerraformPath(null));
  }

  @Test
  void reusesAlreadyMigratedModuleAndValidatesItsBlob() {
    Fixture fixture = fixture();
    String path = "v1/modules/kkrepo/network/aws/1.2.3/module.zip";
    RepositoryDataMigrationAssetRecord source = source(path, 7L, "application/zip", "nexus", "10.0.0.1");
    AssetRecord asset = asset(fixture.runtime(), 101, 102L, path, 103L);
    AssetBlobRecord blob = blob(102, 7, "module-object");
    when(fixture.assets().find(fixture.runtime(), path)).thenReturn(Optional.of(asset));
    when(fixture.assets().blob(asset)).thenReturn(blob);

    CloseTrackingInputStream body = new CloseTrackingInputStream(new byte[0]);
    TerraformRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, body, null, true);

    assertTrue(body.closed());
    assertEquals(103L, migrated.componentId());
    assertEquals(101L, migrated.assetId());
    assertEquals(102L, migrated.assetBlobId());
    assertEquals("module-object", migrated.assetBlobObjectKey());
    verify(fixture.service(), never())
        .putForMigration(any(), any(), any(), any(), any(), any(), any());

    RepositoryDataMigrationAssetRecord wrongSize = source(path, 8L, null, null, null);
    assertThrows(IllegalStateException.class,
        () -> fixture.writer().write(
            fixture.repository(), wrongSize, new ByteArrayInputStream(new byte[0]), null, true));
    when(fixture.assets().blob(asset)).thenReturn(null);
    assertThrows(IllegalStateException.class,
        () -> fixture.writer().write(
            fixture.repository(), source, new ByteArrayInputStream(new byte[0]), null, false));
  }

  @Test
  void reusesPublishedProviderPlatformOnlyAfterSharedStateConfirmsPublication() {
    Fixture fixture = fixture();
    String sourcePath = "v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/"
        + "terraform-provider-fixture_1.2.3_linux_amd64.zip";
    String targetPath = "v1/providers/kkrepo/fixture/1.2.3/package/linux/"
        + "terraform-provider-fixture_1.2.3_linux_amd64.zip";
    RepositoryDataMigrationAssetRecord source = source(sourcePath, 9L, "application/zip", null, null);
    AssetRecord asset = asset(fixture.runtime(), 201, 202L, targetPath, null);
    when(fixture.assets().find(fixture.runtime(), targetPath)).thenReturn(Optional.of(asset));
    when(fixture.registry().listProviderPlatforms(
        fixture.runtime().id(), "kkrepo", "fixture", "1.2.3")).thenReturn(List.of(
            new TerraformRegistryDao.ProviderPlatform(
                fixture.runtime().id(), "kkrepo", "fixture", "1.2.3", "linux", "amd64",
                "terraform-provider-fixture_1.2.3_linux_amd64.zip", targetPath, "sha256", "5.0",
                1, Instant.now())));
    when(fixture.assets().blob(asset)).thenReturn(blob(202, 9, "provider-object"));

    TerraformRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(new byte[0]), null, true);

    assertEquals(201L, migrated.assetId());
    assertEquals("provider-object", migrated.assetBlobObjectKey());
    verify(fixture.service(), never())
        .putForMigration(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void buffersAndReplaysModuleThroughNormalPublicationPath() {
    Fixture fixture = fixture();
    String path = "v1/modules/kkrepo/network/aws/1.2.3/module.zip";
    byte[] content = "module!".getBytes(StandardCharsets.UTF_8);
    RepositoryDataMigrationAssetRecord source = source(
        "/" + path + "/", (long) content.length, "application/zip", "nexus-user", "10.0.0.2");
    AssetRecord stored = asset(fixture.runtime(), 301, 302L, path, 303L);
    when(fixture.assets().find(fixture.runtime(), path))
        .thenReturn(Optional.empty(), Optional.of(stored));
    when(fixture.assets().blob(stored)).thenReturn(blob(302, content.length, "module-new"));
    when(fixture.service().putForMigration(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());

    CloseTrackingInputStream body = new CloseTrackingInputStream(content);
    TerraformRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, body, "application/x-terraform-module", true);

    assertTrue(body.closed());
    assertEquals(301L, migrated.assetId());
    ArgumentCaptor<TerraformPath> pathCaptor = ArgumentCaptor.forClass(TerraformPath.class);
    verify(fixture.service()).putForMigration(
        eq(fixture.runtime()), pathCaptor.capture(), any(InputStream.class),
        eq("application/x-terraform-module"), eq(null), eq("nexus-user"), eq("10.0.0.2"));
    assertEquals(TerraformPath.Kind.MODULE_ARCHIVE, pathCaptor.getValue().kind());
    assertEquals(path, pathCaptor.getValue().rawPath());
  }

  @Test
  void mapsNexusProviderArchiveToCanonicalPackagePathAndDefaultsMetadata() {
    Fixture fixture = fixture();
    String filename = "terraform-provider-fixture_1.2.3_linux_amd64.zip";
    String sourcePath = "/v1/providers/kkrepo/fixture/1.2.3/download/linux/amd64/" + filename;
    String targetPath = "v1/providers/kkrepo/fixture/1.2.3/package/linux/" + filename;
    byte[] content = "provider".getBytes(StandardCharsets.UTF_8);
    RepositoryDataMigrationAssetRecord source = source(sourcePath, (long) content.length, null, null, null);
    AssetRecord stored = asset(fixture.runtime(), 401, 402L, targetPath, null);
    when(fixture.assets().find(fixture.runtime(), targetPath))
        .thenReturn(Optional.empty(), Optional.of(stored));
    when(fixture.registry().listProviderPlatforms(
        fixture.runtime().id(), "kkrepo", "fixture", "1.2.3")).thenReturn(List.of());
    when(fixture.assets().blob(stored)).thenReturn(blob(402, content.length, "provider-new"));
    when(fixture.service().putForMigration(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());

    fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(content), " ", true);

    ArgumentCaptor<TerraformPath> pathCaptor = ArgumentCaptor.forClass(TerraformPath.class);
    verify(fixture.service()).putForMigration(
        eq(fixture.runtime()), pathCaptor.capture(), any(InputStream.class),
        eq("application/octet-stream"), eq("attachment; filename=\"" + filename + "\""),
        eq("nexus-migration"), eq(null));
    assertEquals(TerraformPath.Kind.PROVIDER_DOWNLOAD, pathCaptor.getValue().kind());
    assertEquals("linux", pathCaptor.getValue().os());
    assertEquals("amd64", pathCaptor.getValue().arch());
  }

  @Test
  void restoresProxyProviderArchiveAtItsNexusCachePath() {
    Fixture fixture = fixture(RepositoryType.PROXY);
    String filename = "terraform-provider-null_3.2.4_linux_amd64.zip";
    String path = "v1/providers/hashicorp/null/3.2.4/download/linux/amd64/" + filename;
    byte[] content = "cached-provider".getBytes(StandardCharsets.UTF_8);
    RepositoryDataMigrationAssetRecord source = source(
        path, (long) content.length, "application/zip", "nexus-proxy", "10.0.0.3");
    AssetRecord stored = asset(fixture.runtime(), 501, 502L, path, null);
    when(fixture.assets().find(fixture.runtime(), path))
        .thenReturn(Optional.empty(), Optional.of(stored));
    when(fixture.assets().blob(stored)).thenReturn(blob(502, content.length, "provider-cache"));
    when(fixture.service().restoreProxyCacheForMigration(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());

    TerraformRepositoryDataMigrationWriter.MigratedAsset migrated = fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(content), null, true);

    assertEquals(501L, migrated.assetId());
    assertEquals("provider-cache", migrated.assetBlobObjectKey());
    ArgumentCaptor<TerraformPath> pathCaptor = ArgumentCaptor.forClass(TerraformPath.class);
    verify(fixture.service()).restoreProxyCacheForMigration(
        eq(fixture.runtime()), pathCaptor.capture(), any(InputStream.class),
        eq("application/zip"), eq("nexus-proxy"), eq("10.0.0.3"));
    assertEquals(TerraformPath.Kind.PROVIDER_ARCHIVE, pathCaptor.getValue().kind());
    assertEquals(path, pathCaptor.getValue().rawPath());
    verify(fixture.service(), never())
        .putForMigration(any(), any(), any(), any(), any(), any(), any());
    verify(fixture.registry(), never()).listProviderPlatforms(anyLong(), any(), any(), any());
  }

  @Test
  void restoresProxyModuleArchiveWithoutUsingHostedPublication() {
    Fixture fixture = fixture(RepositoryType.PROXY);
    String path = "v1/modules/terraform-aws-modules/vpc/aws/5.21.0/module.zip";
    byte[] content = "cached-module".getBytes(StandardCharsets.UTF_8);
    RepositoryDataMigrationAssetRecord source = source(path, (long) content.length, null, null, null);
    AssetRecord stored = asset(fixture.runtime(), 601, 602L, path, null);
    when(fixture.assets().find(fixture.runtime(), path))
        .thenReturn(Optional.empty(), Optional.of(stored));
    when(fixture.assets().blob(stored)).thenReturn(blob(602, content.length, "module-cache"));
    when(fixture.service().restoreProxyCacheForMigration(any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());

    fixture.writer().write(
        fixture.repository(), source, new ByteArrayInputStream(content), "application/zip", true);

    ArgumentCaptor<TerraformPath> pathCaptor = ArgumentCaptor.forClass(TerraformPath.class);
    verify(fixture.service()).restoreProxyCacheForMigration(
        eq(fixture.runtime()), pathCaptor.capture(), any(InputStream.class),
        eq("application/zip"), eq("nexus-migration"), eq(null));
    assertEquals(TerraformPath.Kind.MODULE_ARCHIVE, pathCaptor.getValue().kind());
    assertEquals(path, pathCaptor.getValue().rawPath());
    verify(fixture.service(), never())
        .putForMigration(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectsInvalidTargetsSizeMismatchAndMissingPublishedAsset() {
    Fixture fixture = fixture();
    byte[] content = "module".getBytes(StandardCharsets.UTF_8);
    String modulePath = "v1/modules/kkrepo/network/aws/1.2.3/module.zip";

    RepositoryRecord raw = new RepositoryRecord(
        1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, null, null, null, null, "ALLOW_ONCE", true, Map.of());
    assertThrows(IllegalArgumentException.class,
        () -> fixture.writer().write(raw, source(modulePath, 6L, null, null, null),
            new ByteArrayInputStream(content), null, true));

    when(fixture.runtimes().resolveById(fixture.repository().id())).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class,
        () -> fixture.writer().write(fixture.repository(), source(modulePath, 6L, null, null, null),
            new ByteArrayInputStream(content), null, true));

    when(fixture.runtimes().resolveById(fixture.repository().id()))
        .thenReturn(Optional.of(fixture.runtime()));
    assertThrows(IllegalArgumentException.class,
        () -> fixture.writer().write(fixture.repository(), source("v1/providers/invalid", 1L, null, null, null),
            new ByteArrayInputStream(content), null, true));

    when(fixture.assets().find(fixture.runtime(), modulePath)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class,
        () -> fixture.writer().write(fixture.repository(), source(modulePath, 99L, null, null, null),
            new ByteArrayInputStream(content), null, true));
    verify(fixture.service(), never())
        .putForMigration(any(), any(), any(), any(), any(), any(), any());

    when(fixture.service().putForMigration(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MavenResponse.created());
    assertThrows(IllegalStateException.class,
        () -> fixture.writer().write(fixture.repository(), source(modulePath, 6L, null, null, null),
            new ByteArrayInputStream(content), null, false));
  }

  private static Fixture fixture() {
    return fixture(RepositoryType.HOSTED);
  }

  private static Fixture fixture(RepositoryType type) {
    TerraformService service = mock(TerraformService.class);
    TerraformAssetSupport assets = mock(TerraformAssetSupport.class);
    TerraformRegistryDao registry = mock(TerraformRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        7L, type == RepositoryType.PROXY ? "terraform-proxy" : "terraform-hosted",
        RepositoryFormat.TERRAFORM, type,
        type == RepositoryType.PROXY ? "terraform-proxy" : "terraform-hosted",
        true, 1L, "ALLOW_ONCE", null, null, true,
        type == RepositoryType.PROXY ? "https://registry.terraform.io/" : null,
        null, null, null, null, List.of());
    RepositoryRecord repository = new RepositoryRecord(
        runtime.id(), runtime.name(), RepositoryFormat.TERRAFORM, type,
        runtime.recipeName(), true, 1L,
        null,
        type == RepositoryType.PROXY ? "https://registry.terraform.io/" : null,
        null, null, "ALLOW_ONCE", true, Map.of());
    when(runtimes.resolveById(runtime.id())).thenReturn(Optional.of(runtime));
    return new Fixture(
        new TerraformRepositoryDataMigrationWriter(service, assets, registry, runtimes),
        service, assets, registry, runtimes, runtime, repository);
  }

  private static RepositoryDataMigrationAssetRecord source(
      String path, Long size, String contentType, String createdBy, String createdByIp) {
    return new RepositoryDataMigrationAssetRecord(
        1L, 2L, "asset-1", "component-1", path, new byte[32], RepositoryFormat.TERRAFORM,
        "kkrepo", "fixture", "1.2.3", "archive", contentType, size, "blob-ref",
        Instant.now(), null, Instant.now(), Instant.now(), createdBy, createdByIp,
        "PENDING", 0, null, null, null, null, null, null, Map.of(), Instant.now());
  }

  private static AssetRecord asset(
      RepositoryRuntime runtime, long id, Long blobId, String path, Long componentId) {
    return new AssetRecord(
        id, runtime.id(), componentId, blobId, RepositoryFormat.TERRAFORM, path, new byte[32],
        path.substring(path.lastIndexOf('/') + 1), "terraform", "application/zip", 7L,
        null, Instant.now(), Map.of());
  }

  private static AssetBlobRecord blob(long id, long size, String objectKey) {
    return new AssetBlobRecord(
        id, 1L, "blob-ref", new byte[32], objectKey, new byte[32], "sha1", "sha256", "md5",
        size, "application/zip", "nexus", null, Instant.now(), Instant.now(), Map.of());
  }

  private record Fixture(
      TerraformRepositoryDataMigrationWriter writer,
      TerraformService service,
      TerraformAssetSupport assets,
      TerraformRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      RepositoryRuntime runtime,
      RepositoryRecord repository) {}

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private CloseTrackingInputStream(byte[] body) {
      super(body);
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
