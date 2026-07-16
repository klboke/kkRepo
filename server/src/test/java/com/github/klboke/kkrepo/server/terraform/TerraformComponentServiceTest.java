package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.terraform.TerraformPathParser;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TerraformComponentServiceTest {
  private final TerraformPathParser paths = new TerraformPathParser();
  private AssetDao assetDao;
  private ComponentDao componentDao;
  private BrowseNodeDao browseNodeDao;
  private TerraformRegistryDao registry;
  private TerraformComponentService service;

  @BeforeEach
  void setUp() {
    assetDao = mock(AssetDao.class);
    componentDao = mock(ComponentDao.class);
    browseNodeDao = mock(BrowseNodeDao.class);
    registry = mock(TerraformRegistryDao.class);
    service = new TerraformComponentService(assetDao, componentDao, browseNodeDao, registry);
  }

  @Test
  void createsStableLogicalCoordinatesAndBrowsePaths() {
    RepositoryRuntime runtime = runtime(1, RepositoryType.HOSTED);
    ComponentRecord module = service.moduleComponent(
        runtime,
        paths.parse("v1/modules/acme/network/aws/1.2.3/network.zip"),
        Instant.EPOCH);
    ComponentRecord provider = service.providerComponent(
        runtime,
        paths.parse("v1/providers/acme/demo/1.0.0/download/linux/amd64"),
        Instant.EPOCH);

    assertEquals("terraform-module", module.kind());
    assertEquals("network", module.name());
    assertEquals("aws", module.attributes().get("system"));
    assertEquals(
        "v1/modules/acme/network/aws/1.2.3", module.attributes().get("browsePath"));
    assertEquals("terraform-provider", provider.kind());
    assertEquals("demo", provider.name());
    assertEquals(
        "v1/providers/acme/demo/1.0.0", provider.attributes().get("browsePath"));
    assertNotEquals(
        Arrays.toString(module.coordinateHash()), Arrays.toString(provider.coordinateHash()));
  }

  @Test
  void proxyRebuildCollapsesPublicAssetsAndSkipsInternalCacheRows() {
    RepositoryRuntime runtime = runtime(2, RepositoryType.PROXY);
    AssetRecord module = asset(
        10, runtime, "v1/modules/acme/network/aws/1.2.3/network.zip");
    AssetRecord provider = asset(
        20, runtime,
        "v1/providers/acme/demo/1.0.0/download/linux/amd64/provider.zip");
    AssetRecord sums = asset(
        21, runtime,
        "v1/providers/acme/demo/1.0.0/download/linux/amd64/SHA256SUMS");
    AssetRecord internal = asset(
        30, runtime, ".terraform/routes/route.json");
    when(assetDao.listAssetsByPrefix(runtime.id(), ""))
        .thenReturn(List.of(module, provider, sums, internal));
    when(componentDao.upsertReturningId(any())).thenAnswer(invocation -> {
      ComponentRecord component = invocation.getArgument(0);
      return "terraform-module".equals(component.kind()) ? 101L : 202L;
    });

    service.rebuild(runtime);

    verify(componentDao).deleteByRepositoryIdAndFormat(
        runtime.id(), RepositoryFormat.TERRAFORM);
    ArgumentCaptor<ComponentRecord> components = ArgumentCaptor.forClass(ComponentRecord.class);
    verify(componentDao, times(2)).upsertReturningId(components.capture());
    assertEquals(
        Set.of("terraform-module", "terraform-provider"),
        Set.copyOf(components.getAllValues().stream().map(ComponentRecord::kind).toList()));
    verify(assetDao).updateAssetComponentBinding(module.id(), 101L);
    verify(assetDao).updateAssetComponentBinding(provider.id(), 202L);
    verify(assetDao).updateAssetComponentBinding(sums.id(), 202L);
    verify(assetDao, never()).updateAssetComponentBinding(internal.id(), 101L);
    verify(assetDao, never()).updateAssetComponentBinding(internal.id(), 202L);
  }

  @Test
  void hostedRebuildIndexesOnlyReadyProviderAssetsAndCurrentMetadata() {
    RepositoryRuntime runtime = runtime(3, RepositoryType.HOSTED);
    String archivePath = "v1/providers/acme/demo/1.0.0/package/linux/provider.zip";
    String sumsPath =
        "v1/providers/acme/demo/1.0.0/metadata-r2/terraform-provider-demo_1.0.0_SHA256SUMS";
    String signaturePath = sumsPath + ".sig";
    AssetRecord archive = asset(40, runtime, archivePath);
    AssetRecord sums = asset(41, runtime, sumsPath);
    AssetRecord signature = asset(42, runtime, signaturePath);
    AssetRecord oldMetadata = asset(
        43, runtime,
        "v1/providers/acme/demo/1.0.0/metadata-r1/terraform-provider-demo_1.0.0_SHA256SUMS");
    AssetRecord orphan = asset(
        44, runtime, "v1/providers/acme/demo/1.0.0/package/darwin/orphan.zip");
    when(assetDao.listAssetsByPrefix(runtime.id(), ""))
        .thenReturn(List.of(archive, sums, signature, oldMetadata, orphan));
    when(registry.findProviderState(runtime.id(), "acme", "demo", "1.0.0"))
        .thenReturn(Optional.of(new TerraformRegistryDao.ProviderState(
            runtime.id(), "acme", "demo", "1.0.0", 2,
            sumsPath, signaturePath, 1, Instant.now())));
    when(registry.listProviderPlatforms(runtime.id(), "acme", "demo", "1.0.0"))
        .thenReturn(List.of(new TerraformRegistryDao.ProviderPlatform(
            runtime.id(), "acme", "demo", "1.0.0", "linux", "amd64",
            "provider.zip", archivePath, "sha256", "5.0", 2, Instant.now())));
    when(componentDao.upsertReturningId(any())).thenReturn(303L);

    service.rebuild(runtime);

    verify(componentDao).upsertReturningId(any());
    verify(assetDao).updateAssetComponentBinding(archive.id(), 303L);
    verify(assetDao).updateAssetComponentBinding(sums.id(), 303L);
    verify(assetDao).updateAssetComponentBinding(signature.id(), 303L);
    verify(assetDao, never()).updateAssetComponentBinding(oldMetadata.id(), 303L);
    verify(assetDao, never()).updateAssetComponentBinding(orphan.id(), 303L);
  }

  @Test
  void providerPublicationCommitsStateAndBindingsThroughOneServiceCall() {
    RepositoryRuntime runtime = runtime(4, RepositoryType.HOSTED);
    String oldArchive = "v1/providers/acme/demo/1.0.0/package/linux/old.zip";
    String newArchive = "v1/providers/acme/demo/1.0.0/package/linux/new.zip";
    String oldSums = "v1/providers/acme/demo/1.0.0/metadata-r1/SHA256SUMS";
    String oldSignature = oldSums + ".sig";
    String newSums = "v1/providers/acme/demo/1.0.0/metadata-r2/SHA256SUMS";
    String newSignature = newSums + ".sig";
    TerraformRegistryDao.ProviderPlatform platform = new TerraformRegistryDao.ProviderPlatform(
        runtime.id(), "acme", "demo", "1.0.0", "linux", "amd64",
        "new.zip", newArchive, "sha256", "5.0", 2, Instant.now());
    TerraformRegistryDao.ProviderState state = new TerraformRegistryDao.ProviderState(
        runtime.id(), "acme", "demo", "1.0.0", 2,
        newSums, newSignature, 1, Instant.now());
    when(registry.findProviderState(runtime.id(), "acme", "demo", "1.0.0"))
        .thenReturn(Optional.of(new TerraformRegistryDao.ProviderState(
            runtime.id(), "acme", "demo", "1.0.0", 1,
            oldSums, oldSignature, 1, Instant.EPOCH)));
    when(registry.listProviderPlatforms(runtime.id(), "acme", "demo", "1.0.0"))
        .thenReturn(List.of(new TerraformRegistryDao.ProviderPlatform(
            runtime.id(), "acme", "demo", "1.0.0", "linux", "amd64",
            "old.zip", oldArchive, "old", "5.0", 1, Instant.EPOCH)));
    when(componentDao.upsertReturningId(any())).thenReturn(404L);
    for (AssetRecord asset : List.of(
        asset(50, runtime, oldArchive),
        asset(51, runtime, oldSums),
        asset(52, runtime, oldSignature),
        asset(53, runtime, newArchive),
        asset(54, runtime, newSums),
        asset(55, runtime, newSignature))) {
      when(assetDao.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    }

    service.publishProvider(
        runtime, platform, state, List.of(newArchive, newSums, newSignature));

    verify(registry).publishProvider(platform, state);
    verify(assetDao).updateAssetComponentBinding(50L, null);
    verify(assetDao).updateAssetComponentBinding(51L, null);
    verify(assetDao).updateAssetComponentBinding(52L, null);
    verify(assetDao).updateAssetComponentBinding(53L, 404L);
    verify(assetDao).updateAssetComponentBinding(54L, 404L);
    verify(assetDao).updateAssetComponentBinding(55L, 404L);
    ArgumentCaptor<ComponentRecord> component = ArgumentCaptor.forClass(ComponentRecord.class);
    verify(componentDao).upsertReturningId(component.capture());
    assertEquals("terraform-provider", component.getValue().kind());
    assertEquals("v1/providers/acme/demo/1.0.0",
        component.getValue().attributes().get("browsePath"));
  }

  @Test
  void groupRebuildDoesNotPersistDuplicateComponents() {
    service.rebuild(runtime(5, RepositoryType.GROUP));

    verifyNoInteractions(assetDao, componentDao, browseNodeDao, registry);
  }

  private static RepositoryRuntime runtime(long id, RepositoryType type) {
    return new RepositoryRuntime(
        id, "terraform-" + id, RepositoryFormat.TERRAFORM, type,
        "terraform-" + type.name().toLowerCase(), true, 1L, "ALLOW",
        null, null, true, type == RepositoryType.PROXY ? "https://registry.example/" : null,
        60, 60, true, null, List.of());
  }

  private static AssetRecord asset(long id, RepositoryRuntime runtime, String path) {
    return new AssetRecord(
        id, runtime.id(), 999L, id + 1000, RepositoryFormat.TERRAFORM, path,
        new byte[32], path.substring(path.lastIndexOf('/') + 1), "terraform",
        "application/octet-stream", 10L, null, Instant.now(), Map.of());
  }
}
