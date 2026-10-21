package com.github.klboke.kkrepo.server.composer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.composer.ComposerPathParser;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ComposerProxyRouteCacheTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void warmGetAndHeadOnAnotherReplicaKeepRoutesUnchangedUsingBoundedReads() throws Exception {
    Fixture f = new Fixture();
    f.metadata(java.util.stream.IntStream.range(0, 600)
        .mapToObj(i -> version("1.0." + i, "https://dist.test/" + i + ".zip")).toList());
    MavenResponse first = f.get(f.replica(), false);
    byte[] expected = first.body().readAllBytes();
    assertEquals(600, f.writes.size());
    f.writes.clear();
    f.batchSizes.clear();

    ComposerProxyService second = f.replica();
    MavenResponse warm = f.get(second, false);
    assertArrayEquals(expected, warm.body().readAllBytes());
    assertEquals(first.etag(), warm.etag());
    assertEquals(first.lastModified(), warm.lastModified());
    MavenResponse head = f.get(second, true);
    assertEquals(first.etag(), head.etag());
    assertEquals(first.lastModified(), head.lastModified());
    assertTrue(f.writes.isEmpty(), "cached GET/HEAD must not create writes or cache invalidations");
    assertEquals(List.of(256, 256, 88, 256, 256, 88), f.batchSizes);
    verify(f.dao, never()).findAssetByPath(anyLong(), anyString());
    verify(f.dao, never()).findBlobById(anyLong());
    verifyNoInteractions(f.fetcher);
  }

  @Test
  void refreshChangesOnlyDifferentRoutesAndKeepsRemovedVersionRoutesForOldLockFiles() throws Exception {
    Fixture f = new Fixture();
    f.metadata(List.of(version("1.0.0", "https://dist.test/a.zip"),
        version("2.0.0", "https://dist.test/b.zip")));
    f.get(f.replica(), true);
    String oldPath = f.writes.getFirst();
    f.writes.clear();

    // Equal-length URLs exercise the checksum comparison, rather than only a size check.
    f.metadata(List.of(version("2.0.0", "https://dist.test/c.zip"),
        version("3.0.0", "https://dist.test/d.zip")));
    f.get(f.replica(), true);
    assertEquals(2, f.writes.size());
    assertEquals(3, f.routes.size());
    assertTrue(f.routes.containsKey(oldPath));
    f.writes.clear();
    f.get(f.replica(), false).closeBodyIfOpen();
    assertTrue(f.writes.isEmpty());

    // Reverting metadata to a previously seen revision must restore its route, too.
    f.metadata(List.of(version("2.0.0", "https://dist.test/b.zip")));
    f.get(f.replica(), true);
    assertEquals(1, f.writes.size());
  }

  @Test
  void repairsMissingRoutesAndMissingBlobBindingsFromSharedState() throws Exception {
    Fixture f = new Fixture();
    f.metadata(List.of(version("1", "https://dist.test/a.zip"),
        version("2", "https://dist.test/b.zip"), version("3", "https://dist.test/c.zip")));
    f.get(f.replica(), true);
    List<String> paths = List.copyOf(f.writes);
    f.routes.remove(paths.get(0));
    f.blobs.remove(f.routes.get(paths.get(1)).assetBlobId());
    f.routes.put(paths.get(2), mock(AssetRecord.class));
    f.writes.clear();

    f.get(f.replica(), true);
    assertEquals(paths, f.writes);
  }

  @Test
  void retriesIncompleteRoutePublicationWithoutRewritingAlreadyPublishedRoutes() throws Exception {
    Fixture f = new Fixture();
    f.metadata(List.of(version("1", "https://dist.test/a.zip"),
        version("2", "https://dist.test/b.zip")));
    f.failAfterWrites = 1;
    assertThrows(IllegalStateException.class, () -> f.get(f.replica(), false));
    assertEquals(1, f.routes.size());
    f.failAfterWrites = Integer.MAX_VALUE;
    f.writes.clear();

    f.get(f.replica(), true);
    assertEquals(1, f.writes.size());
    assertEquals(2, f.routes.size());
  }

  @Test
  void doesNotFetchRouteRowsForSourceOnlyOrMinifiedInheritedDistEntries() throws Exception {
    Fixture f = new Fixture();
    f.metadata(List.of(Map.of("version", "1", "source", Map.of("url", "https://git.test/repo")),
        Map.of("version", "2"), Map.of("version", "3", "dist", Map.of("type", "zip"))));
    f.get(f.replica(), true);
    assertTrue(f.writes.isEmpty());
    verifyNoInteractions(f.dao);
  }

  @Test
  void routeSerializationFailureIsPropagatedBeforePublishingTheBatch() throws Exception {
    Fixture f = new Fixture();
    f.metadata(List.of(version("1", "https://dist.test/a.zip")));
    ObjectMapper failing = spy(JSON);
    var failure = new com.fasterxml.jackson.core.JsonProcessingException("route serialization failed") {};
    doThrow(failure).when(failing).writeValueAsBytes(any(ComposerProxyService.Route.class));
    ComposerProxyService service = new ComposerProxyService(failing, f.dao, f.assets, f.fetcher,
        mock(ProxyNegativeCache.class), mock(ProxyStateDao.class), mock(RawProxyService.class));
    var thrown = assertThrows(IllegalStateException.class, () -> f.get(service, false));
    assertEquals(failure, thrown.getCause());
    assertTrue(f.writes.isEmpty());
  }

  private static Map<String, Object> version(String version, String url) {
    return Map.of("version", version, "dist", Map.of("url", url, "type", "zip", "shasum", ""));
  }

  private static final class Fixture {
    final AssetDao dao = mock(AssetDao.class);
    final ComposerAssetSupport assets = mock(ComposerAssetSupport.class);
    final HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    final RepositoryRuntime runtime = ComposerHostedServiceTest.runtime(
        "composer-proxy", RepositoryType.PROXY, List.of());
    final Map<String, AssetRecord> routes = new LinkedHashMap<>();
    final Map<Long, AssetBlobRecord> blobs = new LinkedHashMap<>();
    final List<String> writes = new ArrayList<>();
    final List<Integer> batchSizes = new ArrayList<>();
    long nextId = 1;
    int failAfterWrites = Integer.MAX_VALUE;

    Fixture() throws Exception {
      AssetRecord cached = mock(AssetRecord.class);
      when(cached.lastUpdatedAt()).thenReturn(Instant.now());
      when(assets.find(eq(runtime), anyString())).thenReturn(Optional.of(cached));
      when(assets.readBytes(runtime, "packages.json")).thenReturn(JSON.writeValueAsBytes(
          Map.of("metadata-url", "https://upstream.test/p2/%package%.json")));
      when(dao.findAssetsByPaths(eq(runtime.id()), anyCollection())).thenAnswer(call -> {
        Collection<String> paths = call.getArgument(1);
        batchSizes.add(paths.size());
        Map<String, AssetRecord> found = new LinkedHashMap<>();
        paths.forEach(path -> { if (routes.containsKey(path)) found.put(path, routes.get(path)); });
        return Map.copyOf(found);
      });
      when(dao.findBlobsByIds(anyCollection())).thenAnswer(call -> {
        Collection<Long> ids = call.getArgument(0);
        assertTrue(ids.size() <= 256, "blob reads must also be bounded");
        Map<Long, AssetBlobRecord> found = new LinkedHashMap<>();
        ids.forEach(id -> { if (blobs.containsKey(id)) found.put(id, blobs.get(id)); });
        return Map.copyOf(found);
      });
      doAnswer(call -> {
        if (writes.size() == failAfterWrites) throw new IllegalStateException("interrupted writer");
        String path = call.getArgument(1);
        byte[] bytes = call.getArgument(2);
        writes.add(path);
        long id = nextId++;
        AssetRecord asset = mock(AssetRecord.class);
        when(asset.assetBlobId()).thenReturn(id);
        AssetBlobRecord blob = mock(AssetBlobRecord.class);
        when(blob.size()).thenReturn((long) bytes.length);
        when(blob.sha256()).thenReturn(ComposerResponses.sha256(bytes));
        routes.put(path, asset);
        blobs.put(id, blob);
        return null;
      }).when(assets).storeBytes(eq(runtime), anyString(), any(), eq(ComposerResponses.JSON), any());
    }

    void metadata(List<Map<String, Object>> versions) throws Exception {
      when(assets.readBytes(runtime, "p2/company/example.json")).thenReturn(JSON.writeValueAsBytes(
          Map.of("minified", "composer/2.0", "packages", Map.of("company/example", versions))));
    }

    ComposerProxyService replica() {
      return new ComposerProxyService(JSON, dao, assets, fetcher, mock(ProxyNegativeCache.class),
          mock(ProxyStateDao.class), mock(RawProxyService.class));
    }

    MavenResponse get(ComposerProxyService service, boolean head) {
      return service.get(runtime, new ComposerPathParser().parse("p2/company/example.json"),
          "https://repo.test/repository/composer-proxy", null, head);
    }
  }
}
