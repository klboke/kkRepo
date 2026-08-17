package com.github.klboke.kkrepo.server.huggingface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.ApiCacheEntry;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.ModelFile;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.ModelRevision;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceHeaders;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProtocolCache;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HuggingFaceServiceTest {
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @Test
  void metadataKeepsRawProvenanceButRemovesClientVisibleXetHints() throws Exception {
    Fixture fixture = fixture();
    byte[] upstream = ("""
        {"sha":"%s","private":false,"gated":false,
         "siblings":[{"type":"file","path":"model.safetensors","size":4,
           "xetHash":"internal-xet-id",
           "lfs":{"oid":"%s","size":4}}],
         "url":"https://hub.example/org/model/resolve/main/config.json",
         "token":{"downloadUrl":"https://hub.example/api/models/org/model/xet-read-token/main"}}
        """).formatted(COMMIT, "a".repeat(64)).getBytes(StandardCharsets.UTF_8);
    when(fixture.registry.findApiCache(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(fixture.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200,
        Map.of(
            "Content-Type", "application/json",
            "ETag", "\"upstream-etag\"",
            "Link", "<https://hub.example/api/models/org/model/tree/main?cursor=next>; rel=\"next\"",
            "X-Xet-Hash", "must-not-be-exposed"),
        new ByteArrayInputStream(upstream)));

    List<byte[]> storedBodies = new ArrayList<>();
    doAnswer(invocation -> {
      String path = invocation.getArgument(1);
      byte[] bytes = ((java.io.InputStream) invocation.getArgument(2)).readAllBytes();
      storedBodies.add(bytes);
      boolean raw = path.contains("/raw/");
      return new RawProtocolCache.StoredAsset(
          raw ? 10L : 11L, null, raw ? 20L : 21L, sha256(bytes), bytes.length,
          "application/json");
    }).when(fixture.cache).storeHidden(
        eq(fixture.runtime), anyString(), any(), anyString(), any());
    when(fixture.registry.upsertApiCache(any())).thenAnswer(invocation -> {
      ApiCacheEntry entry = invocation.getArgument(0);
      return new ApiCacheEntry(
          30L, entry.repositoryId(), entry.route(), entry.query(), entry.requestFingerprint(),
          entry.rawAssetId(), entry.derivedAssetId(), entry.upstreamEtag(), entry.derivedEtag(),
          entry.nextLink(), entry.transformVersion(), entry.expiresAt(), entry.updatedAt());
    });
    stubRevisionProjection(fixture);
    when(fixture.cache.serve(eq(fixture.runtime), anyString(), eq(false), eq("inline")))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2,
            "application/json", null, null));

    MavenResponse response = fixture.service.get(
        fixture.runtime, "api/models/org/model", "expand=true",
        "https://repo.example/repository/hf", false);

    assertEquals(200, response.status());
    assertEquals(2, storedBodies.size());
    String raw = new String(storedBodies.get(0), StandardCharsets.UTF_8);
    String derived = new String(storedBodies.get(1), StandardCharsets.UTF_8);
    assertTrue(raw.contains("internal-xet-id"));
    assertFalse(derived.contains("internal-xet-id"));
    assertFalse(derived.contains("xet-read-token"));
    assertTrue(derived.contains(
        "https://repo.example/repository/hf/org/model/resolve/main/config.json"));
    assertFalse(response.headers().containsKey(HuggingFaceHeaders.XET_HASH));
    assertEquals(
        "<https://repo.example/repository/hf/api/models/org/model/tree/main?cursor=next>; rel=\"next\"",
        response.headers().get("Link"));
    assertEquals(sha256(storedBodies.get(1)), response.etag());
  }

  @Test
  void coldLfsFillPinsCommitValidatesFullBytesAndNeverExposesXetRouting() throws Exception {
    Fixture fixture = fixture();
    byte[] content = "model-bytes".getBytes(StandardCharsets.UTF_8);
    String linkedSha = sha256(content);
    ComponentRecord component = new HuggingFaceComponentFactory().component(
        fixture.runtime, "org/model", COMMIT, COMMIT, false, false,
        "transformers", "text-generation", "apache-2.0", Instant.now());
    ModelRevision revision = revision(component.id() == null ? 55L : component.id());
    component = new ComponentRecord(
        55L, component.repositoryId(), component.format(), component.namespace(), component.name(),
        component.version(), component.kind(), component.coordinateHash(), component.attributes(),
        component.lastUpdatedAt());
    ModelFile discovered = file("model.safetensors", null, null, null,
        HuggingFaceRegistryDao.FILE_DISCOVERED, 0L);
    ModelFile enriched = file("model.safetensors", null, linkedSha, (long) content.length,
        HuggingFaceRegistryDao.FILE_FETCHING, 9L);
    ModelFile ready = file("model.safetensors", 77L, linkedSha, (long) content.length,
        HuggingFaceRegistryDao.FILE_READY, 9L);
    when(fixture.registry.findRevision(fixture.runtime.id(), "org/model", COMMIT))
        .thenReturn(Optional.of(revision));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(55L);
    when(fixture.registry.upsertRevision(any())).thenReturn(revision);
    when(fixture.registry.findFile(
        fixture.runtime.id(), "org/model", COMMIT, "model.safetensors"))
        .thenReturn(Optional.empty(), Optional.of(ready));
    when(fixture.registry.upsertFileMetadata(any())).thenReturn(discovered, enriched);
    when(fixture.registry.markFileFetching(eq(70L), eq(9L), any())).thenReturn(true);
    when(fixture.registry.updateFetchingFileMetadata(
        eq(70L), eq(9L), any(), eq(linkedSha), any(), eq((long) content.length),
        anyString(), anyString(), any())).thenReturn(true);
    HuggingFaceLeaseManager.Lease lease = mock(HuggingFaceLeaseManager.Lease.class);
    when(lease.fencingToken()).thenReturn(9L);
    when(fixture.leases.acquireUnlessCompleted(eq(fixture.runtime.id()), anyString(), any()))
        .thenReturn(Optional.of(lease));
    when(fixture.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200,
        Map.of(
            HuggingFaceHeaders.REPO_COMMIT, COMMIT,
            HuggingFaceHeaders.LINKED_ETAG, "\"" + linkedSha + "\"",
            HuggingFaceHeaders.LINKED_SIZE, Integer.toString(content.length),
            HuggingFaceHeaders.XET_HASH, "internal-xet-id",
            "Content-Length", Integer.toString(content.length)),
        new ByteArrayInputStream(content)));
    when(fixture.componentDao.findById(55L)).thenReturn(Optional.of(component));
    when(fixture.cache.storeVerifiedImmutable(
        eq(fixture.runtime), anyString(), any(), anyString(), any(), eq(component), anyString(),
        eq((long) content.length), eq(linkedSha), eq(null)))
        .thenAnswer(invocation -> {
          byte[] actual = ((java.io.InputStream) invocation.getArgument(2)).readAllBytes();
          assertEquals(HexFormat.of().formatHex(content), HexFormat.of().formatHex(actual));
          return new RawProtocolCache.StoredAsset(
              77L, 55L, 88L, sha256(actual), actual.length, "application/octet-stream");
        });
    when(fixture.registry.markFileReady(
        eq(70L), eq(9L), eq(77L), eq(55L), eq(linkedSha), anyString(), any()))
        .thenReturn(true);
    when(fixture.cache.serveStored(
        eq(fixture.runtime), any(RawProtocolCache.StoredAsset.class), eq(false), eq("attachment")))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(content), content.length,
            "application/octet-stream", linkedSha, null));

    MavenResponse response = fixture.service.get(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/model.safetensors", "",
        "https://repo.example/repository/hf", false);

    assertEquals(200, response.status());
    assertEquals(COMMIT, response.headers().get(HuggingFaceHeaders.REPO_COMMIT));
    assertEquals("\"" + linkedSha + "\"", response.headers().get(HuggingFaceHeaders.LINKED_ETAG));
    assertEquals(Integer.toString(content.length), response.headers().get(HuggingFaceHeaders.LINKED_SIZE));
    assertFalse(response.headers().containsKey(HuggingFaceHeaders.XET_HASH));
    assertFalse(response.headers().containsKey("Location"));
    ArgumentCaptor<HttpRemoteFetcher.Request> requests =
        ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fixture.fetcher).fetch(requests.capture());
    assertFalse(requests.getValue().headOnly());
    assertTrue(requests.getValue().url().contains("/resolve/" + COMMIT + "/"));
    verify(fixture.registry, never()).upsertRouteProjection(any());
  }

  @Test
  void warmCommitPinnedFileDoesNotContactUpstream() throws Exception {
    Fixture fixture = fixture();
    ModelRevision revision = revision(55L);
    ModelFile ready = file(
        "config.json", 77L, "b".repeat(64), 12L, HuggingFaceRegistryDao.FILE_READY, 4L);
    when(fixture.registry.findRevision(fixture.runtime.id(), "org/model", COMMIT))
        .thenReturn(Optional.of(revision));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(55L);
    when(fixture.registry.upsertRevision(any())).thenReturn(revision);
    when(fixture.registry.findFile(fixture.runtime.id(), "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(ready));
    when(fixture.cache.find(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/config.json"))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(fixture.cache.serve(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/config.json", true, "attachment"))
        .thenReturn(MavenResponse.noBody(200, 12L, "application/json", "etag", null));

    MavenResponse response = fixture.service.get(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/config.json", "",
        "https://repo.example/repository/hf", true);

    assertEquals(200, response.status());
    assertEquals(COMMIT, response.headers().get(HuggingFaceHeaders.REPO_COMMIT));
    assertEquals(
        "\"" + "b".repeat(64) + "\"",
        response.headers().get(HuggingFaceHeaders.LINKED_ETAG));
    verify(fixture.fetcher, never()).fetch(any());
    verify(fixture.leases, never()).acquireUnlessCompleted(anyLong(), anyString(), any());
    verify(fixture.componentDao, never()).upsertReturningId(any());
    verify(fixture.registry, never()).upsertRevision(any());
    verify(fixture.registry, never()).upsertRouteProjection(any());
  }

  @Test
  void warmRegularGitFileExposesGitOidAsLinkedEtag() throws Exception {
    Fixture fixture = fixture();
    String gitOid = "22ffb3454131a71d4144340befb799c66ad0c670";
    ModelRevision revision = revision(55L);
    ModelFile ready = new ModelFile(
        70L, 60L, 42L, "org/model", COMMIT, "config.json",
        77L, 55L, gitOid, null, null, 12L, "c".repeat(64), "application/json",
        "OTHER", HuggingFaceRegistryDao.FILE_READY, 4L, null, null, Instant.now());
    when(fixture.registry.findRevision(fixture.runtime.id(), "org/model", COMMIT))
        .thenReturn(Optional.of(revision));
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(55L);
    when(fixture.registry.upsertRevision(any())).thenReturn(revision);
    when(fixture.registry.findFile(fixture.runtime.id(), "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(ready));
    when(fixture.cache.find(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/config.json"))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(fixture.cache.serve(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/config.json", true, "attachment"))
        .thenReturn(MavenResponse.noBody(200, 12L, "application/json", "etag", null));

    MavenResponse response = fixture.service.get(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/config.json", "",
        "https://repo.example/repository/hf", true);

    assertEquals("\"" + gitOid + "\"", response.headers().get(HuggingFaceHeaders.LINKED_ETAG));
    verify(fixture.fetcher, never()).fetch(any());
  }

  @Test
  void rejectsUnsupportedWritesQueriesAndClientSideXetRoutes() {
    Fixture fixture = fixture();
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> fixture.service.post(
        fixture.runtime, "api/models/org/model", "", "https://repo.example/repository/hf",
        "{}".getBytes(StandardCharsets.UTF_8), false));
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> fixture.service.get(
        fixture.runtime, "api/models/org/model", "token=secret",
        "https://repo.example/repository/hf", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> fixture.service.get(
        fixture.runtime, "api/models/org/model/xet-read-token/main", "",
        "https://repo.example/repository/hf", false));
  }

  private static void stubRevisionProjection(Fixture fixture) {
    when(fixture.componentDao.upsertReturningId(any())).thenReturn(55L);
    when(fixture.registry.findRevision(fixture.runtime.id(), "org/model", COMMIT))
        .thenReturn(Optional.empty());
    when(fixture.registry.upsertRevision(any())).thenAnswer(invocation -> {
      ModelRevision value = invocation.getArgument(0);
      return new ModelRevision(
          60L, value.repositoryId(), value.repoId(), value.commitHash(), value.componentId(),
          value.rawMetadataAssetId(), value.author(), value.committedAt(), value.privateModel(),
          value.gated(), value.libraryName(), value.pipelineTag(), value.license(),
          value.observedAt(), value.updatedAt());
    });
    when(fixture.registry.upsertRef(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(fixture.registry.upsertFileMetadata(any())).thenAnswer(invocation -> {
      ModelFile value = invocation.getArgument(0);
      return new ModelFile(
          70L, value.revisionId(), value.repositoryId(), value.repoId(), value.commitHash(),
          value.path(), value.assetId(), value.componentId(), value.gitOid(), value.lfsSha256(),
          value.xetHash(), value.expectedSize(), value.internalSha256(), value.contentType(),
          value.fileKind(), value.state(), value.fencingToken(), value.failureCode(),
          value.nextAttemptAt(), value.updatedAt());
    });
  }

  private static ModelRevision revision(long componentId) {
    Instant now = Instant.now();
    return new ModelRevision(
        60L, 42L, "org/model", COMMIT, componentId, null, "fixture", now, false, false,
        "transformers", "text-generation", "apache-2.0", now, now);
  }

  private static ModelFile file(
      String path, Long assetId, String linkedSha, Long size, String state, long fencingToken) {
    return new ModelFile(
        70L, 60L, 42L, "org/model", COMMIT, path,
        assetId, 55L, null, linkedSha, linkedSha == null ? null : "internal-xet-id", size,
        assetId == null ? null : linkedSha, path.endsWith(".json")
            ? "application/json" : "application/octet-stream",
        path.endsWith(".safetensors") ? "SAFETENSORS" : "OTHER",
        state, fencingToken, null, null, Instant.now());
  }

  private static Fixture fixture() {
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    ComponentDao componentDao = mock(ComponentDao.class);
    HttpRemoteFetcher fetcher = mock(HttpRemoteFetcher.class);
    RawProtocolCache cache = mock(RawProtocolCache.class);
    HuggingFaceLeaseManager leases = mock(HuggingFaceLeaseManager.class);
    RepositoryRuntime runtime = new RepositoryRuntime(
        42L, "hf", RepositoryFormat.HUGGINGFACE, RepositoryType.PROXY, "huggingface-proxy",
        true, 1L, null, null, null, true, "https://hub.example", 1440, 60, true,
        null, List.of());
    HuggingFaceService service = new HuggingFaceService(
        registry, componentDao, fetcher, cache, new HuggingFaceComponentFactory(), leases,
        new ObjectMapper(), 1024 * 1024);
    return new Fixture(registry, componentDao, fetcher, cache, leases, runtime, service);
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private record Fixture(
      HuggingFaceRegistryDao registry,
      ComponentDao componentDao,
      HttpRemoteFetcher fetcher,
      RawProtocolCache cache,
      HuggingFaceLeaseManager leases,
      RepositoryRuntime runtime,
      HuggingFaceService service) {
  }
}
