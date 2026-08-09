package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AptAssetSupportTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final ComponentDao components = mock(ComponentDao.class);
  private final BrowseNodeDao browseNodes = mock(BrowseNodeDao.class);
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final RepositoryRuntime runtime = runtime();
  private final AssetRecord asset = asset(10L, 20L, 30L, Map.of("existing", true));
  private final ComponentRecord component = component();
  private AptAssetSupport support;

  @BeforeEach
  void setUp() {
    support = new AptAssetSupport(assets, components, browseNodes, hosted);
  }

  @Test
  void storesGeneratedAndPackageAssetsAndDelegatesReads() {
    Path file = Path.of("demo.deb");
    Map<String, Object> attributes = Map.of("aptPackage", "demo");
    when(assets.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    assertSame(asset, support.storePackage(
        runtime, asset.path(), "stable/main/demo/1.0/amd64/demo.deb", file,
        attributes, "alice", "127.0.0.1", component));
    verify(hosted).putInternalWithComponentFileAtBrowsePath(
        runtime, asset.path(), file, "application/vnd.debian.binary-package", attributes,
        "alice", "127.0.0.1", component,
        "stable/main/demo/1.0/amd64/demo.deb");

    support.storeGenerated(runtime, ".apt/Release", new byte[] {1, 2}, "text/plain", attributes);
    verify(hosted).putInternalUnindexed(
        any(), any(), any(), any(), any(), any(), any());
    support.storeGeneratedFile(runtime, ".apt/Packages", file, "text/plain", attributes);
    verify(hosted).putInternalUnindexedFile(
        runtime, ".apt/Packages", file, "text/plain", attributes, "apt-metadata", runtime.name());
    MavenResponse response = MavenResponse.noBody(204);
    when(hosted.getInternal(runtime, asset.path(), true)).thenReturn(response);
    assertSame(response, support.serve(runtime, asset.path(), true));
    support.delete(runtime, asset.path());
    verify(hosted).deleteInternal(runtime, asset.path());
    assertEquals(Optional.of(asset), support.findAsset(runtime, asset.path()));
    when(assets.listAssetsByComponent(30L)).thenReturn(List.of(asset));
    assertEquals(List.of(asset), support.listAssetsByComponent(30L));

    assertThrows(IllegalStateException.class, () -> support.storePackage(
        runtime, "missing", "browse", file, attributes, null, null, component));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.requireAsset(runtime, "missing"));
  }

  @Test
  void retiresOldProjectionsAndRemovesOnlyOrphanedComponents() {
    support.retirePackageProjection(null);
    verify(assets, never()).findAssetById(anyLong());

    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset));
    support.retirePackageProjection(10L);
    verify(browseNodes).deleteByAssetId(10L);
    verify(assets).updateAssetComponentBinding(10L, null);
    verify(components).deleteIfNoAssets(30L);

    AssetRecord unbound = asset(11L, 20L, null, Map.of());
    when(assets.findAssetById(11L)).thenReturn(Optional.of(unbound));
    support.retirePackageProjection(11L);
    verify(components, times(1)).deleteIfNoAssets(30L);
    support.retirePackageProjection(99L);
  }

  @Test
  void resolvesBlobsAndBindsProxyProjection() {
    when(components.upsertReturningId(component)).thenReturn(30L);
    assertEquals(30L, support.upsertComponent(component));

    when(assets.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(asset));
    assertThrows(IllegalStateException.class, () -> support.requireBlob(runtime, asset.path()));
    AssetRecord withBlob = asset(10L, 20L, 30L, Map.of());
    when(assets.findAssetByPath(runtime.id(), asset.path())).thenReturn(Optional.of(withBlob));
    assertThrows(IllegalStateException.class, () -> support.requireBlob(runtime, asset.path()));
    AssetBlobRecord blob = blob();
    when(assets.findBlobById(20L)).thenReturn(Optional.of(blob));
    assertSame(blob, support.requireBlob(runtime, asset.path()));

    when(assets.findAssetById(10L)).thenReturn(Optional.of(withBlob));
    AssetRecord bound = support.bindProxyPackage(
        runtime, asset.path(), component, "stable/main/demo", Map.of("aptSource", "proxy"));
    assertSame(withBlob, bound);
    verify(assets).updateAssetComponentBinding(10L, 30L);
    verify(assets).updateAssetAttributes(10L, Map.of("aptSource", "proxy"));
    verify(components).touchLastUpdated(anyLong(), any());
    verify(browseNodes).upsertPathAncestors(runtime.id(), "stable/main/demo", 10L, 30L);

    when(assets.findAssetById(10L)).thenReturn(Optional.empty());
    support.bindProxyPackage(runtime, asset.path(), component, "browse", null);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1, "apt", RepositoryFormat.APT, RepositoryType.PROXY, "apt-proxy", true, 1L,
        "ALLOW", null, null, true, "https://apt.example/", 60, 60, true, null, List.of());
  }

  private static AssetRecord asset(
      Long id, Long blobId, Long componentId, Map<String, Object> attributes) {
    return new AssetRecord(
        id, 1, componentId, blobId, RepositoryFormat.APT, "pool/d/demo/demo_1.0_amd64.deb",
        new byte[32], "demo_1.0_amd64.deb", "package",
        "application/vnd.debian.binary-package", 7L, null, Instant.EPOCH, attributes);
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        20L, 1, "blob", new byte[32], "object", new byte[32], "b".repeat(40),
        "a".repeat(64), "c".repeat(32), 7, "application/vnd.debian.binary-package",
        "proxy", "remote", Instant.EPOCH, Instant.EPOCH, Map.of());
  }

  private static ComponentRecord component() {
    return new ComponentRecord(
        null, 1, RepositoryFormat.APT, "stable/main", "demo", "1.0", "apt-package",
        new byte[32], Map.of(), Instant.EPOCH);
  }
}
