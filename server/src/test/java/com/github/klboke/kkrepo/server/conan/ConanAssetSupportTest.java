package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConanAssetSupportTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final RawHostedService hosted = mock(RawHostedService.class);
  private final ConanAssetSupport support = new ConanAssetSupport(assets, hosted);
  private final RepositoryRuntime runtime = runtime();

  @Test
  void keepsStagingOutOfBrowseAndPersistsTheNexusProjectionAtPromotion() {
    String staging = ".conan/staging/44/conanfile.py";
    AssetRecord stagedAsset = asset(10L, 20L, staging, null);
    AssetBlobRecord blob = blob();
    when(assets.findAssetByPath(runtime.id(), staging)).thenReturn(Optional.of(stagedAsset));
    when(assets.findBlobById(20L)).thenReturn(Optional.of(blob));

    ConanAssetSupport.Staged staged = support.stage(
        runtime, 44L, "conanfile.py", new ByteArrayInputStream(new byte[] {1}),
        "text/x-python", "alice", "127.0.0.1");

    assertSame(stagedAsset, staged.asset());
    verify(hosted).putInternalHidden(
        eq(runtime), eq(staging), any(), eq("text/x-python"),
        eq(Map.of("conanStaging", true, "conanFile", "conanfile.py")),
        eq("alice"), eq("127.0.0.1"));

    ConanReference reference = new ConanReference(
        "demo", "1.0", "acme", "stable", "rrev", null, null);
    ComponentRecord component = new ComponentRecord(
        30L, runtime.id(), RepositoryFormat.CONAN, "acme/stable", "demo", "1.0",
        "conan-recipe", new byte[32], Map.of(), Instant.EPOCH);
    String storage = "conans/demo/1.0/acme/stable/revisions/rrev/files/conanfile.py";
    AssetRecord promoted = asset(11L, 20L, storage, 30L);
    when(assets.findAssetByPath(runtime.id(), storage)).thenReturn(Optional.of(promoted));

    assertSame(promoted, support.promote(
        runtime, reference, "conanfile.py", staged, "text/x-python", "alice",
        "127.0.0.1", component));
    verify(hosted).linkInternalBlobWithComponentAtBrowsePath(
        runtime,
        storage,
        blob,
        "text/x-python",
        "alice",
        "127.0.0.1",
        component,
        "acme/demo/1.0/stable#rrev/conanfile.py");
  }

  @Test
  void keepsProxyStagingOutOfBrowse() {
    AssetRecord stagedAsset = asset(
        12L, 20L, ".conan/proxy-staging/00000000-0000-0000-0000-000000000001/file", null);
    when(assets.findAssetByPath(eq(runtime.id()), any())).thenReturn(Optional.of(stagedAsset));
    when(assets.findBlobById(20L)).thenReturn(Optional.of(blob()));

    ConanAssetSupport.Staged staged = support.stageProxy(
        runtime, "conan_package.tgz", new ByteArrayInputStream(new byte[0]),
        "application/octet-stream", "https://upstream.example/");

    assertEquals(stagedAsset, staged.asset());
    verify(hosted).putInternalHidden(
        eq(runtime), any(), any(), eq("application/octet-stream"),
        eq(Map.of("conanProxyStaging", true, "conanFile", "conan_package.tgz")),
        eq("conan-proxy"), eq("https://upstream.example/"));
  }

  @Test
  void discardsStagingWhenTheBlobWasNotPersisted() {
    String staging = ".conan/staging/44/conanfile.py";
    AssetRecord missingBlob = asset(10L, null, staging, null);
    when(assets.findAssetByPath(runtime.id(), staging)).thenReturn(Optional.of(missingBlob));

    assertThrows(
        IllegalStateException.class,
        () -> support.stage(
            runtime, 44L, "conanfile.py", InputStream.nullInputStream(),
            null, "alice", "ip"));

    verify(hosted).deleteInternal(runtime, staging);

    String proxyStaging = ".conan/proxy-staging/id/conan_package.tgz";
    AssetRecord proxyMissingBlob = asset(11L, null, proxyStaging, null);
    when(assets.findAssetByPath(eq(runtime.id()), any()))
        .thenReturn(Optional.of(proxyMissingBlob));
    assertThrows(
        IllegalStateException.class,
        () -> support.stageProxy(
            runtime, "conan_package.tgz", InputStream.nullInputStream(),
            "application/gzip", "https://upstream.example"));
    verify(hosted, times(2)).deleteInternal(eq(runtime), anyString());
  }

  @Test
  void servesReadsFindsAndOpensStagedAssets() {
    MavenResponse response = MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {1, 2, 3}), 3,
        "application/octet-stream", null, Instant.EPOCH);
    when(hosted.getInternal(runtime, "path", false)).thenReturn(response);
    AssetRecord asset = asset(1L, 20L, "path", null);
    when(assets.findAssetByPath(runtime.id(), "path")).thenReturn(Optional.of(asset));

    assertArrayEquals(new byte[] {1, 2, 3}, support.readStaged(runtime, "path", 3));
    when(hosted.getInternal(runtime, "path", false)).thenReturn(response);
    assertSame(response, support.openStaged(runtime, "path"));
    assertEquals(Optional.of(asset), support.find(runtime, "path"));
    when(hosted.getInternal(runtime, "conans/demo/1.0/_/_/revisions/r/files/file", true))
        .thenReturn(response);
    assertSame(response, support.serve(
        runtime, new ConanReference("demo", "1.0", null, null, "r", null, null),
        "file", true));
  }

  @Test
  void rejectsOversizedAndUnreadableStagedMetadata() {
    MavenResponse declaredTooLarge = MavenResponse.ok(
        InputStream.nullInputStream(), 4, "text/plain", null, null);
    when(hosted.getInternal(runtime, "large", false)).thenReturn(declaredTooLarge);
    assertThrows(
        ConanExceptions.ContentTooLarge.class,
        () -> support.readStaged(runtime, "large", 3));

    when(hosted.getInternal(runtime, "streamed", false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 0, "text/plain", null, null));
    assertThrows(
        ConanExceptions.ContentTooLarge.class,
        () -> support.readStaged(runtime, "streamed", 3));

    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken");
      }
    };
    when(hosted.getInternal(runtime, "broken", false)).thenReturn(
        MavenResponse.ok(broken, 0, "text/plain", null, null));
    assertThrows(
        ConanExceptions.BadRequest.class,
        () -> support.readStaged(runtime, "broken", 3));
  }

  @Test
  void resolvesBlobsAndDeletesOnlyAssetsOwnedByTheRepository() {
    AssetRecord owned = asset(1L, 20L, "owned", null);
    AssetRecord foreign = new AssetRecord(
        2L, 999L, null, 20L, RepositoryFormat.CONAN, "foreign", new byte[32],
        "foreign", "conan", "application/octet-stream", 1L, null, Instant.EPOCH, Map.of());
    AssetBlobRecord storedBlob = blob();
    when(assets.findAssetById(1L)).thenReturn(Optional.of(owned));
    when(assets.findBlobById(20L)).thenReturn(Optional.of(storedBlob));
    when(assets.findAssetById(2L)).thenReturn(Optional.of(foreign));

    assertSame(storedBlob, support.blob(1L));
    support.deleteByAssetId(runtime, 1L);
    support.deleteByAssetId(runtime, 2L);
    support.deleteByAssetId(runtime, 3L);

    verify(hosted).deleteInternal(runtime, "owned");
    verify(hosted, never()).deleteInternal(runtime, "foreign");
    assertThrows(IllegalStateException.class, () -> support.blob(3L));
  }

  @Test
  void rejectsMissingBlobReferencesAndDiscardsOnlyPresentStaging() {
    AssetRecord noBlob = asset(4L, null, "none", null);
    when(assets.findAssetById(4L)).thenReturn(Optional.of(noBlob));
    assertThrows(IllegalStateException.class, () -> support.blob(4L));

    AssetRecord missingBlob = asset(5L, 99L, "missing", null);
    when(assets.findAssetById(5L)).thenReturn(Optional.of(missingBlob));
    when(assets.findBlobById(99L)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> support.blob(5L));

    support.discard(runtime, null);
    ConanAssetSupport.Staged staged = new ConanAssetSupport.Staged("staged", noBlob, blob());
    support.discard(runtime, staged);
    verify(hosted).deleteInternal(runtime, "staged");
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L, "conan", RepositoryFormat.CONAN, RepositoryType.HOSTED, "conan-hosted",
        true, 1L, "ALLOW", null, null, true, null, null, null, List.of());
  }

  private static AssetRecord asset(Long id, Long blobId, String path, Long componentId) {
    return new AssetRecord(
        id, 1L, componentId, blobId, RepositoryFormat.CONAN, path, new byte[32],
        path.substring(path.lastIndexOf('/') + 1), "conan", "application/octet-stream",
        1L, null, Instant.EPOCH, Map.of());
  }

  private static AssetBlobRecord blob() {
    return new AssetBlobRecord(
        20L, 1L, "blob", new byte[32], "object", new byte[32], "a".repeat(40),
        "b".repeat(64), "c".repeat(32), 1L, "application/octet-stream", "alice",
        "127.0.0.1", Instant.EPOCH, Instant.EPOCH, Map.of());
  }
}
