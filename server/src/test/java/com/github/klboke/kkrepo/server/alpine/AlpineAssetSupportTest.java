package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.github.klboke.kkrepo.protocol.alpine.AlpineMediaTypes;
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

class AlpineAssetSupportTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final ComponentDao components = mock(ComponentDao.class);
  private final BrowseNodeDao browse = mock(BrowseNodeDao.class);
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final AlpineAssetSupport support =
      new AlpineAssetSupport(assets, components, browse, hosted);
  private final RepositoryRuntime runtime = runtime();

  @Test
  void storesAndServesPackagesAndGeneratedMetadata() {
    AssetRecord asset = asset(10L, 20L, 30L, Map.of());
    ComponentRecord component = component(30L);
    Path file = Path.of("demo.apk");
    when(assets.findAssetByPath(runtime.id(), "v3.20/main/x86_64/demo.apk"))
        .thenReturn(Optional.of(asset));

    assertSame(asset, support.storePackage(
        runtime, "v3.20/main/x86_64/demo.apk", "v3.20/main/x86_64/demo/1/demo.apk",
        file, Map.of("sha256", "a"), "alice", "127.0.0.1", component));
    verify(hosted).putInternalWithComponentFileAtBrowsePath(
        runtime, "v3.20/main/x86_64/demo.apk", file, AlpineMediaTypes.APK_PACKAGE,
        Map.of("sha256", "a"), "alice", "127.0.0.1", component,
        "v3.20/main/x86_64/demo/1/demo.apk");

    support.storeGenerated(runtime, "index", new byte[] {1}, "type", Map.of("revision", 1));
    verify(hosted).putInternalUnindexed(
        org.mockito.ArgumentMatchers.eq(runtime), org.mockito.ArgumentMatchers.eq("index"),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("type"),
        org.mockito.ArgumentMatchers.eq(Map.of("revision", 1)),
        org.mockito.ArgumentMatchers.eq("alpine-metadata"), org.mockito.ArgumentMatchers.isNull());
    support.storeGeneratedFile(runtime, "archive", file, "type", Map.of());
    verify(hosted).putInternalUnindexedFile(
        runtime, "archive", file, "type", Map.of(), "alpine-metadata", null);

    MavenResponse response = MavenResponse.noBody(204);
    when(hosted.getInternal(runtime, "path", true)).thenReturn(response);
    assertSame(response, support.serve(runtime, "path", true));
    support.delete(runtime, "path");
    verify(hosted).deleteInternal(runtime, "path");
    assertEquals(Optional.of(asset), support.findAsset(runtime, "v3.20/main/x86_64/demo.apk"));
  }

  @Test
  void failsClosedWhenPersistedPackageOrBlobIsMissing() {
    assertThrows(IllegalStateException.class, () -> support.storePackage(
        runtime, "missing", "browse", Path.of("missing"), Map.of(), null, null, component(1L)));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.requireAsset(runtime, "missing"));

    when(assets.findAssetByPath(runtime.id(), "no-blob"))
        .thenReturn(Optional.of(asset(10L, null, null, Map.of())));
    assertThrows(IllegalStateException.class, () -> support.requireBlob(runtime, "no-blob"));

    when(assets.findAssetByPath(runtime.id(), "missing-blob"))
        .thenReturn(Optional.of(asset(10L, 20L, null, Map.of())));
    assertThrows(IllegalStateException.class, () -> support.requireBlob(runtime, "missing-blob"));

    AssetBlobRecord blob = blob();
    when(assets.findBlobById(20L)).thenReturn(Optional.of(blob));
    assertSame(blob, support.requireBlob(runtime, "missing-blob"));
  }

  @Test
  void retiresProjectionAndDeletesEmptyComponent() {
    support.retirePackageProjection(null);
    verify(assets, never()).findAssetById(org.mockito.ArgumentMatchers.anyLong());

    when(assets.findAssetById(10L)).thenReturn(Optional.of(asset(10L, 20L, 30L, Map.of())));
    support.retirePackageProjection(10L);
    verify(browse).deleteByAssetId(10L);
    verify(assets).updateAssetComponentBinding(10L, null);
    verify(components).deleteIfNoAssets(30L);

    when(assets.findAssetById(11L)).thenReturn(Optional.of(asset(11L, 20L, null, Map.of())));
    support.retirePackageProjection(11L);
    verify(components, never()).deleteIfNoAssets(11L);
  }

  @Test
  void bindsCachedProxyPackageIntoComponentAndBrowseProjection() {
    AssetRecord original = asset(10L, 20L, null, Map.of("existing", true));
    AssetRecord rebound = asset(10L, 20L, 44L, Map.of("existing", true, "new", "value"));
    ComponentRecord component = component(null);
    when(assets.findAssetByPath(runtime.id(), "v3.20/main/x86_64/demo.apk"))
        .thenReturn(Optional.of(original));
    when(components.upsertReturningId(component)).thenReturn(44L);
    when(assets.findAssetById(10L)).thenReturn(Optional.of(rebound));

    assertSame(rebound, support.bindProxyPackage(
        runtime, "v3.20/main/x86_64/demo.apk", component, "browse", Map.of("new", "value")));
    verify(assets).updateAssetComponentBinding(10L, 44L);
    verify(assets).updateAssetAttributes(10L, Map.of("existing", true, "new", "value"));
    verify(components).touchLastUpdated(org.mockito.ArgumentMatchers.eq(44L),
        org.mockito.ArgumentMatchers.any(Instant.class));
    verify(browse).upsertPathAncestors(runtime.id(), "browse", 10L, 44L);

    when(assets.findAssetById(10L)).thenReturn(Optional.empty());
    assertSame(original, support.bindProxyPackage(
        runtime, "v3.20/main/x86_64/demo.apk", component, "browse", null));
    assertEquals(44L, support.upsertComponent(component));
    when(assets.listAssetsByComponent(44L)).thenReturn(List.of(original));
    assertEquals(List.of(original), support.listAssetsByComponent(44L));
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "alpine", RepositoryFormat.ALPINE, RepositoryType.HOSTED, "alpine-hosted", true,
        1L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }

  private static ComponentRecord component(Long id) {
    return new ComponentRecord(
        id, 1L, RepositoryFormat.ALPINE, "v3.20/main/x86_64", "demo", "1.0-r0",
        "alpine-apk-v2", new byte[32], Map.of(), Instant.EPOCH);
  }

  private static AssetRecord asset(
      Long id, Long blobId, Long componentId, Map<String, Object> attributes) {
    return new AssetRecord(
        id, 1L, componentId, blobId, RepositoryFormat.ALPINE,
        "v3.20/main/x86_64/demo.apk", new byte[32], "demo.apk", "package",
        AlpineMediaTypes.APK_PACKAGE, 7L, null, Instant.EPOCH, attributes);
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        20L, 1L, "blob", new byte[32], "object", new byte[32], "sha1", "a".repeat(64),
        "md5", 7L, AlpineMediaTypes.APK_PACKAGE, "alice", "127.0.0.1", Instant.EPOCH,
        Instant.EPOCH, Map.of());
  }
}
