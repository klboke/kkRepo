package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
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

class CondaAssetSupportTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final BrowseNodeDao browseNodes = mock(BrowseNodeDao.class);
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final RepositoryRuntime runtime = runtime();
  private final AssetRecord asset = asset(10L, 20L);
  private final AssetBlobRecord blob = blob(20L, 7, "a".repeat(64));
  private final ComponentRecord component = new ComponentRecord(
      30L, runtime.id(), RepositoryFormat.CONDA, "main/linux-64", "demo", "1.0",
      "package", new byte[32], Map.of(), Instant.EPOCH);
  private CondaAssetSupport support;

  @BeforeEach
  void setUp() {
    reset(assets, browseNodes, hosted);
    support = new CondaAssetSupport(assets, browseNodes, hosted);
  }

  @Test
  void storesPromotesAndDelegatesAssetAccess() {
    Path file = Path.of("package.conda");
    Map<String, Object> attributes = Map.of("sha256", "a".repeat(64));
    when(assets.findAssetByPath(runtime.id(), "main/linux-64/package.conda"))
        .thenReturn(Optional.of(asset));

    assertSame(asset, support.store(
        runtime, "main/linux-64/package.conda", "main/linux-64/demo/1.0/package.conda",
        file, "application/octet-stream", attributes, "alice", "127.0.0.1", component));
    verify(hosted).putInternalWithComponentFileAtBrowsePath(
        runtime, "main/linux-64/package.conda", file, "application/octet-stream", attributes,
        "alice", "127.0.0.1", component, "main/linux-64/demo/1.0/package.conda");

    CondaAssetSupport.StagedAsset staged = new CondaAssetSupport.StagedAsset("stage", blob);
    assertSame(asset, support.promote(
        runtime, "main/linux-64/package.conda", "browse/package.conda", staged,
        "application/octet-stream", "alice", "127.0.0.1", component));
    verify(hosted).linkInternalBlobWithComponentAtBrowsePath(
        runtime, "main/linux-64/package.conda", blob, "application/octet-stream", "alice",
        "127.0.0.1", component, "browse/package.conda");

    MavenResponse response = MavenResponse.noBody(204);
    CachedAssetMetadata cached = mock(CachedAssetMetadata.class);
    when(hosted.get(runtime, "path", true)).thenReturn(response);
    when(hosted.getInternal(runtime, "internal", false)).thenReturn(response);
    when(hosted.findInternal(runtime, "internal")).thenReturn(Optional.of(cached));
    assertSame(response, support.serve(runtime, "path", true));
    assertSame(response, support.serveInternal(runtime, "internal", false));
    assertEquals(Optional.of(cached), support.findInternal(runtime, "internal"));

    support.storeGenerated(runtime, "repodata.json", file, "application/json", attributes);
    verify(hosted).putInternalUnindexedFile(
        runtime, "repodata.json", file, "application/json", attributes,
        "conda-metadata", runtime.name());
    assertEquals(Optional.of(asset), support.find(runtime, "main/linux-64/package.conda"));
    support.delete(runtime, "main/linux-64/package.conda");
    verify(hosted).deleteInternal(runtime, "main/linux-64/package.conda");
  }

  @Test
  void reportsMissingPersistedStoreAndPromotion() {
    assertThrows(IllegalStateException.class, () -> support.store(
        runtime, "missing", "browse", Path.of("missing"), "type", Map.of(), null, null,
        component));
    assertThrows(IllegalStateException.class, () -> support.promote(
        runtime, "missing", "browse", new CondaAssetSupport.StagedAsset("stage", blob),
        "type", null, null, component));
  }

  @Test
  void stagesOnlyACompleteBlobWithMatchingSizeAndChecksum() {
    when(assets.findAssetByPath(eq(runtime.id()), anyString())).thenReturn(Optional.of(asset));
    when(assets.findBlobById(blob.id())).thenReturn(Optional.of(blob));

    CondaAssetSupport.StagedAsset staged = support.stage(
        runtime, "main/linux-64/demo-1.0-0.conda", Path.of("package.conda"),
        "application/octet-stream", Map.of(), "proxy", "remote", blob.sha256(), blob.size());

    assertTrue(staged.path().startsWith(".conda/staging/"));
    assertTrue(staged.path().endsWith("/demo-1.0-0.conda"));
    assertSame(blob, staged.blob());
    verify(hosted, never()).deleteInternal(runtime, staged.path());
  }

  @Test
  void cleansUpEveryInvalidStagingOutcomeWithoutMaskingTheFailure() {
    when(assets.findAssetByPath(eq(runtime.id()), anyString())).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("cleanup failed"))
        .when(hosted).deleteInternal(eq(runtime), anyString());
    IllegalStateException missing = assertThrows(IllegalStateException.class, () -> support.stage(
        runtime, "main/linux-64/demo.conda", Path.of("package"), "type", Map.of(), null, null,
        blob.sha256(), blob.size()));
    assertTrue(missing.getMessage().contains("was not persisted"));

    reset(assets, hosted);
    AssetRecord withoutBlob = asset(10L, null);
    when(assets.findAssetByPath(eq(runtime.id()), anyString()))
        .thenReturn(Optional.of(withoutBlob));
    assertThrows(IllegalStateException.class, () -> support.stage(
        runtime, "main/linux-64/demo.conda", Path.of("package"), "type", Map.of(), null, null,
        blob.sha256(), blob.size()));
    verify(hosted).deleteInternal(eq(runtime), anyString());

    reset(assets, hosted);
    when(assets.findAssetByPath(eq(runtime.id()), anyString())).thenReturn(Optional.of(asset));
    when(assets.findBlobById(blob.id())).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> support.stage(
        runtime, "main/linux-64/demo.conda", Path.of("package"), "type", Map.of(), null, null,
        blob.sha256(), blob.size()));

    when(assets.findBlobById(blob.id())).thenReturn(Optional.of(blob));
    assertThrows(IllegalStateException.class, () -> support.stage(
        runtime, "main/linux-64/demo.conda", Path.of("package"), "type", Map.of(), null, null,
        null, blob.size()));
    assertThrows(IllegalStateException.class, () -> support.stage(
        runtime, "main/linux-64/demo.conda", Path.of("package"), "type", Map.of(), null, null,
        "b".repeat(64), blob.size()));
    assertThrows(IllegalStateException.class, () -> support.stage(
        runtime, "main/linux-64/demo.conda", Path.of("package"), "type", Map.of(), null, null,
        blob.sha256(), blob.size() + 1));
  }

  @Test
  void discardsBestEffortAndResolvesBlobsStrictly() {
    support.discard(runtime, null);
    verify(hosted, never()).deleteInternal(eq(runtime), anyString());

    CondaAssetSupport.StagedAsset staged = new CondaAssetSupport.StagedAsset("stage", blob);
    support.discard(runtime, staged);
    verify(hosted).deleteInternal(runtime, "stage");
    doThrow(new IllegalStateException("transient")).when(hosted).deleteInternal(runtime, "stage");
    support.discard(runtime, staged);

    when(assets.findAssetByPath(runtime.id(), "package")).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.blob(runtime, "package"));
    when(assets.findAssetByPath(runtime.id(), "package"))
        .thenReturn(Optional.of(asset(10L, null)));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.blob(runtime, "package"));
    when(assets.findAssetByPath(runtime.id(), "package")).thenReturn(Optional.of(asset));
    when(assets.findBlobById(blob.id())).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> support.blob(runtime, "package"));
    when(assets.findBlobById(blob.id())).thenReturn(Optional.of(blob));
    assertSame(blob, support.blob(runtime, "package"));
  }

  @Test
  void bindsDeferredProxyAssetsOnlyWhenAllIdentitiesExist() {
    support.bindCachedPackage(runtime, null, blob, component, "browse");
    support.bindCachedPackage(runtime, asset(null, 20L), blob, component, "browse");
    support.bindCachedPackage(runtime, asset, null, component, "browse");
    support.bindCachedPackage(runtime, asset, blob, null, "browse");
    verify(browseNodes, never()).deleteByAssetId(10L);

    support.bindCachedPackage(runtime, asset, blob, component, "browse");
    verify(browseNodes).deleteByAssetId(10L);
    verify(hosted).linkInternalBlobWithComponentAtBrowsePath(
        runtime, asset.path(), blob, asset.contentType(), "conda-proxy-index",
        runtime.proxyRemoteUrl(), component, "browse");
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1, "conda", RepositoryFormat.CONDA, RepositoryType.PROXY, "conda-proxy", true, 1L,
        "ALLOW_ONCE", null, null, true, "https://repo.example/", 60, 60, true, null,
        List.of());
  }

  private static AssetRecord asset(Long id, Long blobId) {
    return new AssetRecord(
        id, 1, 30L, blobId, RepositoryFormat.CONDA, "main/linux-64/package.conda",
        new byte[32], "package.conda", "package", "application/octet-stream", 7L, null,
        Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob(Long id, long size, String sha256) {
    return new AssetBlobRecord(
        id, 1, "blob", new byte[32], "object", new byte[32], "sha1", sha256, "md5", size,
        "application/octet-stream", "proxy", "remote", Instant.EPOCH, Instant.EPOCH, Map.of());
  }
}
