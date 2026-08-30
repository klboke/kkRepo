package com.github.klboke.kkrepo.server.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.helm.HelmIndex;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HelmGroupServiceTest {
  @Test
  void mergesMemberIndexesInOrderAndSkipsOfflineMembers() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime offline = runtime(1L, "offline", RepositoryType.HOSTED, false, List.of());
    RepositoryRuntime first = runtime(2L, "first", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime second = runtime(3L, "second", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "helm-all", RepositoryType.GROUP, true, List.of(offline, first, second));
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.hosted.get(first, "index.yaml", false)).thenReturn(index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              appVersion: private
              urls: [private/demo.tgz]
        """));
    when(fixture.proxy.get(second, "index.yaml", false)).thenReturn(index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              appVersion: public
              urls: [demo-1.0.0.tgz]
            - name: demo
              version: 2.0.0
              urls: [charts/current.tgz]
        """));

    MavenResponse response = fixture.service.get(group, "index.yaml", false);
    byte[] body = response.body().readAllBytes();

    assertEquals(200, response.status());
    assertEquals(List.of(
        new HelmIndex.Entry("demo", "1.0.0", List.of("demo-1.0.0.tgz")),
        new HelmIndex.Entry("demo", "2.0.0", List.of("demo-2.0.0.tgz"))),
        HelmIndex.entries(body));
    assertTrue(new String(body, StandardCharsets.UTF_8).contains("appVersion: private"));
  }

  @Test
  void resolvesNestedGroupChartsAndProvenanceThroughOrderedFallback() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "upstream", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime nested = runtime(4L, "nested", RepositoryType.GROUP, true, List.of(proxy));
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted, nested));
    MavenResponse chart = asset("digest-b");
    MavenResponse provenance = MavenResponse.noBody(200);
    when(fixture.memberCache.getIfCurrent(any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> index("entries: {}\n"));
    when(fixture.proxy.get(proxy, "index.yaml", false)).thenAnswer(ignored -> freshProxyIndex("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: digest-b
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz.prov", false))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing"));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false)).thenReturn(chart);
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz.prov", false)).thenReturn(provenance);

    MavenResponse chartHead = fixture.service.get(group, "demo-1.0.0.tgz", true);
    MavenResponse provenanceHead = fixture.service.get(group, "demo-1.0.0.tgz.prov", true);

    assertEquals(200, chartHead.status());
    assertFalse(chartHead.hasBody());
    assertEquals(200, provenanceHead.status());
    assertFalse(provenanceHead.hasBody());

    verify(fixture.memberCache).putIfCurrent(
        nested, "demo-1.0.0.tgz", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(nested));
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, nested.id(), winnerGeneration(group));
    verify(fixture.memberCache).putIfCurrent(
        nested, "demo-1.0.0.tgz.prov", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(nested));
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz.prov", NexusCacheType.CONTENT, nested.id(), winnerGeneration(group));
  }

  @Test
  void servesFreshDurableGroupIndexWithoutFanOut() {
    Fixture fixture = fixture();
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of());
    CachedAssetMetadata cached = mock(CachedAssetMetadata.class);
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cached));
    when(fixture.reader.serveSnapshot(cached, false, "index.yaml"))
        .thenAnswer(ignored -> index("entries: {}\n"));

    MavenResponse response = fixture.service.get(group, "index.yaml", true);

    assertEquals(200, response.status());
    assertEquals("text/x-yaml", response.contentType());
    verify(fixture.reader).serveSnapshot(cached, false, "index.yaml");
  }

  @Test
  void rebuildsFreshDurableIndexWhenItsBlobCannotBeOpened() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    CachedAssetMetadata cached = mock(CachedAssetMetadata.class);
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cached));
    when(fixture.reader.serveSnapshot(cached, false, "index.yaml"))
        .thenReturn(MavenResponse.ok(
            () -> {
              throw new MavenExceptions.MavenNotFoundException("missing cached blob");
            },
            1L,
            HelmIndex.CONTENT_TYPE,
            "old",
            Instant.EPOCH));
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          private:
            - name: private
              version: 1.0.0
              urls: [private.tgz]
        """));

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("private", "1.0.0", List.of("private-1.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.indexCache).invalidateGroupAfterCommit(group.id());
  }

  @Test
  void persistsMergedIndexWithSharedFreshnessWatermark() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    NexusLikeCacheInfo cacheInfo = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:00Z"), "token", NexusCacheType.METADATA);
    Map<String, Object> attributes = Map.of("helmGroupIndex", true);
    HelmAssetWriter.Stored stored = storedIndex();
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any())).thenReturn(cacheInfo);
    when(fixture.indexCache.freshAttributes(
        group, cacheInfo, null, "member-generation")).thenReturn(attributes);
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          private:
            - name: private
              version: 1.0.0
              urls: [private.tgz]
        """));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.writeBytes(
        eq(group), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq(HelmIndex.CONTENT_TYPE), eq(com.github.klboke.kkrepo.protocol.helm.HelmAssetKind.INDEX),
        isNull(), eq(attributes), anyMap(), eq("group"), isNull()))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(200, response.status());
    assertEquals(
        List.of(new HelmIndex.Entry("private", "1.0.0", List.of("private-1.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.reader).beforeRead(
        stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
  }

  @Test
  void retriesCollectionWhenTheGroupWatermarkChangesAndPublishesTheStableGeneration()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    NexusLikeCacheInfo preRefresh = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:00Z"), "pre-refresh", NexusCacheType.METADATA);
    NexusLikeCacheInfo postRefresh = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:01Z"), "post-refresh", NexusCacheType.METADATA);
    AtomicInteger memberReads = new AtomicInteger();
    Map<String, Object> attributes = Map.of("helmGroupIndex", true);
    HelmAssetWriter.Stored stored = storedIndex();
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any()))
        .thenReturn(preRefresh, postRefresh, postRefresh, postRefresh);
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored ->
        memberReads.incrementAndGet() == 1
            ? index("""
                entries:
                  private:
                    - name: private
                      version: 1.0.0
                      urls: [private-1.0.0.tgz]
                """)
            : index("""
                entries:
                  private:
                    - name: private
                      version: 2.0.0
                      urls: [private-2.0.0.tgz]
                """));
    when(fixture.indexCache.freshAttributes(
        group, postRefresh, null, "member-generation")).thenReturn(attributes);
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.writeBytes(
        eq(group), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq(HelmIndex.CONTENT_TYPE), eq(com.github.klboke.kkrepo.protocol.helm.HelmAssetKind.INDEX),
        isNull(), eq(attributes), anyMap(), eq("group"), isNull()))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("private", "2.0.0", List.of("private-2.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.hosted, times(2)).get(hosted, "index.yaml", false);
    verify(fixture.indexCache).freshAttributes(
        group, postRefresh, null, "member-generation");
  }

  @Test
  void retriesCollectionWhenAnOlderReplicaAdvancesAMemberAssetGeneration() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    NexusLikeCacheInfo cacheInfo = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:00Z"), "token", NexusCacheType.METADATA);
    AtomicInteger memberReads = new AtomicInteger();
    Map<String, Object> attributes = Map.of("helmGroupIndex", true);
    HelmAssetWriter.Stored stored = storedIndex();
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any())).thenReturn(cacheInfo);
    when(fixture.indexCache.memberAssetGeneration(group))
        .thenReturn("member-1", "member-2", "member-2", "member-2");
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored ->
        memberReads.incrementAndGet() == 1
            ? index("""
                entries:
                  private:
                    - name: private
                      version: 1.0.0
                      urls: [private-1.0.0.tgz]
                """)
            : index("""
                entries:
                  private:
                    - name: private
                      version: 2.0.0
                      urls: [private-2.0.0.tgz]
                """));
    when(fixture.indexCache.freshAttributes(group, cacheInfo, null, "member-2"))
        .thenReturn(attributes);
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.writeBytes(
        eq(group), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq(HelmIndex.CONTENT_TYPE), eq(com.github.klboke.kkrepo.protocol.helm.HelmAssetKind.INDEX),
        isNull(), eq(attributes), anyMap(), eq("group"), isNull()))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("private", "2.0.0", List.of("private-2.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.hosted, times(2)).get(hosted, "index.yaml", false);
    verify(fixture.indexCache).freshAttributes(group, cacheInfo, null, "member-2");
  }

  @Test
  void doesNotPublishAGroupIndexWhenTheWatermarkChangesAcrossTheRetry() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any())).thenReturn(
        cacheInfo("generation-1"),
        cacheInfo("generation-2"),
        cacheInfo("generation-3"),
        cacheInfo("generation-4"));
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          private:
            - name: private
              version: 1.0.0
              urls: [private.tgz]
        """));

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("private", "1.0.0", List.of("private-1.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void doesNotPublishAGroupIndexWhenTheSharedWatermarkCannotBeRead() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any()))
        .thenThrow(new IllegalStateException("watermark unavailable"));
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          private:
            - name: private
              version: 1.0.0
              urls: [private.tgz]
        """));

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("private", "1.0.0", List.of("private-1.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.indexCache, times(4)).current(eq(group), any());
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void servesCompleteMergeWhenDurableCachePersistenceFails() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    NexusLikeCacheInfo cacheInfo = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:00Z"), "token", NexusCacheType.METADATA);
    Map<String, Object> attributes = Map.of("helmGroupIndex", true);
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any())).thenReturn(cacheInfo);
    when(fixture.indexCache.freshAttributes(
        group, cacheInfo, null, "member-generation")).thenReturn(attributes);
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          private:
            - name: private
              version: 1.0.0
              urls: [private.tgz]
        """));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.writeBytes(
        eq(group), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq(HelmIndex.CONTENT_TYPE), eq(com.github.klboke.kkrepo.protocol.helm.HelmAssetKind.INDEX),
        isNull(), eq(attributes), anyMap(), eq("group"), isNull()))
        .thenThrow(new IllegalStateException("group blob store unavailable"));
    doThrow(new IllegalStateException("watermark unavailable"))
        .when(fixture.indexCache).invalidateGroupAfterCommit(group.id());

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("private", "1.0.0", List.of("private-1.0.0.tgz"))),
        HelmIndex.entries(response.body().readAllBytes()));
    verify(fixture.indexCache).invalidateGroupAfterCommit(group.id());
  }

  @Test
  void preservesTheProxyIndexAbsoluteFreshnessDeadlineInTheDurableMerge() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = runtime(2L, "upstream", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(proxy));
    NexusLikeCacheInfo cacheInfo = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:00Z"), "token", NexusCacheType.METADATA);
    Instant memberFreshUntil = Instant.parse("2026-08-30T00:00:30Z");
    Map<String, Object> attributes = Map.of(
        "helmGroupIndex", true,
        "helmGroupMemberIndexFreshUntil", memberFreshUntil.toString());
    HelmAssetWriter.Stored stored = storedIndex();
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any())).thenReturn(cacheInfo);
    when(fixture.indexCache.freshAttributes(
        group, cacheInfo, memberFreshUntil, "member-generation"))
        .thenReturn(attributes);
    when(fixture.proxy.get(proxy, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          public:
            - name: public
              version: 1.0.0
              urls: [public.tgz]
        """).withInternalAttribute(
            HelmProxyService.INDEX_FRESH_UNTIL_ATTRIBUTE, memberFreshUntil));
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.writeBytes(
        eq(group), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq(HelmIndex.CONTENT_TYPE), eq(com.github.klboke.kkrepo.protocol.helm.HelmAssetKind.INDEX),
        isNull(), eq(attributes), anyMap(), eq("group"), isNull()))
        .thenReturn(stored);

    MavenResponse response = fixture.service.get(group, "index.yaml", false);

    assertEquals(200, response.status());
    verify(fixture.indexCache).freshAttributes(
        group, cacheInfo, memberFreshUntil, "member-generation");
  }

  @Test
  void carriesACachedNestedGroupMemberDeadlineIntoTheOuterCache() {
    Fixture fixture = fixture();
    RepositoryRuntime nested = runtime(2L, "nested", RepositoryType.GROUP, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(nested));
    CachedAssetMetadata cachedNested = mock(CachedAssetMetadata.class);
    Instant memberFreshUntil = Instant.parse("2026-08-30T00:00:30Z");
    NexusLikeCacheInfo cacheInfo = new NexusLikeCacheInfo(
        Instant.parse("2026-08-30T00:00:00Z"), "token", NexusCacheType.METADATA);
    Map<String, Object> attributes = Map.of("helmGroupIndex", true);
    HelmAssetWriter.Stored stored = storedIndex();
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.empty());
    when(fixture.indexCache.findFresh(eq(nested), any())).thenReturn(Optional.of(cachedNested));
    when(fixture.indexCache.memberIndexFreshUntil(cachedNested)).thenReturn(memberFreshUntil);
    when(fixture.reader.serveSnapshot(cachedNested, false, "index.yaml"))
        .thenAnswer(ignored -> index("entries: {}\n"));
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.indexCache.current(eq(group), any())).thenReturn(cacheInfo);
    when(fixture.indexCache.freshAttributes(
        group, cacheInfo, memberFreshUntil, "member-generation"))
        .thenReturn(attributes);
    when(fixture.registry.forBlobStoreId(7L)).thenReturn(fixture.storage);
    when(fixture.writer.writeBytes(
        eq(group), eq(fixture.storage), eq(7L), eq("index.yaml"), any(byte[].class),
        eq(HelmIndex.CONTENT_TYPE), eq(com.github.klboke.kkrepo.protocol.helm.HelmAssetKind.INDEX),
        isNull(), eq(attributes), anyMap(), eq("group"), isNull()))
        .thenReturn(stored);

    assertEquals(200, fixture.service.get(group, "index.yaml", false).status());
    verify(fixture.indexCache).freshAttributes(
        group, cacheInfo, memberFreshUntil, "member-generation");
  }

  @Test
  void evictsDigestMismatchedCachedWinnerAndFallsBackInConfiguredOrder() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "upstream", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted, proxy));
    MavenResponse expected = asset("same");
    when(fixture.memberCache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, winnerGeneration(group)))
        .thenReturn(Optional.of(hosted.id()));
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: same
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.proxy.get(proxy, "index.yaml", false)).thenAnswer(ignored -> freshProxyIndex("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: same
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false))
        .thenReturn(asset("different"));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
    verify(fixture.memberCache).evict(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT);
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(group));
  }

  @Test
  void usesCachedWinnerWithoutRepeatingMemberFanOut() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "upstream", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted, proxy));
    MavenResponse expected = asset("proxy");
    when(fixture.memberCache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, winnerGeneration(group)))
        .thenReturn(Optional.of(proxy.id()));
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> index("entries: {}\n"));
    when(fixture.proxy.get(proxy, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: proxy
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false)).thenReturn(expected);

    MavenResponse response = fixture.service.get(group, "demo-1.0.0.tgz", true);

    assertEquals(200, response.status());
    assertFalse(response.hasBody());
    verify(fixture.proxy).get(proxy, "demo-1.0.0.tgz", false);
    verify(fixture.proxy, never()).get(proxy, "demo-1.0.0.tgz", true);
    verify(fixture.hosted, never()).get(hosted, "demo-1.0.0.tgz", false);
  }

  @Test
  void rechecksTheExactSourceGenerationBeforeUsingACachedWinner() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "upstream", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted, proxy));
    MavenResponse expected = asset("same");
    GroupMemberAssetCache.Generation captured = new GroupMemberAssetCache.Generation(
        group.id(), NexusCacheType.CONTENT, "winner-generation", "before");
    when(fixture.indexCache.winnerAssetGeneration(group, "demo-1.0.0.tgz"))
        .thenReturn("before", "after");
    when(fixture.memberCache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, captured))
        .thenReturn(Optional.of(proxy.id()));
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("same"));
    when(fixture.proxy.get(proxy, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("same"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));

    verify(fixture.indexCache, times(2))
        .winnerAssetGeneration(group, "demo-1.0.0.tgz");
    verify(fixture.memberCache, never()).getIfCurrent(any(), any(), any(), any());
    verify(fixture.proxy, never()).get(proxy, "demo-1.0.0.tgz", false);
    verify(fixture.memberCache, never()).putIfCurrent(
        any(), any(), any(), any(Long.class), any());
  }

  @Test
  void rechecksTheExactSourceGenerationBeforePublishingAWinner() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "private", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    MavenResponse expected = asset("same");
    when(fixture.indexCache.winnerAssetGeneration(group, "demo-1.0.0.tgz"))
        .thenReturn("stable", "stable", "changed");
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("same"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));

    verify(fixture.indexCache, times(3))
        .winnerAssetGeneration(group, "demo-1.0.0.tgz");
    verify(fixture.memberCache, never()).putIfCurrent(
        any(), any(), any(), any(Long.class), any());
  }

  @Test
  void bindsChartAndProvenanceDownloadsToTheReleaseSelectedByTheMergedIndex() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "higher-priority", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "index-winner", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of(hosted, proxy));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    MavenResponse chart = asset("digest-b");
    MavenResponse provenance = MavenResponse.noBody(200);
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml")).thenAnswer(ignored -> index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: digest-b
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.hosted.get(hosted, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: digest-a
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.proxy.get(proxy, "index.yaml", false)).thenAnswer(ignored -> index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: digest-b
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz.prov", false))
        .thenReturn(MavenResponse.noBody(200));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false)).thenReturn(chart);
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz.prov", false)).thenReturn(provenance);

    assertSame(chart, fixture.service.get(group, "demo-1.0.0.tgz", false));
    assertSame(provenance, fixture.service.get(group, "demo-1.0.0.tgz.prov", false));

    verify(fixture.hosted, never()).get(hosted, "demo-1.0.0.tgz", false);
    verify(fixture.hosted, never()).get(hosted, "demo-1.0.0.tgz.prov", false);
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(group));
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz.prov", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(group));
  }

  @Test
  void fallsBackOnlyBetweenMembersAdvertisingTheSelectedRelease() {
    Fixture fixture = fixture();
    RepositoryRuntime unavailable = runtime(
        2L, "unavailable", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime fallback = runtime(
        3L, "fallback", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(unavailable, fallback));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    AtomicBoolean missingBodyOpened = new AtomicBoolean();
    MavenResponse expected = asset("digest-b");
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(unavailable, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.proxy.get(fallback, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(unavailable, "demo-1.0.0.tgz", false))
        .thenReturn(MavenResponse.ok(
            () -> {
              missingBodyOpened.set(true);
              throw new MavenExceptions.MavenNotFoundException("missing member blob");
            },
            42L,
            "application/gzip",
            "missing",
            Instant.EPOCH)
            .withInternalAttribute(HelmAssetReader.SHA256_ATTRIBUTE, "digest-b"));
    when(fixture.proxy.get(fallback, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
    assertTrue(missingBodyOpened.get());
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, fallback.id(), winnerGeneration(group));
  }

  @Test
  void doesNotCacheWinnerSelectedFromAPartialMemberIndex() {
    Fixture fixture = fixture();
    RepositoryRuntime unavailable = runtime(
        2L, "unavailable", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime fallback = runtime(
        3L, "fallback", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(unavailable, fallback));
    MavenResponse expected = asset(null);
    when(fixture.hosted.get(unavailable, "index.yaml", false))
        .thenThrow(new MavenExceptions.BadUpstreamException("temporarily unavailable"));
    when(fixture.proxy.get(fallback, "index.yaml", false)).thenAnswer(ignored -> freshProxyIndex("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              urls: [demo-1.0.0.tgz]
        """));
    when(fixture.proxy.get(fallback, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));

    verify(fixture.memberCache, never()).putIfCurrent(
        eq(group), eq("demo-1.0.0.tgz"), eq(NexusCacheType.CONTENT),
        anyLong(), any(GroupMemberAssetCache.Generation.class));
  }

  @Test
  void evictsALazyMissingCachedWinnerBeforeFallingBack() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "cached", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "fallback", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted, proxy));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    MavenResponse expected = asset("digest-b");
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.memberCache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, winnerGeneration(group)))
        .thenReturn(Optional.of(hosted.id()));
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.proxy.get(proxy, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false))
        .thenReturn(MavenResponse.ok(
            () -> {
              throw new IllegalStateException("object store unavailable");
            },
            42L,
            "application/gzip",
            "missing",
            Instant.EPOCH)
            .withInternalAttribute(HelmAssetReader.SHA256_ATTRIBUTE, "digest-b"));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
    verify(fixture.memberCache).evict(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT);
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(group));
  }

  @Test
  void headProbesLazyCandidatesBeforeCachingTheWinner() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "cached", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime proxy = runtime(3L, "fallback", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted, proxy));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    AtomicBoolean fallbackBodyOpened = new AtomicBoolean();
    AtomicBoolean fallbackBodyClosed = new AtomicBoolean();
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.memberCache.getIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, winnerGeneration(group)))
        .thenReturn(Optional.of(hosted.id()));
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.proxy.get(proxy, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false))
        .thenReturn(MavenResponse.ok(
            () -> {
              throw new IllegalStateException("object store unavailable");
            },
            42L,
            "application/gzip",
            "missing",
            Instant.EPOCH)
            .withInternalAttribute(HelmAssetReader.SHA256_ATTRIBUTE, "digest-b"));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false))
        .thenReturn(MavenResponse.ok(
            () -> {
              fallbackBodyOpened.set(true);
              return new ByteArrayInputStream(new byte[] {1}) {
                @Override
                public void close() throws IOException {
                  fallbackBodyClosed.set(true);
                  super.close();
                }
              };
            },
            1L,
            "application/gzip",
            "fallback",
            Instant.EPOCH)
            .withHeader("X-Helm-Test", "fallback")
            .withInternalAttribute(HelmAssetReader.SHA256_ATTRIBUTE, "digest-b"));

    MavenResponse response = fixture.service.get(group, "demo-1.0.0.tgz", true);

    assertEquals(200, response.status());
    assertFalse(response.hasBody());
    assertEquals(1L, response.contentLength());
    assertEquals("fallback", response.etag());
    assertEquals("fallback", response.headers().get("X-Helm-Test"));
    assertTrue(fallbackBodyOpened.get());
    assertTrue(fallbackBodyClosed.get());
    verify(fixture.hosted).get(hosted, "demo-1.0.0.tgz", false);
    verify(fixture.hosted, never()).get(hosted, "demo-1.0.0.tgz", true);
    verify(fixture.proxy).get(proxy, "demo-1.0.0.tgz", false);
    verify(fixture.proxy, never()).get(proxy, "demo-1.0.0.tgz", true);
    verify(fixture.memberCache).evict(group, "demo-1.0.0.tgz", NexusCacheType.CONTENT);
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(group));
  }

  @Test
  void rejectsOverwrittenChartUntilItsAdvertisedDigestIsRebuilt() {
    Fixture fixture = fixture();
    RepositoryRuntime overwritten = runtime(
        2L, "overwritten", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime fallback = runtime(
        3L, "fallback", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(overwritten, fallback));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    AtomicBoolean mismatchedBodyClosed = new AtomicBoolean();
    MavenResponse mismatched = MavenResponse.ok(
        new ByteArrayInputStream(new byte[] {1}) {
          @Override
          public void close() throws IOException {
            mismatchedBodyClosed.set(true);
            super.close();
          }
        },
        1L,
        "application/gzip",
        null,
        Instant.EPOCH)
        .withInternalAttribute(HelmAssetReader.SHA256_ATTRIBUTE, "new-digest");
    MavenResponse expected = asset("sha256:OLD-DIGEST");
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("old-digest"));
    when(fixture.hosted.get(overwritten, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("old-digest"));
    when(fixture.proxy.get(fallback, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("old-digest"));
    when(fixture.hosted.get(overwritten, "demo-1.0.0.tgz", false))
        .thenReturn(mismatched);
    when(fixture.proxy.get(fallback, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
    assertTrue(mismatchedBodyClosed.get());
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, fallback.id(), winnerGeneration(group));
  }

  @Test
  void preservesDirectProxyBehaviorWhenUpstreamIndexDigestDisagreesWithReleaseAsset() {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = runtime(
        2L, "upstream", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(proxy));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    MavenResponse expected = asset("actual-upstream-digest");
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("advertised-upstream-digest"));
    when(fixture.proxy.get(proxy, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("advertised-upstream-digest"));
    when(fixture.proxy.get(proxy, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, proxy.id(), winnerGeneration(group));
  }

  @Test
  void rejectsNestedGroupBytesWhenItsWinnerChangesAfterTheOuterReleaseSelection() {
    Fixture fixture = fixture();
    RepositoryRuntime nestedHosted = runtime(
        2L, "nested-hosted", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime nested = runtime(
        3L, "nested", RepositoryType.GROUP, true, List.of(nestedHosted));
    RepositoryRuntime fallback = runtime(
        4L, "fallback", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(nested, fallback));
    AtomicInteger nestedIndexReads = new AtomicInteger();
    MavenResponse changedNestedBytes = asset("digest-two");
    MavenResponse expected = asset("digest-one");
    when(fixture.memberCache.getIfCurrent(any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(fixture.hosted.get(nestedHosted, "index.yaml", false)).thenAnswer(ignored ->
        nestedIndexReads.incrementAndGet() <= 2
            ? selectedIndex("digest-one")
            : selectedIndex("digest-two"));
    when(fixture.proxy.get(fallback, "index.yaml", false))
        .thenAnswer(ignored -> freshProxyIndex("""
            entries:
              demo:
                - name: demo
                  version: 1.0.0
                  digest: digest-one
                  urls: [demo-1.0.0.tgz]
            """));
    when(fixture.hosted.get(nestedHosted, "demo-1.0.0.tgz", false))
        .thenReturn(changedNestedBytes);
    when(fixture.proxy.get(fallback, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
    assertEquals(4, nestedIndexReads.get());
    verify(fixture.memberCache).putIfCurrent(
        group, "demo-1.0.0.tgz", NexusCacheType.CONTENT, fallback.id(), winnerGeneration(group));
  }

  @Test
  void rejectsAdvertisedReleaseWhenMemberDigestCannotBeVerifiedWithoutOptionalCache() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "hosted", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    HelmGroupService withoutMemberCache = new HelmGroupService(
        fixture.hosted, fixture.proxy, fixture.indexCache, null,
        fixture.registry, fixture.writer, fixture.reader);
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false))
        .thenReturn(MavenResponse.noBody(200));

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> withoutMemberCache.get(group, "demo-1.0.0.tgz", false));
  }

  @Test
  void rebuildsUnreadableAndInvalidCachedGroupIndexes() throws Exception {
    Fixture unreadable = fixture();
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of());
    CachedAssetMetadata unreadableIndex = mock(CachedAssetMetadata.class);
    when(unreadable.indexCache.findFresh(eq(group), any()))
        .thenReturn(Optional.of(unreadableIndex));
    when(unreadable.reader.serveSnapshot(unreadableIndex, false, "index.yaml"))
        .thenReturn(response(new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("unreadable group index");
          }
        }));

    assertEquals(
        List.of(),
        HelmIndex.entries(unreadable.service.get(group, "index.yaml", false).body().readAllBytes()));
    verify(unreadable.indexCache).invalidateGroupAfterCommit(group.id());

    Fixture invalid = fixture();
    CachedAssetMetadata invalidIndex = mock(CachedAssetMetadata.class);
    when(invalid.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(invalidIndex));
    when(invalid.reader.serveSnapshot(invalidIndex, false, "index.yaml"))
        .thenAnswer(ignored -> index("entries: ["));

    assertEquals(
        List.of(),
        HelmIndex.entries(invalid.service.get(group, "index.yaml", false).body().readAllBytes()));
    verify(invalid.indexCache).invalidateGroupAfterCommit(group.id());
  }

  @Test
  void skipsUnreadableAndInvalidMemberIndexesDuringBoundResolution() {
    Fixture fixture = fixture();
    RepositoryRuntime unreadable = runtime(
        1L, "unreadable", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime invalid = runtime(
        2L, "invalid", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime good = runtime(3L, "good", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(unreadable, invalid, good));
    CachedAssetMetadata cachedIndex = mock(CachedAssetMetadata.class);
    MavenResponse expected = asset("digest-b");
    when(fixture.indexCache.findFresh(eq(group), any())).thenReturn(Optional.of(cachedIndex));
    when(fixture.reader.serveSnapshot(cachedIndex, false, "index.yaml"))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.hosted.get(unreadable, "index.yaml", false))
        .thenReturn(response(new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("unreadable member index");
          }
        }));
    when(fixture.hosted.get(invalid, "index.yaml", false))
        .thenAnswer(ignored -> index("entries: ["));
    when(fixture.proxy.get(good, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-b"));
    when(fixture.proxy.get(good, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));
  }

  @Test
  void isolatesMissingUnreadableAndInvalidMemberIndexesWithoutCachingPartialMerge()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime missing = runtime(1L, "missing", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime unreadable = runtime(2L, "unreadable", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime invalid = runtime(3L, "invalid", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime schemaInvalid = runtime(
        4L, "schema-invalid", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime semanticVersionInvalid = runtime(
        8L, "semver-invalid", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime lazyMissing = runtime(
        5L, "lazy-missing", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime closeFailure = runtime(
        6L, "close-failure", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime good = runtime(7L, "good", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true,
        List.of(
            missing,
            unreadable,
            invalid,
            schemaInvalid,
            semanticVersionInvalid,
            lazyMissing,
            closeFailure,
            good));
    when(fixture.hosted.get(missing, "index.yaml", false))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing"));
    when(fixture.hosted.get(unreadable, "index.yaml", false))
        .thenReturn(response(new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("unreadable");
          }
        }));
    when(fixture.hosted.get(invalid, "index.yaml", false)).thenReturn(index("entries: ["));
    when(fixture.hosted.get(schemaInvalid, "index.yaml", false))
        .thenReturn(index("entries: {demo: [error]}\n"));
    when(fixture.hosted.get(semanticVersionInvalid, "index.yaml", false)).thenReturn(index("""
        entries:
          demo:
            - name: demo
              version: latest
              urls: [demo-latest.tgz]
        """));
    when(fixture.hosted.get(lazyMissing, "index.yaml", false))
        .thenReturn(MavenResponse.ok(
            () -> {
              throw new MavenExceptions.MavenNotFoundException("missing blob");
            },
            1L,
            HelmIndex.CONTENT_TYPE,
            null,
            Instant.EPOCH));
    byte[] valid = "entries: {}\n".getBytes(StandardCharsets.UTF_8);
    when(fixture.hosted.get(closeFailure, "index.yaml", false))
        .thenReturn(response(new ByteArrayInputStream(valid) {
          @Override
          public void close() throws IOException {
            throw new IOException("close failed");
          }
        }));
    when(fixture.hosted.get(good, "index.yaml", false)).thenReturn(index("""
        entries:
          good:
            - name: good
              version: 1.0.0
              urls: [good.tgz]
        """));
    when(fixture.indexCache.enabled()).thenReturn(true);

    MavenResponse merged = fixture.service.get(group, "index.yaml", false);
    assertEquals(
        List.of(new HelmIndex.Entry("good", "1.0.0", List.of("good-1.0.0.tgz"))),
        HelmIndex.entries(merged.body().readAllBytes()));
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void doesNotCacheAnOuterIndexWhenNestedAggregationIsDegraded() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime outerHosted = runtime(
        1L, "outer-hosted", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime nestedHosted = runtime(
        2L, "nested-hosted", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime unavailable = runtime(
        3L, "unavailable", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime nested = runtime(
        4L, "nested", RepositoryType.GROUP, true, List.of(nestedHosted, unavailable));
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(outerHosted, nested));
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.hosted.get(outerHosted, "index.yaml", false)).thenReturn(index("""
        entries:
          outer:
            - name: outer
              version: 1.0.0
              urls: [outer.tgz]
        """));
    when(fixture.hosted.get(nestedHosted, "index.yaml", false)).thenReturn(index("""
        entries:
          nested:
            - name: nested
              version: 1.0.0
              urls: [nested.tgz]
        """));
    when(fixture.proxy.get(unavailable, "index.yaml", false))
        .thenThrow(new MavenExceptions.BadUpstreamException("temporarily unavailable"));

    MavenResponse merged = fixture.service.get(group, "index.yaml", false);

    assertEquals(List.of(
        new HelmIndex.Entry("outer", "1.0.0", List.of("outer-1.0.0.tgz")),
        new HelmIndex.Entry("nested", "1.0.0", List.of("nested-1.0.0.tgz"))),
        HelmIndex.entries(merged.body().readAllBytes()));
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void doesNotCacheAHealthySubsetWhileAProxyIndexIsMissing() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime healthy = runtime(
        1L, "healthy", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime missing = runtime(
        2L, "missing", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(healthy, missing));
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.hosted.get(healthy, "index.yaml", false)).thenReturn(index("""
        entries:
          healthy:
            - name: healthy
              version: 1.0.0
              urls: [healthy.tgz]
        """));
    when(fixture.proxy.get(missing, "index.yaml", false))
        .thenThrow(new MavenExceptions.MavenNotFoundException("negative cached"));

    MavenResponse merged = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("healthy", "1.0.0", List.of("healthy-1.0.0.tgz"))),
        HelmIndex.entries(merged.body().readAllBytes()));
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void isolatesHostedIndexGenerationFailuresWithoutCachingTheHealthySubset() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime unavailable = runtime(
        1L, "unavailable", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime healthy = runtime(
        2L, "healthy", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(unavailable, healthy));
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.hosted.get(unavailable, "index.yaml", false))
        .thenThrow(new IllegalStateException("member blob store unavailable"));
    when(fixture.hosted.get(healthy, "index.yaml", false)).thenReturn(index("""
        entries:
          healthy:
            - name: healthy
              version: 1.0.0
              urls: [healthy.tgz]
        """));

    MavenResponse merged = fixture.service.get(group, "index.yaml", false);

    assertEquals(
        List.of(new HelmIndex.Entry("healthy", "1.0.0", List.of("healthy-1.0.0.tgz"))),
        HelmIndex.entries(merged.body().readAllBytes()));
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void doesNotCacheAGroupIndexBuiltFromAStaleProxyFallback() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime healthy = runtime(
        1L, "healthy", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime stale = runtime(
        2L, "stale", RepositoryType.PROXY, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(healthy, stale));
    when(fixture.indexCache.enabled()).thenReturn(true);
    when(fixture.hosted.get(healthy, "index.yaml", false)).thenReturn(index("""
        entries:
          healthy:
            - name: healthy
              version: 1.0.0
              urls: [healthy.tgz]
        """));
    when(fixture.proxy.get(stale, "index.yaml", false)).thenReturn(index("""
        entries:
          stale:
            - name: stale
              version: 1.0.0
              urls: [stale.tgz]
        """).withInternalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE, false));

    MavenResponse merged = fixture.service.get(group, "index.yaml", false);

    assertEquals(List.of(
        new HelmIndex.Entry("healthy", "1.0.0", List.of("healthy-1.0.0.tgz")),
        new HelmIndex.Entry("stale", "1.0.0", List.of("stale-1.0.0.tgz"))),
        HelmIndex.entries(merged.body().readAllBytes()));
    verify(fixture.writer, never()).writeBytes(
        any(), any(), any(Long.class), any(), any(), any(), any(), any(), anyMap(), anyMap(),
        any(), any());
  }

  @Test
  void protectsIndexAndAssetTraversalFromCyclicRuntimeSnapshots() throws Exception {
    Fixture fixture = fixture();
    List<RepositoryRuntime> firstMembers = new ArrayList<>();
    RepositoryRuntime first = runtime(10L, "first", RepositoryType.GROUP, true, firstMembers);
    RepositoryRuntime second = runtime(20L, "second", RepositoryType.GROUP, true, List.of(first));
    firstMembers.add(second);

    MavenResponse index = fixture.service.get(first, "index.yaml", false);
    assertEquals(List.of(), HelmIndex.entries(index.body().readAllBytes()));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> fixture.service.get(first, "demo-1.0.0.tgz", false));
  }

  @Test
  void servesDynamicHeadAndRejectsMissingGroupBlobStore() {
    Fixture fixture = fixture();
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of());
    assertEquals(200, fixture.service.get(group, "index.yaml", true).status());

    RepositoryRuntime noBlobStore = new RepositoryRuntime(
        11L, "no-blob", RepositoryFormat.HELM, RepositoryType.GROUP, "helm-group", true, null,
        null, null, null, true, null, 60, 60, true, null, List.of());
    when(fixture.indexCache.enabled()).thenReturn(true);
    assertThrows(IllegalStateException.class,
        () -> fixture.service.get(noBlobStore, "index.yaml", false));
  }

  @Test
  void remainsCorrectWithoutOptionalMemberWinnerCache() {
    Fixture fixture = fixture();
    RepositoryRuntime group = runtime(10L, "all", RepositoryType.GROUP, true, List.of());
    HelmGroupService withoutMemberCache = new HelmGroupService(
        fixture.hosted, fixture.proxy, fixture.indexCache, null,
        fixture.registry, fixture.writer, fixture.reader);

    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> withoutMemberCache.get(group, "missing-1.0.0.tgz", false));
  }

  @Test
  void servesAssetsWithoutOptionalMemberWinnerCache() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "hosted", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    HelmGroupService withoutMemberCache = new HelmGroupService(
        fixture.hosted, fixture.proxy, fixture.indexCache, null,
        fixture.registry, fixture.writer, fixture.reader);
    MavenResponse expected = asset("digest-a");
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-a"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, withoutMemberCache.get(group, "demo-1.0.0.tgz", false));
  }

  @Test
  void disablesWinnerCachingWhenTheDurableMemberSourceGenerationReadFails() {
    Fixture fixture = fixture();
    RepositoryRuntime hosted = runtime(2L, "hosted", RepositoryType.HOSTED, true, List.of());
    RepositoryRuntime group = runtime(
        10L, "all", RepositoryType.GROUP, true, List.of(hosted));
    MavenResponse expected = asset("digest-a");
    when(fixture.indexCache.winnerAssetGeneration(group, "demo-1.0.0.tgz"))
        .thenThrow(new IllegalStateException("asset binding unavailable"));
    when(fixture.hosted.get(hosted, "index.yaml", false))
        .thenAnswer(ignored -> selectedIndex("digest-a"));
    when(fixture.hosted.get(hosted, "demo-1.0.0.tgz", false)).thenReturn(expected);

    assertSame(expected, fixture.service.get(group, "demo-1.0.0.tgz", false));

    verify(fixture.memberCache, never()).getIfCurrent(any(), any(), any(), any());
    verify(fixture.memberCache, never()).putIfCurrent(
        any(), any(), any(), any(Long.class), any());
  }

  @Test
  void rejectsInputAndSerializedIndexesBeyondTheAggregationLimit() {
    MavenResponse response = MavenResponse.noBody(200);
    MavenExceptions.BadUpstreamException error = assertThrows(
        MavenExceptions.BadUpstreamException.class,
        () -> HelmGroupService.readBounded(response, -1));
    assertEquals("Helm group index exceeds the 64 MiB aggregation limit", error.getMessage());

    HelmGroupService.ensureIndexWithinLimit(HelmGroupService.MAX_AGGREGATED_INDEX_BYTES);
    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> HelmGroupService.ensureIndexWithinLimit(
            (long) HelmGroupService.MAX_AGGREGATED_INDEX_BYTES + 1));
  }

  @Test
  void rejectsNonGroupAndUnknownPaths() {
    Fixture fixture = fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> fixture.service.get(
        runtime(1L, "hosted", RepositoryType.HOSTED, true, List.of()), "index.yaml", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> fixture.service.get(
        runtime(2L, "group", RepositoryType.GROUP, true, List.of()), "README.md", false));
  }

  private static Fixture fixture() {
    HelmHostedService hosted = mock(HelmHostedService.class);
    HelmProxyService proxy = mock(HelmProxyService.class);
    HelmGroupIndexCache indexCache = mock(HelmGroupIndexCache.class);
    GroupMemberAssetCache memberCache = mock(GroupMemberAssetCache.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    HelmAssetWriter writer = mock(HelmAssetWriter.class);
    HelmAssetReader reader = mock(HelmAssetReader.class);
    BlobStorage storage = mock(BlobStorage.class);
    when(proxy.getIndexForGroup(any(), eq(false))).thenAnswer(invocation ->
        proxy.get(
            invocation.getArgument(0), HelmHostedService.INDEX_PATH, false));
    when(indexCache.enabled()).thenReturn(false);
    when(indexCache.memberAssetGeneration(any())).thenReturn("member-generation");
    when(indexCache.winnerAssetGeneration(any(), any())).thenReturn("member-generation");
    when(memberCache.captureGeneration(any(), any())).thenAnswer(invocation -> {
      RepositoryRuntime repository = invocation.getArgument(0);
      NexusCacheType cacheType = invocation.getArgument(1);
      return Optional.of(new GroupMemberAssetCache.Generation(
          repository.id(), cacheType, "winner-generation"));
    });
    return new Fixture(
        hosted,
        proxy,
        indexCache,
        memberCache,
        registry,
        writer,
        reader,
        storage,
        new HelmGroupService(hosted, proxy, indexCache, memberCache, registry, writer, reader));
  }

  private static GroupMemberAssetCache.Generation winnerGeneration(
      RepositoryRuntime repository) {
    return new GroupMemberAssetCache.Generation(
        repository.id(), NexusCacheType.CONTENT, "winner-generation", "member-generation");
  }

  private static HelmAssetWriter.Stored storedIndex() {
    AssetRecord asset = new AssetRecord(
        1L, 10L, null, 2L, RepositoryFormat.HELM, "index.yaml", null,
        "index.yaml", "INDEX", HelmIndex.CONTENT_TYPE, 4L,
        null, Instant.now(), Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        2L, 7L, "blob://bucket/index", null, "index", null,
        "sha1", "sha256", "md5", 4L, HelmIndex.CONTENT_TYPE, "group", null,
        Instant.EPOCH, Instant.EPOCH, Map.of());
    return new HelmAssetWriter.Stored(
        asset, blob, new HelmAssetWriter.Digests("md5", "sha1", "sha256", "sha512", 4L),
        true, null);
  }

  private static NexusLikeCacheInfo cacheInfo(String token) {
    return new NexusLikeCacheInfo(Instant.EPOCH, token, NexusCacheType.METADATA);
  }

  private static MavenResponse index(String body) {
    String document = body.stripLeading().startsWith("apiVersion:")
        ? body
        : "apiVersion: v1\n" + body;
    document = document.replaceAll(
        "(?m)^(\\s*)- name:", "$1- apiVersion: v2\n$1  name:");
    byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
    return MavenResponse.ok(
        new ByteArrayInputStream(bytes), bytes.length, HelmIndex.CONTENT_TYPE, null, Instant.EPOCH);
  }

  private static MavenResponse selectedIndex(String digest) {
    return index("""
        entries:
          demo:
            - name: demo
              version: 1.0.0
              digest: %s
              urls: [demo-1.0.0.tgz]
        """.formatted(digest));
  }

  private static MavenResponse freshProxyIndex(String body) {
    return index(body).withInternalAttribute(
        HelmProxyService.INDEX_FRESH_UNTIL_ATTRIBUTE,
        Instant.parse("2099-01-01T00:00:00Z"));
  }

  private static MavenResponse asset(String sha256) {
    return MavenResponse.noBody(200)
        .withInternalAttribute(HelmAssetReader.SHA256_ATTRIBUTE, sha256);
  }

  private static MavenResponse response(InputStream body) {
    return MavenResponse.ok(body, 0L, HelmIndex.CONTENT_TYPE, null, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      RepositoryType type,
      boolean online,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.HELM,
        type,
        "helm-" + type.name().toLowerCase(java.util.Locale.ROOT),
        online,
        7L,
        type == RepositoryType.HOSTED ? "ALLOW" : null,
        null,
        null,
        true,
        type == RepositoryType.PROXY ? "https://charts.example.test/" : null,
        60,
        60,
        true,
        null,
        members);
  }

  private record Fixture(
      HelmHostedService hosted,
      HelmProxyService proxy,
      HelmGroupIndexCache indexCache,
      GroupMemberAssetCache memberCache,
      BlobStorageRegistry registry,
      HelmAssetWriter writer,
      HelmAssetReader reader,
      BlobStorage storage,
      HelmGroupService service) {
  }
}
