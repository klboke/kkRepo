package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AnsibleGalaxyServiceProxyStateTest {
  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);
  private static final String FILENAME = "acme-tools-1.2.3.tar.gz";

  @TempDir
  Path tempDir;

  private ObjectMapper mapper;
  private AnsibleGalaxyRegistryDao registry;
  private AnsibleGalaxyAssetSupport assets;
  private AnsibleCollectionArchiveInspector inspector;
  private HttpRemoteFetcher fetcher;
  private AnsibleGalaxyService service;
  private RepositoryRuntime proxy;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    registry = mock(AnsibleGalaxyRegistryDao.class);
    assets = mock(AnsibleGalaxyAssetSupport.class);
    inspector = mock(AnsibleCollectionArchiveInspector.class);
    fetcher = mock(HttpRemoteFetcher.class);
    service = new AnsibleGalaxyService(
        mapper, registry, assets, inspector, fetcher, mock(RepositoryRuntimeRegistry.class),
        new AnsibleImportTaskLeaseManager(registry));
    proxy = runtime(20L, RepositoryType.PROXY, "https://galaxy.example/root/", List.of());
  }

  @Test
  void usesPositiveAndNegativeMetadataCachesWithoutTakingALease() throws Exception {
    Instant now = Instant.now();
    Map<String, Object> identity = detail(SHA_A);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(state(identity, now.plusSeconds(60), null, null, SHA_A)));

    assertEquals(identity, service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));
    verify(registry, never()).tryAcquireLease(anyString(), anyString(), any());
    verify(fetcher, never()).fetch(any());

    AnsibleGalaxyRegistryDao registry2 = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleGalaxyService service2 = service(registry2, fetcher, assets, inspector);
    when(registry2.findProxyState(proxy.id(), "acme", "missing", "1.2.3"))
        .thenReturn(Optional.of(state(Map.of(), null, 404, now.plusSeconds(60), null)));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service2.fetchProxyDocument(
        proxy, "acme", "missing", "1.2.3", "api/v3/missing"));
    verify(registry2, never()).tryAcquireLease(anyString(), anyString(), any());
  }

  @Test
  void reusesMetadataThatAnotherReplicaRefreshedAfterLeaseAcquisition() throws Exception {
    Map<String, Object> identity = detail(SHA_A);
    AnsibleGalaxyRegistryDao.ProxyVersionState refreshed =
        state(identity, Instant.now().plusSeconds(60), null, null, SHA_A);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.of(refreshed));
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("metadata")));

    assertEquals(identity, service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    verify(fetcher, never()).fetch(any());
    verify(registry).releaseLease("metadata", "owner", 7L);
  }

  @Test
  void waitsForTheReplicaHoldingTheMetadataLease() throws Exception {
    Map<String, Object> identity = detail(SHA_A);
    AnsibleGalaxyRegistryDao.ProxyVersionState refreshed =
        state(identity, Instant.now().plusSeconds(60), null, null, SHA_A);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.of(refreshed));
    when(registry.tryAcquireLease(anyString(), anyString(), any())).thenReturn(Optional.empty());

    assertEquals(identity, service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    verify(fetcher, never()).fetch(any());
  }

  @Test
  void waitsForFreshMetadataInsteadOfReturningTheStaleProjection() {
    Instant now = Instant.now();
    Map<String, Object> staleIdentity = detail(SHA_A);
    Map<String, Object> freshIdentity = detail(SHA_B);
    AnsibleGalaxyRegistryDao.ProxyVersionState stale =
        state(staleIdentity, now.minusSeconds(1), null, null, SHA_A);
    AnsibleGalaxyRegistryDao.ProxyVersionState fresh =
        state(freshIdentity, now.plusSeconds(60), null, null, SHA_B);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(stale), Optional.of(fresh));

    assertEquals(freshIdentity, service.awaitProxyDocument(
        proxy, "acme", "tools", "1.2.3", now));

    verify(registry, times(2)).findProxyState(proxy.id(), "acme", "tools", "1.2.3");
  }

  @Test
  void mapsInterruptedReplicaWaitsToServiceUnavailable() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(AnsibleGalaxyExceptions.ServiceUnavailable.class,
          () -> service.awaitProxyDocument(
              proxy, "acme", "tools", "1.2.3", Instant.now()));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void observesNegativeStatePublishedByTheReplicaHoldingTheLease() {
    AnsibleGalaxyRegistryDao.ProxyVersionState negative = state(
        Map.of(), null, 404, Instant.now().plusSeconds(60), null);
    when(registry.findProxyState(proxy.id(), "acme", "missing", "1.2.3"))
        .thenReturn(Optional.of(negative));

    assertThrows(AnsibleGalaxyExceptions.NotFound.class,
        () -> service.awaitProxyDocument(
            proxy, "acme", "missing", "1.2.3", Instant.now()));
  }

  @Test
  void conditionallyRevalidatesExpiredMetadataWith304() throws Exception {
    Map<String, Object> identity = detail(SHA_A);
    AnsibleGalaxyRegistryDao.ProxyVersionState expired = state(
        identity, Instant.now().minusSeconds(1), null, null, SHA_A);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(expired));
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("metadata")));
    when(fetcher.fetch(any())).thenReturn(result(304, Map.of("ETag", "\"etag\""), null));

    assertEquals(identity, service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    verify(registry).touchProxyState(
        eq(proxy.id()), eq("acme"), eq("tools"), eq("1.2.3"),
        eq("etag"), eq(null), any(Instant.class), any(Instant.class));
    verify(registry, never()).upsertProxyState(any());
    verify(registry, never()).nextRepositoryRevision(proxy.id());
    verify(registry).releaseLease("metadata", "owner", 7L);
  }

  @Test
  void touchesUnchangedMetadataAfterAFullRevalidationWithoutBumpingContentRevision()
      throws Exception {
    Map<String, Object> identity = new LinkedHashMap<>(detail(SHA_A));
    identity.remove("files");
    AnsibleGalaxyRegistryDao.ProxyVersionState expired = state(
        identity, Instant.now().minusSeconds(1), null, null, SHA_A);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(expired));
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("unchanged-metadata")));
    when(fetcher.fetch(any())).thenReturn(jsonResult(200, identity));

    assertEquals(identity, service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    verify(registry).touchProxyState(
        eq(proxy.id()), eq("acme"), eq("tools"), eq("1.2.3"), eq("new"), eq(null),
        any(Instant.class), any(Instant.class));
    verify(registry, never()).upsertProxyState(any());
    verify(registry).releaseLease("unchanged-metadata", "owner", 7L);
  }

  @Test
  void persistsBoundedMetadataAndResolvesRelativeArtifactLinks() throws Exception {
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("metadata")));
    when(fetcher.fetch(any())).thenReturn(jsonResult(200, detail(SHA_A)));

    Map<String, Object> projected = service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail");

    assertFalse(projected.containsKey("files"));
    ArgumentCaptor<AnsibleGalaxyRegistryDao.ProxyVersionState> persisted =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.ProxyVersionState.class);
    verify(registry).upsertProxyState(persisted.capture());
    assertEquals(FILENAME, persisted.getValue().artifactFilename());
    assertEquals("https://galaxy.example/download/" + FILENAME,
        persisted.getValue().upstreamDownloadUrl());
    assertEquals(SHA_A, persisted.getValue().artifactSha256());
  }

  @Test
  void derivesTheCanonicalArtifactFilenameWhenUpstreamOmitsIt() throws Exception {
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("fallback-filename")));
    when(fetcher.fetch(any())).thenReturn(jsonResult(200, Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "download_url", "/download/" + FILENAME,
        "artifact", Map.of("sha256", SHA_A))));

    service.fetchProxyDocument(proxy, "acme", "tools", "1.2.3", "api/v3/detail");

    ArgumentCaptor<AnsibleGalaxyRegistryDao.ProxyVersionState> persisted =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.ProxyVersionState.class);
    verify(registry).upsertProxyState(persisted.capture());
    assertEquals(FILENAME, persisted.getValue().artifactFilename());
    verify(registry).releaseLease("fallback-filename", "owner", 7L);
  }

  @Test
  void rejectsMalformedProxyIdentityBeforeDerivingFallbackFilename() throws Exception {
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("malformed-identity")));
    when(fetcher.fetch(any())).thenReturn(jsonResult(200, Map.of(
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "artifact", Map.of("sha256", SHA_A))));

    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.fetchProxyDocument(
            proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    verify(registry, never()).upsertProxyState(any());
    verify(registry).releaseLease("malformed-identity", "owner", 7L);
  }

  @Test
  void rejectsNonCanonicalArtifactFilenameFromUpstreamMetadata() throws Exception {
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("non-canonical-filename")));
    when(fetcher.fetch(any())).thenReturn(jsonResult(200, Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "download_url", "/download/renamed.tar.gz",
        "artifact", Map.of("filename", "renamed.tar.gz", "sha256", SHA_A))));

    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.fetchProxyDocument(
            proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    verify(registry, never()).upsertProxyState(any());
    verify(registry).releaseLease("non-canonical-filename", "owner", 7L);
  }

  @Test
  void recordsNegativeResponsesAndRejectsOtherBadStatuses() throws Exception {
    when(registry.findProxyState(proxy.id(), "acme", "missing", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("negative")));
    when(fetcher.fetch(any())).thenReturn(result(410, Map.of("ETag", "gone"), null));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.fetchProxyDocument(
        proxy, "acme", "missing", "1.2.3", "api/v3/missing"));
    ArgumentCaptor<AnsibleGalaxyRegistryDao.ProxyVersionState> negative =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.ProxyVersionState.class);
    verify(registry).upsertProxyState(negative.capture());
    assertEquals(410, negative.getValue().negativeStatus());
    assertTrue(negative.getValue().upstreamIdentity().isEmpty());

    AnsibleGalaxyRegistryDao registry2 = mock(AnsibleGalaxyRegistryDao.class);
    HttpRemoteFetcher fetcher2 = mock(HttpRemoteFetcher.class);
    AnsibleGalaxyService service2 = service(registry2, fetcher2, assets, inspector);
    when(registry2.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry2.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("bad-status")));
    when(fetcher2.fetch(any())).thenReturn(result(503, Map.of(), null));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class, () -> service2.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));
    verify(registry2).releaseLease("bad-status", "owner", 7L);
  }

  @Test
  void fallsBackToStaleMetadataOnlyForTransportFailures() throws Exception {
    Map<String, Object> identity = detail(SHA_A);
    AnsibleGalaxyRegistryDao.ProxyVersionState expired = state(
        identity, Instant.now().minusSeconds(1), null, null, SHA_A);
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(expired));
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("stale")));
    when(fetcher.fetch(any())).thenThrow(new IOException("offline"));
    assertEquals(identity, service.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));

    AnsibleGalaxyRegistryDao registry2 = mock(AnsibleGalaxyRegistryDao.class);
    HttpRemoteFetcher fetcher2 = mock(HttpRemoteFetcher.class);
    AnsibleGalaxyService service2 = service(registry2, fetcher2, assets, inspector);
    when(registry2.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry2.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("no-stale")));
    when(fetcher2.fetch(any())).thenThrow(new IOException("offline"));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class, () -> service2.fetchProxyDocument(
        proxy, "acme", "tools", "1.2.3", "api/v3/detail"));
  }

  @Test
  void materializesAndValidatesAnUpstreamArtifactUnderALease() throws Exception {
    AnsibleGalaxyRegistryDao.ProxyVersionState state = state(
        detail(SHA_A), Instant.now().plusSeconds(60), null, null, SHA_A);
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("artifact")));
    when(fetcher.fetch(any())).thenReturn(result(
        200, Map.of("Content-Type", "application/gzip"),
        new ByteArrayInputStream(new byte[] {1, 2, 3})));
    Path file = Files.writeString(tempDir.resolve(FILENAME), "body");
    AnsibleCollectionArchiveInspector.InspectedCollection inspected = inspected(file, SHA_A);
    when(inspector.inspect(any())).thenReturn(inspected);
    AssetRecord asset = asset(31L, 41L);
    when(assets.storeCollection(eq(proxy), anyString(), eq(file), any(), any(), any(), any()))
        .thenReturn(new AnsibleGalaxyAssetSupport.StoredCollection(asset, true));
    when(assets.requiredBlob(asset)).thenReturn(blob(41L, inspected.size(), SHA_A));
    AnsibleGalaxyRegistryDao.CollectionVersion stored = version(proxy.id(), 51L, asset.id());
    when(registry.insertVersion(any())).thenReturn(stored);

    assertEquals(stored, service.materializeProxy(proxy, state));

    assertFalse(Files.exists(file));
    verify(registry, times(2)).releaseLease("artifact", "owner", 7L);
    ArgumentCaptor<AnsibleGalaxyRegistryDao.CollectionVersion> inserted =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.CollectionVersion.class);
    verify(registry).insertVersion(inserted.capture());
    assertEquals("PROXY", inserted.getValue().sourceKind());
  }

  @Test
  void rejectsIncompleteFailedAndMismatchedUpstreamArtifacts() throws Exception {
    AnsibleGalaxyRegistryDao.ProxyVersionState incomplete = new AnsibleGalaxyRegistryDao.ProxyVersionState(
        proxy.id(), "acme", "tools", "1.2.3", FILENAME, null, null, null,
        null, null, null, Instant.now(), null, null, Map.of(), 1L, Instant.now());
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.materializeProxy(proxy, incomplete));

    AnsibleGalaxyRegistryDao registry2 = mock(AnsibleGalaxyRegistryDao.class);
    HttpRemoteFetcher fetcher2 = mock(HttpRemoteFetcher.class);
    AnsibleGalaxyService service2 = service(registry2, fetcher2, assets, inspector);
    AnsibleGalaxyRegistryDao.ProxyVersionState complete = state(
        detail(SHA_A), Instant.now(), null, null, SHA_A);
    when(registry2.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry2.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("artifact-status")));
    when(fetcher2.fetch(any())).thenReturn(result(404, Map.of(), null));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service2.materializeProxy(proxy, complete));

    AnsibleGalaxyRegistryDao registry3 = mock(AnsibleGalaxyRegistryDao.class);
    HttpRemoteFetcher fetcher3 = mock(HttpRemoteFetcher.class);
    AnsibleCollectionArchiveInspector inspector3 = mock(AnsibleCollectionArchiveInspector.class);
    AnsibleGalaxyService service3 = service(registry3, fetcher3, assets, inspector3);
    when(registry3.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry3.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("artifact-sha")));
    when(fetcher3.fetch(any())).thenReturn(result(
        200, Map.of(), new ByteArrayInputStream(new byte[] {1})));
    Path wrong = Files.writeString(tempDir.resolve("wrong.tar.gz"), "wrong");
    when(inspector3.inspect(any())).thenReturn(inspected(wrong, SHA_B));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service3.materializeProxy(proxy, complete));
    assertFalse(Files.exists(wrong));
  }

  @Test
  void discoversV3AtRootOrApiAndRejectsUnsafeAdvertisements() {
    AnsibleGalaxyService root = spy(service);
    doReturn(Map.of("available_versions", Map.of("v3", "api/v3/")))
        .when(root).fetchProxyDocument(proxy, "@upstream", "@discovery", "@v3-root", "");
    assertEquals("https://galaxy.example/root/api/v3/collections",
        root.proxyV3Url(proxy, "collections"));

    AnsibleGalaxyService fallback = spy(service);
    doThrow(new AnsibleGalaxyExceptions.NotFound("root missing"))
        .when(fallback).fetchProxyDocument(
            proxy, "@upstream", "@discovery", "@v3-root", "");
    doReturn(Map.of("available_versions", Map.of("v3", "v3/")))
        .when(fallback).fetchProxyDocument(
            proxy, "@upstream", "@discovery", "@v3-api", "api/");
    assertEquals("https://galaxy.example/root/api/v3/collections",
        fallback.proxyV3Url(proxy, "collections"));

    for (String advertised : List.of(
        "", "x".repeat(2049), "http://[", "file:///tmp/", "https://u@host/v3/")) {
      AnsibleGalaxyService unsafe = spy(service);
      doReturn(Map.of("available_versions", Map.of("v3", advertised)))
          .when(unsafe).fetchProxyDocument(
              proxy, "@upstream", "@discovery", "@v3-root", "");
      if (advertised.isBlank()) {
        doReturn(Map.of("available_versions", Map.of("v3", advertised)))
            .when(unsafe).fetchProxyDocument(
                proxy, "@upstream", "@discovery", "@v3-api", "api/");
      }
      assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
          () -> unsafe.proxyV3Url(proxy, "collections"), advertised);
    }
  }

  @Test
  void followsBoundedVersionPaginationAndRejectsInvalidVersions() throws Exception {
    AnsibleGalaxyService paged = spy(service);
    doReturn("https://galaxy.example/api/v3/versions?page=1")
        .when(paged).proxyV3Url(eq(proxy), anyString());
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("inventory")));
    when(fetcher.fetch(any()))
        .thenReturn(jsonResult(200, Map.of(
            "data", List.of(Map.of("version", "2.0.0")),
            "links", Map.of("next", "?page=2"))))
        .thenReturn(jsonResult(200, Map.of(
            "data", List.of(Map.of("version", "1.0.0")), "links", Map.of("next", ""))));
    assertEquals(List.of("2.0.0", "1.0.0"),
        paged.fetchProxyVersionNames(proxy, "acme", "tools"));

    AnsibleGalaxyRegistryDao invalidRegistry = mock(AnsibleGalaxyRegistryDao.class);
    HttpRemoteFetcher invalidFetcher = mock(HttpRemoteFetcher.class);
    AnsibleGalaxyService invalid = spy(service(invalidRegistry, invalidFetcher, assets, inspector));
    doReturn("https://galaxy.example/api/v3/versions")
        .when(invalid).proxyV3Url(eq(proxy), anyString());
    when(invalidRegistry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("invalid-inventory")));
    when(invalidFetcher.fetch(any())).thenReturn(jsonResult(
        200, Map.of("data", List.of(Map.of("version", "not-semver")))));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> invalid.fetchProxyVersionNames(proxy, "acme", "tools"));

    AnsibleGalaxyRegistryDao cyclicRegistry = mock(AnsibleGalaxyRegistryDao.class);
    HttpRemoteFetcher cyclicFetcher = mock(HttpRemoteFetcher.class);
    AnsibleGalaxyService unbounded = spy(service(cyclicRegistry, cyclicFetcher, assets, inspector));
    String cycleUrl = "https://galaxy.example/api/v3/versions";
    doReturn(cycleUrl).when(unbounded).proxyV3Url(eq(proxy), anyString());
    when(cyclicRegistry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("cyclic-inventory")));
    when(cyclicFetcher.fetch(any())).thenReturn(jsonResult(200, Map.of(
        "data", List.of(Map.of("version", "1.0.0")),
        "links", Map.of("next", cycleUrl))));
    assertThrows(AnsibleGalaxyExceptions.BadUpstream.class,
        () -> unbounded.fetchProxyVersionNames(proxy, "acme", "tools"));
  }

  @Test
  void repairsAnInventoryHeaderWhoseCountDoesNotMatchItsNormalizedRows() throws Exception {
    AnsibleGalaxyService repairing = spy(service);
    Instant now = Instant.now();
    AnsibleGalaxyRegistryDao.ProxyInventory inconsistent =
        new AnsibleGalaxyRegistryDao.ProxyInventory(
            proxy.id(), "acme", "tools", now.minusSeconds(1), now.minusSeconds(2),
            4L, 2, now.minusSeconds(2));
    when(registry.findProxyInventory(proxy.id(), "acme", "tools"))
        .thenReturn(Optional.of(inconsistent));
    when(registry.listProxyInventoryVersionNames(proxy.id(), "acme", "tools"))
        .thenReturn(List.of("1.0.0"));
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.of(lease("repair-inventory")));
    doReturn("https://galaxy.example/api/v3/versions")
        .when(repairing).proxyV3Url(eq(proxy), anyString());
    when(fetcher.fetch(any())).thenReturn(jsonResult(
        200, Map.of("data", List.of(Map.of("version", "1.0.0")))));

    assertEquals(List.of("1.0.0"),
        repairing.fetchProxyVersionNames(proxy, "acme", "tools"));

    verify(registry).replaceProxyInventory(any(), eq(List.of("1.0.0")));
    verify(registry, never()).touchProxyInventory(
        eq(proxy.id()), eq("acme"), eq("tools"), any(), any());
    verify(registry).releaseLease("repair-inventory", "owner", 7L);
  }

  private AnsibleGalaxyService service(
      AnsibleGalaxyRegistryDao dao,
      HttpRemoteFetcher remote,
      AnsibleGalaxyAssetSupport assetSupport,
      AnsibleCollectionArchiveInspector archiveInspector) {
    return new AnsibleGalaxyService(
        mapper, dao, assetSupport, archiveInspector, remote, mock(RepositoryRuntimeRegistry.class),
        new AnsibleImportTaskLeaseManager(dao));
  }

  private Map<String, Object> detail(String sha) {
    return Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "href", "/root/api/v3/collections/acme/tools/versions/1.2.3/",
        "download_url", "/download/" + FILENAME,
        "artifact", Map.of("filename", FILENAME, "sha256", sha),
        "files", List.of("large metadata is intentionally projected out"));
  }

  private AnsibleGalaxyRegistryDao.ProxyVersionState state(
      Map<String, Object> identity,
      Instant cacheUntil,
      Integer negativeStatus,
      Instant negativeUntil,
      String sha) {
    return new AnsibleGalaxyRegistryDao.ProxyVersionState(
        proxy.id(), "acme", "tools", "1.2.3", sha == null ? null : FILENAME,
        "https://galaxy.example/root/api/v3/detail",
        sha == null ? null : "https://galaxy.example/download/" + FILENAME,
        sha, "etag", "Tue, 21 Jul 2026 08:00:00 GMT", cacheUntil, Instant.now(),
        negativeStatus, negativeUntil, identity, 1L, Instant.now());
  }

  private static AnsibleGalaxyRegistryDao.Lease lease(String key) {
    return new AnsibleGalaxyRegistryDao.Lease(
        key, "owner", 7L, Instant.now().plusSeconds(60), Instant.now());
  }

  private HttpRemoteFetcher.Result jsonResult(int status, Map<String, Object> body)
      throws Exception {
    return result(status, Map.of("Content-Type", "application/json", "ETag", "\"new\""),
        new ByteArrayInputStream(mapper.writeValueAsBytes(body)));
  }

  private static HttpRemoteFetcher.Result result(
      int status, Map<String, String> headers, ByteArrayInputStream body) {
    return new HttpRemoteFetcher.Result(status, headers, body);
  }

  private static AnsibleCollectionArchiveInspector.InspectedCollection inspected(
      Path file, String sha) throws IOException {
    return new AnsibleCollectionArchiveInspector.InspectedCollection(
        file, Files.size(file), sha, FILENAME, "acme", "tools", "1.2.3",
        Map.of("description", "fixture"), Map.of(), null);
  }

  private static AssetRecord asset(long id, long blobId) {
    return new AssetRecord(
        id, 20L, 11L, blobId, RepositoryFormat.ANSIBLEGALAXY,
        "api/v3/plugin/ansible/content/published/collections/artifacts/" + FILENAME,
        new byte[32], FILENAME, "ansible-collection", "application/octet-stream",
        4L, null, Instant.now(), Map.of());
  }

  private static AssetBlobRecord blob(long id, long size, String sha) {
    return new AssetBlobRecord(
        id, 1L, "blob", new byte[32], "object", new byte[32], null, sha, null,
        size, "application/octet-stream", "proxy", null,
        Instant.now(), Instant.now(), Map.of());
  }

  private static AnsibleGalaxyRegistryDao.CollectionVersion version(
      long repositoryId, long id, long assetId) {
    Instant now = Instant.now();
    return new AnsibleGalaxyRegistryDao.CollectionVersion(
        id, repositoryId, 11L, assetId, "acme", "acme", "tools", "tools",
        "1.2.3", "1.2.3", FILENAME, SHA_A, 4L, Map.of(), Map.of(), null,
        "PROXY", 1L, AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, String remote, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "ansible-" + id, RepositoryFormat.ANSIBLEGALAXY, type,
        "ansiblegalaxy-" + type.name().toLowerCase(), true, 1L, "ALLOW_ONCE",
        null, null, true, remote, 60, 60, members);
  }
}
