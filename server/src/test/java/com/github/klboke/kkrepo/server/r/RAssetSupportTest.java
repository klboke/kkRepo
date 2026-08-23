package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.r.RMediaTypes;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RAssetSupportTest {
  private static final String PACKAGE_PATH = "src/contrib/demo_1.0.0.tar.gz";

  private final AssetDao assets = mock(AssetDao.class);
  private final ComponentDao components = mock(ComponentDao.class);
  private final BrowseNodeDao browse = mock(BrowseNodeDao.class);
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final RAssetSupport support = new RAssetSupport(assets, components, browse, hosted);
  private final RepositoryRuntime runtime = runtime();

  @Test
  void storesServesAndDeletesPackagesAndGeneratedMetadata() {
    AssetRecord asset = asset(10L, 30L, Map.of());
    ComponentRecord component = component(null);
    Path file = Path.of("demo_1.0.0.tar.gz");
    when(assets.findAssetByPath(runtime.id(), PACKAGE_PATH)).thenReturn(Optional.of(asset));

    assertSame(asset, support.storePackage(
        runtime, PACKAGE_PATH, "src/contrib/demo/1.0.0/demo_1.0.0.tar.gz", file,
        Map.of("sha256", "a"), "alice", "127.0.0.1", component));
    verify(hosted).putInternalWithComponentFileAtBrowsePath(
        runtime, PACKAGE_PATH, file, RMediaTypes.SOURCE_PACKAGE, Map.of("sha256", "a"),
        "alice", "127.0.0.1", component,
        "src/contrib/demo/1.0.0/demo_1.0.0.tar.gz");

    support.storeGenerated(runtime, ".r/index", new byte[] {1}, Map.of("revision", 1));
    verify(hosted).putInternalUnindexed(
        eq(runtime), eq(".r/index"), any(), eq(RMediaTypes.PACKAGES_GZIP),
        eq(Map.of("revision", 1)), eq("r-metadata"),
        org.mockito.ArgumentMatchers.isNull());
    support.storeGeneratedFile(runtime, ".r/archive", file, Map.of());
    verify(hosted).putInternalUnindexedFile(
        runtime, ".r/archive", file, RMediaTypes.PACKAGES_GZIP, Map.of(),
        "r-metadata", null);

    MavenResponse response = MavenResponse.noBody(204);
    when(hosted.getInternal(runtime, "path", true)).thenReturn(response);
    assertSame(response, support.serve(runtime, "path", true));
    support.delete(runtime, "path");
    verify(hosted).deleteInternal(runtime, "path");
    assertEquals(Optional.of(asset), support.findAsset(runtime, PACKAGE_PATH));
  }

  @Test
  void failsClosedWhenStoredPackageIsMissingAndListsComponentAssets() {
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> support.storePackage(
        runtime, "missing", "browse", Path.of("missing"), Map.of(), null, null,
        component(null)));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.requireAsset(runtime, "missing"));

    AssetRecord asset = asset(10L, 44L, Map.of());
    when(assets.listAssetsByComponent(44L)).thenReturn(List.of(asset));
    assertEquals(List.of(asset), support.listAssetsByComponent(44L));
  }

  @Test
  void retiresProjectionAndDeletesOnlyItsEmptyComponent() {
    support.retirePackageProjection(null);
    verify(assets, never()).findAssetById(org.mockito.ArgumentMatchers.anyLong());

    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 30L, Map.of())));
    support.retirePackageProjection(10L);
    verify(browse).deleteByAssetId(10L);
    verify(assets).updateAssetComponentBinding(10L, null);
    verify(components).deleteIfNoAssets(30L);

    when(assets.findAssetById(11L)).thenReturn(Optional.of(asset(11L, null, Map.of())));
    support.retirePackageProjection(11L);
    support.retirePackageProjection(12L);
    verify(components, never()).deleteIfNoAssets(11L);
  }

  @Test
  void bindsCachedProxyPackageIntoTypedComponentAndBrowseProjection() {
    AssetRecord original = asset(10L, null, Map.of("existing", true));
    AssetRecord rebound = asset(10L, 44L, Map.of("existing", true, "new", "value"));
    ComponentRecord component = component(null);
    when(assets.findAssetByPath(runtime.id(), PACKAGE_PATH)).thenReturn(Optional.of(original));
    when(components.upsertReturningId(component)).thenReturn(44L);
    when(assets.findAssetById(10L)).thenReturn(Optional.of(rebound));

    assertSame(rebound, support.bindProxyPackage(
        runtime, PACKAGE_PATH, component, "src/contrib/demo/1.0.0/demo.tar.gz",
        Map.of("new", "value")));
    verify(assets).updateAssetComponentBinding(10L, 44L);
    verify(assets).updateAssetAttributes(10L, Map.of("existing", true, "new", "value"));
    verify(components).touchLastUpdated(
        eq(44L), any(Instant.class));
    verify(browse).deleteByAssetId(10L);
    verify(browse).upsertPathAncestors(
        runtime.id(), "src/contrib/demo/1.0.0/demo.tar.gz", 10L, 44L);

    when(assets.findAssetById(10L)).thenReturn(Optional.empty());
    assertSame(original, support.bindProxyPackage(
        runtime, PACKAGE_PATH, component, "browse", null));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "r", RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted", true,
        1L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }

  private static ComponentRecord component(Long id) {
    return new ComponentRecord(
        id, 1L, RepositoryFormat.R, "src/contrib", "demo", "1.0.0",
        "r-source-package", new byte[32], Map.of(), Instant.EPOCH);
  }

  private static AssetRecord asset(
      long id, Long componentId, Map<String, Object> attributes) {
    return new AssetRecord(
        id, 1L, componentId, 20L, RepositoryFormat.R, PACKAGE_PATH, new byte[32],
        "demo_1.0.0.tar.gz", "r", RMediaTypes.SOURCE_PACKAGE, 7L, null,
        Instant.EPOCH, attributes);
  }
}
