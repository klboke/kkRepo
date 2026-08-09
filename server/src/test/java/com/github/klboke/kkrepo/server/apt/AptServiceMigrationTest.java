package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AptServiceMigrationTest {

  @Test
  void restoresPackageWithoutGeneratingAKeyOrPublishingMetadata() throws Exception {
    AptRegistryDao registry = mock(AptRegistryDao.class);
    AptRepositorySettings repositorySettings = mock(AptRepositorySettings.class);
    AptAssetSupport assets = mock(AptAssetSupport.class);
    AptMetadataBuilder metadataBuilder = mock(AptMetadataBuilder.class);
    AptSigningService signing = mock(AptSigningService.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        1, "apt-migrated", RepositoryFormat.APT, RepositoryType.HOSTED, "apt-hosted",
        false, 1L, "ALLOW", null, null, true, null, null, null, null, null, List.of());
    when(repositorySettings.get(runtime)).thenReturn(new AptRepositorySettings.Settings(
        "stable", "main", List.of("amd64"), false, true, true, null, "kkRepo", "kkRepo"));
    when(registry.findPackage(
        runtime.id(), "stable", "main", "demo", "1.0-1", "amd64"))
        .thenReturn(Optional.empty());
    when(registry.findPackageByPath(runtime.id(), "pool/d/demo/demo_1.0-1_amd64.deb"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any(), any())).thenAnswer(invocation ->
        Optional.of(new AptRegistryDao.Lease(
            invocation.getArgument(0), invocation.getArgument(1), 1, 1,
            invocation.getArgument(3), invocation.getArgument(2))));
    when(registry.renewLease(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(true);
    when(registry.savePackage(any())).thenAnswer(invocation -> invocation.getArgument(0));

    byte[] archive = AptTestPackage.deb(
        "gz", AptTestPackage.control("demo", "1.0-1", "amd64"));
    AssetRecord asset = new AssetRecord(
        10L, runtime.id(), 20L, 30L, RepositoryFormat.APT,
        "pool/d/demo/demo_1.0-1_amd64.deb", new byte[32],
        "demo_1.0-1_amd64.deb", "package", "application/vnd.debian.binary-package",
        (long) archive.length, null, Instant.EPOCH, Map.of());
    when(assets.storePackage(
        any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(asset);

    AptService service = new AptService(
        registry,
        new AptPublishedSnapshotCache(registry),
        repositorySettings,
        new AptDebPackageInspector(),
        new AptComponentFactory(),
        assets,
        metadataBuilder,
        signing,
        new AptLeaseManager(registry),
        mock(RawProxyService.class),
        mock(AptProxyProjectionService.class));

    try (AptDebPackageInspector.InspectedPackage inspected = new AptDebPackageInspector().inspect(
        new ByteArrayInputStream(archive), "demo_1.0-1_amd64.deb")) {
      AptService.PublishedPackage restored = service.restoreHostedPackageForMigration(
          runtime,
          inspected,
          "stable",
          "main",
          "nexus-migration",
          "127.0.0.1",
          "pool/d/demo/demo_1.0-1_amd64.deb");
      assertEquals("demo", restored.packageName());
      assertEquals("1.0-1", restored.version());
    }

    verify(signing, never()).active(any());
    verify(metadataBuilder, never()).build(any(), any(), any(), any());
  }
}
