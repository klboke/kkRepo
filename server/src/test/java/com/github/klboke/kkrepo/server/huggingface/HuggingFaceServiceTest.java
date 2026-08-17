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
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.RevisionRef;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceHeaders;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawProtocolCache;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    when(fixture.cache.find(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/model.safetensors"))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(fixture.cache.serve(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/model.safetensors",
        false, "attachment"))
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

    MavenResponse hot = fixture.service.get(
        fixture.runtime, "org/model/resolve/" + COMMIT + "/model.safetensors", "",
        "https://repo.example/repository/hf", false);
    assertEquals(200, hot.status());
    verify(fixture.fetcher).fetch(any());
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
  void servesFreshMetadataCacheAndRevalidatesExpiredEntriesWith304() throws Exception {
    Fixture fresh = fixture();
    ApiCacheEntry current = apiEntry(Instant.now().plusSeconds(60));
    when(fresh.registry.findApiCache(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(current));
    when(fresh.cache.find(any(), anyString())).thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(fresh.cache.serve(any(), anyString(), eq(true), eq("inline")))
        .thenReturn(MavenResponse.noBody(200, 2L, "application/json", "stored", null));

    MavenResponse cached = fresh.service.get(
        fresh.runtime, "api/models/org/model", "expand=true",
        "https://repo.example/repository/hf", true);

    assertEquals(200, cached.status());
    assertEquals(current.derivedEtag(), cached.etag());
    verify(fresh.fetcher, never()).fetch(any());

    Fixture stale = fixture();
    ApiCacheEntry expired = apiEntry(Instant.now().minusSeconds(1));
    when(stale.registry.findApiCache(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.of(expired));
    when(stale.cache.find(any(), anyString())).thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(stale.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        304, Map.of("X-Request-Id", "revalidated"), InputStream.nullInputStream()));
    when(stale.registry.upsertApiCache(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(stale.cache.serve(any(), anyString(), eq(false), eq("inline")))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2,
            "application/json", null, null));

    MavenResponse revalidated = stale.service.get(
        stale.runtime, "api/models/org/model/revision/" + COMMIT, "",
        "https://repo.example/repository/hf", false);

    assertEquals(200, revalidated.status());
    assertEquals(COMMIT, revalidated.headers().get(HuggingFaceHeaders.REPO_COMMIT));
    assertEquals("revalidated", revalidated.headers().get("X-Request-Id"));
    verify(stale.registry).upsertApiCache(any());
  }

  @Test
  void postsPathsInfoAndProjectsTreeEntriesAtThePinnedCommit() throws Exception {
    Fixture fixture = fixture();
    String tree = """
        [{"type":"directory","path":"ignored"},
         {"type":"file","path":"config.json","oid":"%s","size":12},
         {"type":"file","path":"weights/model.safetensors","size":4,
          "xet_hash":"private-xet","lfs":{"oid":"sha256:%s","size":4}}]
        """.formatted("b".repeat(40), "c".repeat(64));
    stubMetadataRoundTrip(
        fixture, tree.getBytes(StandardCharsets.UTF_8),
        Map.of(HuggingFaceHeaders.REPO_COMMIT, COMMIT, "Content-Type", "application/json"));

    MavenResponse response = fixture.service.post(
        fixture.runtime, "api/models/org/model/paths-info/main", "recursive=true",
        "https://repo.example/repository/hf",
        "{\"paths\":[\"config.json\",\"weights/model.safetensors\"]}"
            .getBytes(StandardCharsets.UTF_8),
        false);

    assertEquals(200, response.status());
    ArgumentCaptor<HttpRemoteFetcher.Request> request =
        ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fixture.fetcher).fetch(request.capture());
    assertTrue(request.getValue().url().contains("recursive=true"));
    assertTrue(request.getValue().requestBody() != null);
    ArgumentCaptor<ModelFile> files = ArgumentCaptor.forClass(ModelFile.class);
    verify(fixture.registry, org.mockito.Mockito.times(2)).upsertFileMetadata(files.capture());
    assertTrue(files.getAllValues().stream()
        .anyMatch(file -> "c".repeat(64).equals(file.lfsSha256())
            && "private-xet".equals(file.xetHash())));
  }

  @Test
  void projectsModelHeaderFallbackLicenseAndAllRefFamilies() throws Exception {
    Fixture model = fixture();
    byte[] modelJson = """
        {"private":true,"gated":"manual","lastModified":"not-an-instant",
         "tags":["transformers","license:mit"],
         "siblings":[{"type":"file","path":"README.md","size":5}]}
        """.getBytes(StandardCharsets.UTF_8);
    stubMetadataRoundTrip(model, modelJson, Map.of(HuggingFaceHeaders.REPO_COMMIT, COMMIT));

    assertEquals(200, model.service.get(
        model.runtime, "api/models/org/model", "securityStatus=true",
        "https://repo.example/repository/hf", false).status());
    ArgumentCaptor<ComponentRecord> component = ArgumentCaptor.forClass(ComponentRecord.class);
    verify(model.componentDao, org.mockito.Mockito.atLeastOnce()).upsertReturningId(component.capture());
    assertTrue(component.getAllValues().stream().anyMatch(value ->
        "mit".equals(value.attributes().get("license"))
            && Boolean.TRUE.equals(value.attributes().get("private"))
            && Boolean.TRUE.equals(value.attributes().get("gated"))));

    Fixture refs = fixture();
    byte[] refsJson = ("""
        {"branches":[{"name":"main","ref":"refs/heads/main","targetCommit":"%s"}],
         "tags":[{"name":"v1","commit":"%s"},{"name":"bad","commit":"nope"}],
         "converts":{},"pullRequests":[{"ref":"refs/pr/7","targetCommit":"%s"}]}
        """).formatted(COMMIT, COMMIT, COMMIT).getBytes(StandardCharsets.UTF_8);
    stubMetadataRoundTrip(refs, refsJson, Map.of());

    assertEquals(200, refs.service.get(
        refs.runtime, "api/models/org/model/refs", "include_pull_requests=true",
        "https://repo.example/repository/hf", false).status());
    verify(refs.registry, org.mockito.Mockito.atLeast(3)).upsertRef(any());
  }

  @Test
  void rejectsInvalidQueriesBodiesRoutesAndRepositoryKinds() {
    Fixture fixture = fixture();
    assertEquals(404, fixture.service.get(
        fixture.runtime, "", "", "https://repo.example/repository/hf", false).status());
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> fixture.service.get(
        fixture.runtime, "api/models/org/model/paths-info/main", "",
        "https://repo.example/repository/hf", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> fixture.service.get(
        fixture.runtime, "org/model/resolve/main/a/%2e%2e/b", "",
        "https://repo.example/repository/hf", false));
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> fixture.service.get(
        hostedRuntime(), "api/models/org/model", "",
        "https://repo.example/repository/hf", false));

    for (String query : List.of(
        "token=secret", "%ZZ=value", "expand=true#fragment", "expand=\u0001",
        java.util.stream.IntStream.range(0, 17)
            .mapToObj(index -> "expand=" + index).collect(java.util.stream.Collectors.joining("&")),
        "x".repeat(8_193))) {
      assertThrows(MavenExceptions.MavenNotFoundException.class, () -> fixture.service.get(
          fixture.runtime, "api/models/org/model", query,
          "https://repo.example/repository/hf", false));
    }

    for (byte[] body : List.of(
        new byte[0], "{}".getBytes(StandardCharsets.UTF_8),
        "{\"paths\":[]}".getBytes(StandardCharsets.UTF_8),
        "{\"paths\":[42]}".getBytes(StandardCharsets.UTF_8),
        "{\"paths\":[\"../escape\"]}".getBytes(StandardCharsets.UTF_8),
        "not-json".getBytes(StandardCharsets.UTF_8), new byte[1024 * 1024 + 1])) {
      assertThrows(MavenExceptions.MavenNotFoundException.class, () -> fixture.service.post(
          fixture.runtime, "api/models/org/model/paths-info/main", "",
          "https://repo.example/repository/hf", body, false));
    }
  }

  @Test
  void preservesBoundedUpstreamErrorsAndWrapsMetadataIoFailures() throws Exception {
    Fixture error = fixture();
    when(error.registry.findApiCache(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(error.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        429,
        Map.of("Content-Type", "application/problem+json", "Retry-After", "10",
            HuggingFaceHeaders.XET_HASH, "hidden"),
        new ByteArrayInputStream("{\"error\":\"rate\"}".getBytes(StandardCharsets.UTF_8))));

    MavenResponse response = error.service.get(
        error.runtime, "api/models/org/model", "",
        "https://repo.example/repository/hf", false);
    assertEquals(429, response.status());
    assertEquals("10", response.headers().get("Retry-After"));
    assertFalse(response.headers().containsKey(HuggingFaceHeaders.XET_HASH));
    assertTrue(response.hasBody());

    Fixture io = fixture();
    when(io.registry.findApiCache(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(io.fetcher.fetch(any())).thenThrow(new IOException("x".repeat(300)));
    MavenExceptions.BadUpstreamException failure = assertThrows(
        MavenExceptions.BadUpstreamException.class,
        () -> io.service.get(io.runtime, "api/models/org/model", "",
            "https://repo.example/repository/hf", false));
    assertTrue(failure.getMessage().length() < 320);
  }

  @Test
  void resolvesFreshAndNewSymbolicRefsThenProjectsTheMutableRoute() throws Exception {
    Fixture cached = fixture();
    RevisionRef ref = revisionRef("main", 7L);
    ModelFile ready = file(
        "config.json", 77L, "b".repeat(64), 12L, HuggingFaceRegistryDao.FILE_READY, 4L);
    when(cached.registry.findRef(42L, "org/model", "main")).thenReturn(Optional.of(ref));
    when(cached.registry.findRevision(42L, "org/model", COMMIT))
        .thenReturn(Optional.of(revision(55L)));
    when(cached.registry.findFile(42L, "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(ready));
    when(cached.cache.find(cached.runtime, canonical("config.json")))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(cached.cache.serve(cached.runtime, canonical("config.json"), true, "attachment"))
        .thenReturn(MavenResponse.noBody(200, 12L, "application/json", "etag", null));

    MavenResponse response = cached.service.get(
        cached.runtime, "org/model/resolve/main/config.json", "",
        "https://repo.example/repository/hf", true);
    assertEquals(200, response.status());
    verify(cached.registry).upsertRouteProjection(any());

    Fixture refreshed = fixture();
    stubMetadataRoundTrip(
        refreshed,
        ("{\"sha\":\"" + COMMIT + "\",\"siblings\":[]}")
            .getBytes(StandardCharsets.UTF_8),
        Map.of());
    when(refreshed.registry.findRef(42L, "org/model", "release"))
        .thenReturn(Optional.empty(), Optional.of(revisionRef("release", 8L)));
    when(refreshed.registry.findFile(42L, "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(ready));
    when(refreshed.cache.find(refreshed.runtime, canonical("config.json")))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(refreshed.cache.serve(
        refreshed.runtime, canonical("config.json"), false, "attachment"))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2,
            "application/json", "etag", null));

    assertEquals(200, refreshed.service.get(
        refreshed.runtime, "org/model/resolve/release/config.json", "",
        "https://repo.example/repository/hf", false).status());
    verify(refreshed.fetcher).fetch(any());
  }

  @Test
  void failsWhenARevisionRefreshDoesNotPublishACommitBinding() throws Exception {
    Fixture fixture = fixture();
    stubMetadataRoundTrip(
        fixture, "{\"siblings\":[]}".getBytes(StandardCharsets.UTF_8), Map.of());
    when(fixture.registry.findRef(42L, "org/model", "missing")).thenReturn(Optional.empty());

    assertThrows(MavenExceptions.BadUpstreamException.class, () -> fixture.service.get(
        fixture.runtime, "org/model/resolve/missing/config.json", "",
        "https://repo.example/repository/hf", false));
  }

  @Test
  void honorsFailureBackoffAndServesTheDistributedFetchWinner() throws Exception {
    Fixture backedOff = fixture();
    ModelFile failed = new ModelFile(
        70L, 60L, 42L, "org/model", COMMIT, "config.json", null, 55L,
        null, null, null, null, null, "application/json", "OTHER",
        HuggingFaceRegistryDao.FILE_FAILED, 3L, "UPSTREAM_GET_503",
        Instant.now().plusSeconds(30), Instant.now());
    when(backedOff.registry.findRevision(42L, "org/model", COMMIT))
        .thenReturn(Optional.of(revision(55L)));
    when(backedOff.registry.findFile(42L, "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(failed));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> backedOff.service.get(
        backedOff.runtime, canonical("config.json"), "",
        "https://repo.example/repository/hf", false));

    Fixture winner = fixture();
    ModelFile discovered = file(
        "config.json", null, null, null, HuggingFaceRegistryDao.FILE_DISCOVERED, 0L);
    ModelFile ready = file(
        "config.json", 77L, "b".repeat(64), 12L, HuggingFaceRegistryDao.FILE_READY, 9L);
    when(winner.registry.findRevision(42L, "org/model", COMMIT))
        .thenReturn(Optional.of(revision(55L)));
    when(winner.registry.findFile(42L, "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(discovered), Optional.of(ready), Optional.of(ready));
    when(winner.cache.find(winner.runtime, canonical("config.json")))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(winner.leases.acquireUnlessCompleted(eq(42L), anyString(), any()))
        .thenAnswer(invocation -> {
          java.util.function.BooleanSupplier completed = invocation.getArgument(2);
          assertTrue(completed.getAsBoolean());
          return Optional.empty();
        });
    when(winner.cache.serve(winner.runtime, canonical("config.json"), false, "attachment"))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("ready".getBytes(StandardCharsets.UTF_8)), 5,
            "application/json", "etag", null));

    assertEquals(200, winner.service.get(
        winner.runtime, canonical("config.json"), "",
        "https://repo.example/repository/hf", false).status());
    verify(winner.fetcher, never()).fetch(any());
  }

  @Test
  void reconcilesAFileThatBecomesReadyWhileClaimingFetchOwnership() {
    Fixture won = fixture();
    ModelFile discovered = file(
        "config.json", null, null, null, HuggingFaceRegistryDao.FILE_DISCOVERED, 0L);
    ModelFile ready = file(
        "config.json", 77L, "b".repeat(64), 12L, HuggingFaceRegistryDao.FILE_READY, 9L);
    HuggingFaceLeaseManager.Lease lease = stubOwnedCold(won, "config.json", discovered);
    when(won.registry.markFileFetching(eq(70L), eq(9L), any())).thenReturn(false);
    when(won.registry.findFile(42L, "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(discovered), Optional.of(ready));
    when(won.cache.find(won.runtime, canonical("config.json")))
        .thenReturn(Optional.of(mock(CachedAssetMetadata.class)));
    when(won.cache.serve(won.runtime, canonical("config.json"), true, "attachment"))
        .thenReturn(MavenResponse.noBody(200, 12L, "application/json", "etag", null));

    assertEquals(200, won.service.get(
        won.runtime, canonical("config.json"), "",
        "https://repo.example/repository/hf", true).status());
    verify(lease).close();

    Fixture changed = fixture();
    stubOwnedCold(changed, "config.json", discovered);
    when(changed.registry.markFileFetching(eq(70L), eq(9L), any())).thenReturn(false);
    when(changed.registry.findFile(42L, "org/model", COMMIT, "config.json"))
        .thenReturn(Optional.of(discovered));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> changed.service.get(
        changed.runtime, canonical("config.json"), "",
        "https://repo.example/repository/hf", false));
  }

  @Test
  void reportsUpstreamFileErrorsAndFencesEveryFailedPublication() throws Exception {
    ModelFile discovered = file(
        "model.bin", null, null, null, HuggingFaceRegistryDao.FILE_DISCOVERED, 0L);

    Fixture missing = fixture();
    stubOwnedCold(missing, "model.bin", discovered);
    when(missing.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        404, Map.of("Retry-After", "2"),
        new ByteArrayInputStream("missing".getBytes(StandardCharsets.UTF_8))));
    MavenResponse notFound = missing.service.get(
        missing.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false);
    assertEquals(404, notFound.status());
    verify(missing.registry, org.mockito.Mockito.atLeastOnce()).markFileFailed(
        eq(70L), eq(9L), eq("UPSTREAM_GET_404"), any(), any());

    Fixture wrongCommit = fixture();
    stubOwnedCold(wrongCommit, "model.bin", discovered);
    when(wrongCommit.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of(HuggingFaceHeaders.REPO_COMMIT, "f".repeat(40)),
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> wrongCommit.service.get(
        wrongCommit.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false));
    verify(wrongCommit.registry).markFileFailed(
        eq(70L), eq(9L), eq("UPSTREAM_FAILURE"), any(), any());

    Fixture tooLarge = fixture(3);
    stubOwnedCold(tooLarge, "model.bin", discovered);
    when(tooLarge.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of(HuggingFaceHeaders.LINKED_SIZE, "4"),
        new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> tooLarge.service.get(
        tooLarge.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false));
  }

  @Test
  void rejectsSupersededMetadataAndPublicationFencingTokens() throws Exception {
    ModelFile discovered = file(
        "model.bin", null, null, null, HuggingFaceRegistryDao.FILE_DISCOVERED, 0L);
    byte[] body = "body".getBytes(StandardCharsets.UTF_8);

    Fixture metadata = fixture();
    stubOwnedCold(metadata, "model.bin", discovered);
    when(metadata.registry.updateFetchingFileMetadata(
        anyLong(), anyLong(), any(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(false);
    when(metadata.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of("Content-Length", "4"), new ByteArrayInputStream(body)));
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> metadata.service.get(
        metadata.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false));

    Fixture publication = fixture();
    stubOwnedCold(publication, "model.bin", discovered);
    when(publication.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of("Content-Length", "4"), new ByteArrayInputStream(body)));
    when(publication.cache.storeVerifiedImmutable(
        any(), anyString(), any(), anyString(), any(), any(), anyString(), any(), any(), any()))
        .thenReturn(new RawProtocolCache.StoredAsset(
            77L, 55L, 88L, sha256(body), body.length, "application/octet-stream"));
    when(publication.registry.markFileReady(
        anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), any()))
        .thenReturn(false);
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> publication.service.get(
        publication.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false));
  }

  @Test
  void rejectsIdentityMismatchAndStreamsBeyondTheConfiguredLimitFailClosed() throws Exception {
    ModelFile discovered = file(
        "model.bin", null, null, null, HuggingFaceRegistryDao.FILE_DISCOVERED, 0L);
    byte[] body = "body".getBytes(StandardCharsets.UTF_8);
    String expected = sha256(body);

    Fixture mismatch = fixture();
    stubOwnedCold(mismatch, "model.bin", discovered);
    when(mismatch.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of(HuggingFaceHeaders.LINKED_ETAG, expected, "Content-Length", "4"),
        new ByteArrayInputStream(body)));
    RawProtocolCache.StoredAsset wrong = mock(RawProtocolCache.StoredAsset.class);
    when(wrong.sha256()).thenReturn("f".repeat(64));
    when(wrong.size()).thenReturn(4L);
    when(mismatch.cache.storeVerifiedImmutable(
        any(), anyString(), any(), anyString(), any(), any(), anyString(), any(), any(), any()))
        .thenReturn(wrong);
    assertThrows(IllegalArgumentException.class, () -> mismatch.service.get(
        mismatch.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false));
    verify(wrong).discardBody();
    verify(mismatch.registry).markFileFailed(
        eq(70L), eq(9L), eq("IDENTITY_MISMATCH"), any(), any());

    Fixture limited = fixture(2);
    stubOwnedCold(limited, "model.bin", discovered);
    when(limited.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of(), new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
    when(limited.cache.storeVerifiedImmutable(
        any(), anyString(), any(), anyString(), any(), any(), anyString(), any(), any(), any()))
        .thenAnswer(invocation -> {
          ((InputStream) invocation.getArgument(2)).readAllBytes();
          throw new AssertionError("size limit should have failed first");
        });
    assertThrows(MavenExceptions.BadUpstreamException.class, () -> limited.service.get(
        limited.runtime, canonical("model.bin"), "",
        "https://repo.example/repository/hf", false));
    verify(limited.registry).markFileFailed(
        eq(70L), eq(9L), eq("UPSTREAM_FAILURE"), any(), any());
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

  private static ApiCacheEntry apiEntry(Instant expiresAt) {
    Instant now = Instant.now();
    return new ApiCacheEntry(
        30L, 42L, "api/models/org/model", "", "", 10L, 11L,
        "\"upstream\"", "derived-etag", null,
        com.github.klboke.kkrepo.protocol.huggingface.HuggingFaceJsonTransformer.SCHEMA_VERSION,
        expiresAt, now);
  }

  private static void stubMetadataRoundTrip(
      Fixture fixture, byte[] upstream, Map<String, String> headers) throws Exception {
    when(fixture.registry.findApiCache(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(fixture.fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, headers, new ByteArrayInputStream(upstream)));
    doAnswer(invocation -> {
      String path = invocation.getArgument(1);
      byte[] bytes = ((InputStream) invocation.getArgument(2)).readAllBytes();
      boolean raw = path.contains("/raw/");
      return new RawProtocolCache.StoredAsset(
          raw ? 10L : 11L, null, raw ? 20L : 21L, sha256(bytes), bytes.length,
          "application/json");
    }).when(fixture.cache).storeHidden(
        eq(fixture.runtime), anyString(), any(), anyString(), any());
    when(fixture.registry.upsertApiCache(any())).thenAnswer(invocation -> {
      ApiCacheEntry value = invocation.getArgument(0);
      return new ApiCacheEntry(
          30L, value.repositoryId(), value.route(), value.query(), value.requestFingerprint(),
          value.rawAssetId(), value.derivedAssetId(), value.upstreamEtag(), value.derivedEtag(),
          value.nextLink(), value.transformVersion(), value.expiresAt(), value.updatedAt());
    });
    stubRevisionProjection(fixture);
    when(fixture.cache.serve(eq(fixture.runtime), anyString(), anyBoolean(), eq("inline")))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), 2,
            "application/json", null, null));
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

  private static RevisionRef revisionRef(String name, long generation) {
    Instant now = Instant.now();
    return new RevisionRef(
        42L, "org/model", name, COMMIT, generation,
        now.plusSeconds(60), now, now);
  }

  private static String canonical(String path) {
    return "org/model/resolve/" + COMMIT + "/" + path;
  }

  private static HuggingFaceLeaseManager.Lease stubOwnedCold(
      Fixture fixture, String path, ModelFile discovered) {
    when(fixture.registry.findRevision(42L, "org/model", COMMIT))
        .thenReturn(Optional.of(revision(55L)));
    when(fixture.registry.findFile(42L, "org/model", COMMIT, path))
        .thenReturn(Optional.of(discovered));
    HuggingFaceLeaseManager.Lease lease = mock(HuggingFaceLeaseManager.Lease.class);
    when(lease.fencingToken()).thenReturn(9L);
    when(fixture.leases.acquireUnlessCompleted(eq(42L), anyString(), any()))
        .thenReturn(Optional.of(lease));
    when(fixture.registry.markFileFetching(eq(70L), eq(9L), any())).thenReturn(true);
    when(fixture.registry.updateFetchingFileMetadata(
        anyLong(), anyLong(), any(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(true);
    when(fixture.componentDao.findById(55L)).thenReturn(Optional.of(persistedComponent()));
    return lease;
  }

  private static ComponentRecord persistedComponent() {
    ComponentRecord candidate = new HuggingFaceComponentFactory().component(
        new RepositoryRuntime(
            42L, "hf", RepositoryFormat.HUGGINGFACE, RepositoryType.PROXY,
            "huggingface-proxy", true, 1L, null, null, null, true,
            "https://hub.example", 1440, 60, true, null, List.of()),
        "org/model", COMMIT, COMMIT, false, false,
        "transformers", "text-generation", "apache-2.0", Instant.now());
    return new ComponentRecord(
        55L, candidate.repositoryId(), candidate.format(), candidate.namespace(), candidate.name(),
        candidate.version(), candidate.kind(), candidate.coordinateHash(), candidate.attributes(),
        candidate.lastUpdatedAt());
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
    return fixture(1024 * 1024);
  }

  private static Fixture fixture(long maxFileBytes) {
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
        new ObjectMapper(), maxFileBytes);
    return new Fixture(registry, componentDao, fetcher, cache, leases, runtime, service);
  }

  private static RepositoryRuntime hostedRuntime() {
    return new RepositoryRuntime(
        42L, "hf-hosted", RepositoryFormat.HUGGINGFACE, RepositoryType.HOSTED,
        "raw-hosted", true, 1L, null, null, null, true, null, 1440, 60, true,
        null, List.of());
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
