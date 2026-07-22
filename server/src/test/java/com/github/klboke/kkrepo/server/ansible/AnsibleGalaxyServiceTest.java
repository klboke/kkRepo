package com.github.klboke.kkrepo.server.ansible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnsibleGalaxyServiceTest {
  private static final String BASE = "https://repo.example/repository/ansible-group/";
  private static final String SHA_A = "a".repeat(64);
  private static final String SHA_B = "b".repeat(64);

  private ObjectMapper mapper;
  private AnsibleGalaxyRegistryDao registry;
  private AnsibleGalaxyAssetSupport assets;
  private AnsibleCollectionArchiveInspector inspector;
  private HttpRemoteFetcher fetcher;
  private AnsibleGalaxyService service;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    registry = mock(AnsibleGalaxyRegistryDao.class);
    assets = mock(AnsibleGalaxyAssetSupport.class);
    inspector = mock(AnsibleCollectionArchiveInspector.class);
    fetcher = mock(HttpRemoteFetcher.class);
    service = new AnsibleGalaxyService(
        mapper,
        registry,
        assets,
        inspector,
        fetcher,
        mock(RepositoryRuntimeRegistry.class),
        new AnsibleImportTaskLeaseManager(registry));
  }

  @Test
  void groupBindsVersionMetadataAndArtifactToTheSamePriorityMember() throws Exception {
    RepositoryRuntime first = runtime(1L, "ansible-first", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime second = runtime(2L, "ansible-second", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        3L, "ansible-group", RepositoryType.GROUP, null, List.of(first, second));
    AnsibleGalaxyRegistryDao.CollectionVersion version = version(first.id(), 100L, 20L);

    when(registry.currentGroupConfigRevision(group.id())).thenReturn(7L);
    when(registry.currentRepositoryRevision(first.id())).thenReturn(11L);
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findVersion(first.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(version));
    when(registry.findVersion(second.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);

    Map<String, Object> detail = json(service.get(
        group,
        "api/v3/collections/acme/tools/versions/1.2.3/",
        null,
        BASE,
        false,
        "alice"));

    assertEquals("1.2.3", detail.get("version"));
    assertTrue(detail.get("download_url").toString().startsWith(BASE));
    assertTrue(detail.get("href").toString().startsWith(BASE));
    ArgumentCaptor<AnsibleGalaxyRegistryDao.GroupBinding> bindingCaptor =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.GroupBinding.class);
    verify(registry).bindGroupSourceIfCurrent(bindingCaptor.capture());
    AnsibleGalaxyRegistryDao.GroupBinding binding = bindingCaptor.getValue();
    assertEquals(group.id(), binding.groupRepositoryId());
    assertEquals(first.id(), binding.memberRepositoryId());
    assertEquals(version.id(), binding.memberVersionId());
    assertEquals(version.artifactSha256(), binding.artifactSha256());

    when(registry.findGroupBindingByArtifactFilename(group.id(), version.artifactFilename()))
        .thenReturn(Optional.of(binding));
    when(registry.currentCoordinateSha256(first.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(SHA_A));
    when(registry.findVersionById(version.id())).thenReturn(Optional.of(version));
    when(assets.serve(first.id(), version.artifactAssetId(), false))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            3,
            "application/octet-stream",
            SHA_A,
            Instant.now()));

    MavenResponse artifact = service.get(
        group,
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + version.artifactFilename(),
        null,
        BASE,
        false,
        "alice");

    assertEquals(200, artifact.status());
    verify(assets).serve(first.id(), version.artifactAssetId(), false);
  }

  @Test
  void groupMetadataFailsClosedWhenTheFencedBindingLosesARevisionRace() {
    RepositoryRuntime member = runtime(
        50L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        51L, "ansible-group", RepositoryType.GROUP, null, List.of(member));
    AnsibleGalaxyRegistryDao.CollectionVersion version = version(member.id(), 400L, 60L);
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(7L);
    when(registry.currentRepositoryRevision(member.id())).thenReturn(11L);
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findVersion(member.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(version));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(false);

    AnsibleGalaxyExceptions.ServiceUnavailable failure = assertThrows(
        AnsibleGalaxyExceptions.ServiceUnavailable.class,
        () -> service.get(
            group, "api/v3/collections/acme/tools/versions/1.2.3/",
            null, BASE, false, "alice"));

    assertTrue(failure.getMessage().contains("retry"));
  }

  @Test
  void groupArtifactFailsClosedWhenTheFencedBindingLosesARevisionRace() {
    RepositoryRuntime member = runtime(
        52L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        53L, "ansible-group", RepositoryType.GROUP, null, List.of(member));
    AnsibleGalaxyRegistryDao.CollectionVersion version = version(member.id(), 401L, 61L);
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(8L);
    when(registry.currentRepositoryRevision(member.id())).thenReturn(12L);
    when(registry.findGroupBindingByArtifactFilename(
        group.id(), version.artifactFilename())).thenReturn(Optional.empty());
    when(registry.findVersionByArtifactFilename(member.id(), version.artifactFilename()))
        .thenReturn(Optional.of(version));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(false);

    AnsibleGalaxyExceptions.ServiceUnavailable failure = assertThrows(
        AnsibleGalaxyExceptions.ServiceUnavailable.class,
        () -> service.get(
            group,
            "api/v3/plugin/ansible/content/published/collections/artifacts/"
                + version.artifactFilename(),
            null, BASE, false, "alice"));

    assertTrue(failure.getMessage().contains("retry"));
    verify(assets, never()).serve(member.id(), version.artifactAssetId(), false);
  }

  @Test
  void groupSkipsOfflineMembersForListsDetailsBindingsAndArtifacts() throws Exception {
    RepositoryRuntime offline = runtime(
        20L, "ansible-offline", RepositoryType.HOSTED, null, List.of(), false);
    RepositoryRuntime online = runtime(
        21L, "ansible-online", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        22L, "ansible-group", RepositoryType.GROUP, null, List.of(offline, online));
    AnsibleGalaxyRegistryDao.CollectionVersion offlineVersion = version(offline.id(), 200L, 30L);
    AnsibleGalaxyRegistryDao.CollectionVersion onlineVersion = version(online.id(), 201L, 31L);
    AnsibleGalaxyRegistryDao.GroupBinding offlineBinding =
        new AnsibleGalaxyRegistryDao.GroupBinding(
            group.id(), "acme", "tools", "1.2.3", offline.id(), offlineVersion.id(),
            offlineVersion.artifactFilename(), 4L, 9L, offlineVersion.artifactSha256(),
            Instant.now(), Instant.now());
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(9L);
    when(registry.currentRepositoryRevision(online.id())).thenReturn(5L);
    when(registry.listVersionNames(online.id(), "acme", "tools"))
        .thenReturn(List.of("1.2.3"));
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(offlineBinding));
    when(registry.findGroupBindingByArtifactFilename(
        group.id(), onlineVersion.artifactFilename()))
        .thenReturn(Optional.of(offlineBinding));
    when(registry.findVersion(online.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(onlineVersion));
    when(registry.findVersionByArtifactFilename(
        online.id(), onlineVersion.artifactFilename()))
        .thenReturn(Optional.of(onlineVersion));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);
    when(assets.serve(online.id(), onlineVersion.artifactAssetId(), false))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[] {1}), 1L,
            "application/octet-stream", onlineVersion.artifactSha256(), Instant.EPOCH));

    Map<String, Object> versions = json(service.get(
        group, "api/v3/collections/acme/tools/versions/", null, BASE, false, "alice"));
    assertEquals(1, map(versions.get("meta")).get("count"));
    assertEquals(200, service.get(
        group, "api/v3/collections/acme/tools/versions/1.2.3/",
        null, BASE, false, "alice").status());
    assertEquals(200, service.get(
        group,
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + onlineVersion.artifactFilename(),
        null, BASE, false, "alice").status());

    verify(registry, never()).listVersionNames(offline.id(), "acme", "tools");
    verify(registry, never()).findVersion(offline.id(), "acme", "tools", "1.2.3");
    verify(registry, never()).findVersionByArtifactFilename(
        offline.id(), onlineVersion.artifactFilename());
    verify(registry, never()).findVersionById(offlineVersion.id());
    verify(assets).serve(online.id(), onlineVersion.artifactAssetId(), false);
  }

  @Test
  void groupListsHostedVersionsWhenLaterProxyFails() throws Exception {
    RepositoryRuntime hosted = runtime(
        30L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxy = runtime(
        31L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime group = runtime(
        32L, "ansible-group", RepositoryType.GROUP, null, List.of(hosted, proxy));
    when(registry.listVersionNames(hosted.id(), "acme", "tools"))
        .thenReturn(List.of("1.2.3"));
    when(registry.listVersionNames(proxy.id(), "acme", "tools"))
        .thenReturn(List.of());
    AnsibleGalaxyService resilient = withFailingProxy(proxy);

    Map<String, Object> versions = json(resilient.get(
        group, "api/v3/collections/acme/tools/versions/", null, BASE, false, "alice"));

    List<?> data = (List<?>) versions.get("data");
    assertEquals(1, map(versions.get("meta")).get("count"));
    assertEquals("1.2.3", map(data.getFirst()).get("version"));
  }

  @Test
  void proxyAndGroupKeepCachedVersionsDuringUpstreamFailure() throws Exception {
    RepositoryRuntime hosted = runtime(
        33L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxy = runtime(
        34L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime group = runtime(
        35L, "ansible-group", RepositoryType.GROUP, null, List.of(hosted, proxy));
    when(registry.listVersionNames(hosted.id(), "acme", "tools"))
        .thenReturn(List.of("1.2.3"));
    when(registry.listVersionNames(proxy.id(), "acme", "tools"))
        .thenReturn(List.of("1.0.0"));
    AnsibleGalaxyService resilient = withFailingProxy(proxy);

    Map<String, Object> versions = json(resilient.get(
        group, "api/v3/collections/acme/tools/versions/", null, BASE, false, "alice"));

    assertEquals(2, map(versions.get("meta")).get("count"));
  }

  @Test
  void groupSurfacesProxyFailureWhenNoMemberProvidesVersions() {
    RepositoryRuntime hosted = runtime(
        36L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime proxy = runtime(
        37L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime group = runtime(
        38L, "ansible-group", RepositoryType.GROUP, null, List.of(hosted, proxy));
    when(registry.listVersionNames(hosted.id(), "acme", "tools"))
        .thenReturn(List.of());
    when(registry.listVersionNames(proxy.id(), "acme", "tools"))
        .thenReturn(List.of());
    AnsibleGalaxyService resilient = withFailingProxy(proxy);

    AnsibleGalaxyExceptions.BadUpstream failure = assertThrows(
        AnsibleGalaxyExceptions.BadUpstream.class,
        () -> resilient.get(
            group, "api/v3/collections/acme/tools/versions/",
            null, BASE, false, "alice"));

    assertEquals("Upstream Galaxy is unavailable", failure.getMessage());
  }

  @Test
  void groupVersionDetailSkipsFailedProxyWhenLaterHostedMemberMatches() throws Exception {
    RepositoryRuntime proxy = runtime(
        39L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime hosted = runtime(
        40L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        41L, "ansible-group", RepositoryType.GROUP, null, List.of(proxy, hosted));
    AnsibleGalaxyRegistryDao.CollectionVersion hostedVersion =
        version(hosted.id(), 300L, 50L);
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(7L);
    when(registry.currentRepositoryRevision(hosted.id())).thenReturn(11L);
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findVersion(hosted.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(hostedVersion));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);
    AnsibleGalaxyService resilient = withFailingProxy(proxy);

    MavenResponse response = resilient.get(
        group, "api/v3/collections/acme/tools/versions/1.2.3/",
        null, BASE, false, "alice");

    assertEquals(200, response.status());
  }

  @Test
  void groupArtifactSkipsFailedProxyWhenLaterHostedMemberMatches() throws Exception {
    RepositoryRuntime proxy = runtime(
        42L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime hosted = runtime(
        43L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    RepositoryRuntime group = runtime(
        44L, "ansible-group", RepositoryType.GROUP, null, List.of(proxy, hosted));
    AnsibleGalaxyRegistryDao.CollectionVersion hostedVersion =
        version(hosted.id(), 301L, 51L);
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(8L);
    when(registry.currentRepositoryRevision(hosted.id())).thenReturn(12L);
    when(registry.findGroupBindingByArtifactFilename(
        group.id(), hostedVersion.artifactFilename())).thenReturn(Optional.empty());
    when(registry.findVersionByArtifactFilename(
        proxy.id(), hostedVersion.artifactFilename())).thenReturn(Optional.empty());
    when(registry.findProxyStateByArtifactFilename(
        proxy.id(), hostedVersion.artifactFilename())).thenReturn(Optional.of(proxyState(
            proxy.id(), "acme", "tools", "1.2.3", null, Map.of(),
            Instant.now().plusSeconds(60))));
    when(registry.findVersionByArtifactFilename(
        hosted.id(), hostedVersion.artifactFilename())).thenReturn(Optional.of(hostedVersion));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);
    when(assets.serve(hosted.id(), hostedVersion.artifactAssetId(), false))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[] {1}), 1L,
            "application/octet-stream", hostedVersion.artifactSha256(), Instant.EPOCH));

    MavenResponse response = service.get(
        group,
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + hostedVersion.artifactFilename(),
        null, BASE, false, "alice");

    assertEquals(200, response.status());
    verify(assets).serve(hosted.id(), hostedVersion.artifactAssetId(), false);
  }

  @Test
  void versionPaginationReturnsARepositoryRelativeNextLinkForAnsibleClients() throws Exception {
    RepositoryRuntime hosted = runtime(
        8L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    when(registry.listVersionNames(hosted.id(), "acme", "tools"))
        .thenReturn(List.of("2.0.0", "1.0.0"));

    Map<String, Object> page = json(service.get(
        hosted,
        "api/v3/collections/acme/tools/versions/",
        "limit=1&offset=0",
        "https://repo.example/repository/ansible-hosted/",
        false,
        "alice"));

    assertEquals(2, map(page.get("meta")).get("count"));
    assertEquals(
        "/repository/ansible-hosted/api/v3/collections/acme/tools/versions/?limit=1&offset=1",
        map(page.get("links")).get("next"));
  }

  @Test
  void importTasksAreVisibleOnlyToTheirRequester() throws Exception {
    RepositoryRuntime hosted = runtime(
        4L, "ansible-hosted", RepositoryType.HOSTED, null, List.of());
    String taskId = "0dfd1f0d-fa14-4caa-b928-be3ec7c8650e";
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    when(registry.findTask(taskId)).thenReturn(Optional.of(
        new AnsibleGalaxyRegistryDao.ImportTask(
            taskId, hosted.id(), "alice", AnsibleGalaxyRegistryDao.TASK_COMPLETED,
            List.of(Map.of("level", "INFO", "message", "Imported")), null, null,
            "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            SHA_A, SHA_A, null, 1, null, null, 1L, now, now, now, now)));

    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "api/v3/imports/collections/" + taskId + "/", null,
        BASE, false, null));
    assertThrows(AnsibleGalaxyExceptions.NotFound.class, () -> service.get(
        hosted, "api/v3/imports/collections/" + taskId + "/", null,
        BASE, false, "bob"));

    Map<String, Object> task = json(service.get(
        hosted, "api/v3/imports/collections/" + taskId + "/", null,
        BASE, false, "alice"));
    assertEquals("completed", task.get("state"));
    assertEquals(now.toString(), task.get("finished_at"));
  }

  @Test
  void proxyFailsClosedWhenAnImmutableVersionChecksumDrifts() throws Exception {
    RepositoryRuntime proxy = runtime(
        5L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    Instant now = Instant.now();
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findProxyState(proxy.id(), "@upstream", "@discovery", "@v3-root"))
        .thenReturn(Optional.of(proxyState(
            proxy.id(), "@upstream", "@discovery", "@v3-root", null,
            Map.of("available_versions", Map.of("v3", "api/v3/")),
            now.plusSeconds(60))));
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(proxyState(
            proxy.id(), "acme", "tools", "1.2.3", SHA_A,
            Map.of("artifact", Map.of("sha256", SHA_A)), now.minusSeconds(1))));
    when(registry.tryAcquireLease(any(), any(), any())).thenReturn(Optional.of(
        new AnsibleGalaxyRegistryDao.Lease("lease", "owner", 1L,
            now.plusSeconds(60), now)));
    when(fetcher.fetch(any())).thenReturn(jsonResult(Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "href", "/api/v3/collections/acme/tools/versions/1.2.3/",
        "download_url", "/api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz",
        "artifact", Map.of(
            "filename", "acme-tools-1.2.3.tar.gz",
            "sha256", SHA_B))));

    AnsibleGalaxyExceptions.BadUpstream failure = assertThrows(
        AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.get(
            proxy,
            "api/v3/collections/acme/tools/versions/1.2.3/",
            null,
            BASE,
            false,
            "alice"));

    assertTrue(failure.getMessage().contains("immutable"));
    verify(registry).releaseLease("lease", "owner", 1L);
  }

  @Test
  void proxyVersionMetadataDoesNotMaterializeTheCollectionArchive() throws Exception {
    RepositoryRuntime proxy = runtime(
        45L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    Instant now = Instant.now();
    Map<String, Object> upstream = Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "href", "/api/v3/collections/acme/tools/versions/1.2.3/",
        "download_url", "/api/v3/artifacts/acme-tools-1.2.3.tar.gz",
        "requires_ansible", ">=2.15",
        "artifact", Map.of(
            "filename", "acme-tools-1.2.3.tar.gz", "sha256", SHA_A, "size", 123L),
        "metadata", Map.of(
            "description", "projected metadata",
            "dependencies", Map.of("acme.base", ">=1.0.0")));
    Map<String, Object> projected =
        AnsibleGalaxyService.projectProxyDocument("1.2.3", upstream);
    AnsibleGalaxyRegistryDao.ProxyVersionState exact =
        new AnsibleGalaxyRegistryDao.ProxyVersionState(
            proxy.id(), "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            "https://galaxy.example/api/v3/collections/acme/tools/versions/1.2.3/",
            "https://galaxy.example/api/v3/artifacts/acme-tools-1.2.3.tar.gz",
            SHA_A, null, null, now.plusSeconds(60), now, null, null,
            projected, 4L, now);
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findProxyState(proxy.id(), "@upstream", "@discovery", "@v3-root"))
        .thenReturn(Optional.of(proxyState(
            proxy.id(), "@upstream", "@discovery", "@v3-root", null,
            Map.of("available_versions", Map.of("v3", "api/v3/")), now.plusSeconds(60))));
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(exact));
    AnsibleGalaxyService metadataOnly = spy(service);

    Map<String, Object> detail = json(metadataOnly.get(
        proxy, "api/v3/collections/acme/tools/versions/1.2.3/",
        null, BASE, false, "alice"));

    assertEquals(SHA_A, map(detail.get("artifact")).get("sha256"));
    assertEquals(123L, ((Number) map(detail.get("artifact")).get("size")).longValue());
    assertEquals(">=1.0.0",
        map(map(detail.get("metadata")).get("dependencies")).get("acme.base"));
    assertEquals("projected metadata", map(detail.get("metadata")).get("description"));
    verify(metadataOnly, never()).materializeProxy(proxy, exact);
    verify(fetcher, never()).fetch(any());
    verify(inspector, never()).inspect(any());
    verify(assets, never()).storeCollection(any(), anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void groupBindsProjectedProxyMetadataBeforeArtifactMaterialization() throws Exception {
    RepositoryRuntime proxy = runtime(
        46L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime group = runtime(
        47L, "ansible-group", RepositoryType.GROUP, null, List.of(proxy));
    Instant now = Instant.now();
    Map<String, Object> projected = AnsibleGalaxyService.projectProxyDocument(
        "1.2.3", Map.of(
            "namespace", Map.of("name", "acme"),
            "collection", Map.of("name", "tools"),
            "version", "1.2.3",
            "download_url", "/artifacts/acme-tools-1.2.3.tar.gz",
            "artifact", Map.of("filename", "acme-tools-1.2.3.tar.gz", "sha256", SHA_A)));
    AnsibleGalaxyRegistryDao.ProxyVersionState exact =
        new AnsibleGalaxyRegistryDao.ProxyVersionState(
            proxy.id(), "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            null, "https://galaxy.example/artifacts/acme-tools-1.2.3.tar.gz",
            SHA_A, null, null, now.plusSeconds(60), now, null, null,
            projected, 4L, now);
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(7L);
    when(registry.currentRepositoryRevision(proxy.id())).thenReturn(4L);
    when(registry.findGroupBinding(group.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.findProxyState(proxy.id(), "@upstream", "@discovery", "@v3-root"))
        .thenReturn(Optional.of(proxyState(
            proxy.id(), "@upstream", "@discovery", "@v3-root", null,
            Map.of("available_versions", Map.of("v3", "api/v3/")), now.plusSeconds(60))));
    when(registry.findProxyState(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(exact));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);
    AnsibleGalaxyService metadataOnly = spy(service);

    assertEquals(200, metadataOnly.get(
        group, "api/v3/collections/acme/tools/versions/1.2.3/",
        null, BASE, false, "alice").status());

    ArgumentCaptor<AnsibleGalaxyRegistryDao.GroupBinding> binding =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.GroupBinding.class);
    verify(registry).bindGroupSourceIfCurrent(binding.capture());
    assertNull(binding.getValue().memberVersionId());
    assertEquals("acme-tools-1.2.3.tar.gz", binding.getValue().artifactFilename());
    assertEquals(SHA_A, binding.getValue().artifactSha256());
    verify(metadataOnly, never()).materializeProxy(proxy, exact);
  }

  @Test
  void groupArtifactMaterializesTheMetadataBoundProxyMember() throws Exception {
    RepositoryRuntime proxy = runtime(
        48L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    RepositoryRuntime group = runtime(
        49L, "ansible-group", RepositoryType.GROUP, null, List.of(proxy));
    Instant now = Instant.now();
    AnsibleGalaxyRegistryDao.ProxyVersionState exact =
        new AnsibleGalaxyRegistryDao.ProxyVersionState(
            proxy.id(), "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            null, "https://galaxy.example/artifacts/acme-tools-1.2.3.tar.gz",
            SHA_A, null, null, now.plusSeconds(60), now, null, null,
            Map.of("artifact", Map.of(
                "filename", "acme-tools-1.2.3.tar.gz", "sha256", SHA_A)), 4L, now);
    AnsibleGalaxyRegistryDao.GroupBinding projectedBinding =
        new AnsibleGalaxyRegistryDao.GroupBinding(
            group.id(), "acme", "tools", "1.2.3", proxy.id(), null,
            "acme-tools-1.2.3.tar.gz", 4L, 7L, SHA_A, now, now);
    AnsibleGalaxyRegistryDao.CollectionVersion stored = version(proxy.id(), 302L, 52L);
    when(registry.currentGroupConfigRevision(group.id())).thenReturn(7L);
    when(registry.currentRepositoryRevision(proxy.id())).thenReturn(4L);
    when(registry.findGroupBindingByArtifactFilename(
        group.id(), "acme-tools-1.2.3.tar.gz")).thenReturn(Optional.of(projectedBinding));
    when(registry.currentCoordinateSha256(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.of(SHA_A));
    when(registry.findVersionByArtifactFilename(proxy.id(), "acme-tools-1.2.3.tar.gz"))
        .thenReturn(Optional.empty());
    when(registry.findProxyStateByArtifactFilename(
        proxy.id(), "acme-tools-1.2.3.tar.gz")).thenReturn(Optional.of(exact));
    when(registry.bindGroupSourceIfCurrent(any())).thenReturn(true);
    when(assets.serve(proxy.id(), stored.artifactAssetId(), false))
        .thenReturn(MavenResponse.ok(
            new ByteArrayInputStream(new byte[] {1}), 1L,
            "application/octet-stream", stored.artifactSha256(), now));
    AnsibleGalaxyService bound = spy(service);
    doReturn(stored).when(bound).materializeProxy(proxy, exact);

    MavenResponse response = bound.get(
        group,
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz",
        null, BASE, false, "alice");

    assertEquals(200, response.status());
    verify(bound).materializeProxy(proxy, exact);
    ArgumentCaptor<AnsibleGalaxyRegistryDao.GroupBinding> promoted =
        ArgumentCaptor.forClass(AnsibleGalaxyRegistryDao.GroupBinding.class);
    verify(registry).bindGroupSourceIfCurrent(promoted.capture());
    assertEquals(stored.id(), promoted.getValue().memberVersionId());
    assertEquals(proxy.id(), promoted.getValue().memberRepositoryId());
    verify(assets).serve(proxy.id(), stored.artifactAssetId(), false);
  }

  @Test
  void proxyRejectsCyclicUpstreamPagination() throws Exception {
    RepositoryRuntime proxy = runtime(
        6L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.example/", List.of());
    Instant now = Instant.now();
    when(registry.findProxyState(proxy.id(), "@upstream", "@discovery", "@v3-root"))
        .thenReturn(Optional.of(proxyState(
            proxy.id(), "@upstream", "@discovery", "@v3-root", null,
            Map.of("available_versions", Map.of("v3", "api/v3/")),
            now.plusSeconds(60))));
    when(registry.findProxyState(proxy.id(), "acme", "tools", "@versions"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(any(), any(), any())).thenReturn(Optional.of(
        new AnsibleGalaxyRegistryDao.Lease("lease", "owner", 1L,
            now.plusSeconds(60), now)));
    when(fetcher.fetch(any())).thenReturn(jsonResult(Map.of(
        "data", List.of(Map.of("version", "1.2.3")),
        "links", Map.of("next", "?limit=1000"))));

    AnsibleGalaxyExceptions.BadUpstream failure = assertThrows(
        AnsibleGalaxyExceptions.BadUpstream.class,
        () -> service.get(
            proxy,
            "api/v3/collections/acme/tools/versions/",
            null,
            BASE,
            false,
            "alice"));

    assertTrue(failure.getMessage().contains("cycle"));
  }

  @Test
  void proxyArtifactUsesWildcardAcceptForGalaxySignedDownloadNegotiation() throws Exception {
    RepositoryRuntime proxy = runtime(
        7L, "ansible-proxy", RepositoryType.PROXY,
        "https://galaxy.ansible.com/", List.of());
    Instant now = Instant.now();
    Map<String, Object> detail = Map.of(
        "namespace", Map.of("name", "acme"),
        "collection", Map.of("name", "tools"),
        "version", "1.2.3",
        "download_url", "https://galaxy.ansible.com/api/v3/artifacts/acme-tools-1.2.3.tar.gz",
        "artifact", Map.of(
            "filename", "acme-tools-1.2.3.tar.gz",
            "sha256", SHA_A));
    AnsibleGalaxyRegistryDao.ProxyVersionState exact =
        new AnsibleGalaxyRegistryDao.ProxyVersionState(
            proxy.id(), "acme", "tools", "1.2.3", "acme-tools-1.2.3.tar.gz",
            null,
            "https://galaxy.ansible.com/api/v3/artifacts/acme-tools-1.2.3.tar.gz",
            SHA_A, null, null, now.plusSeconds(60), now, null, null, detail, 1L, now);
    when(registry.findVersionByArtifactFilename(proxy.id(), "acme-tools-1.2.3.tar.gz"))
        .thenReturn(Optional.empty());
    when(registry.findProxyStateByArtifactFilename(
        proxy.id(), "acme-tools-1.2.3.tar.gz"))
        .thenReturn(Optional.of(exact));
    when(registry.findVersion(proxy.id(), "acme", "tools", "1.2.3"))
        .thenReturn(Optional.empty());
    when(registry.tryAcquireLease(any(), any(), any())).thenReturn(Optional.of(
        new AnsibleGalaxyRegistryDao.Lease(
            "artifact-lease", "owner", 1L, now.plusSeconds(60), now)));
    when(fetcher.fetch(any())).thenReturn(new HttpRemoteFetcher.Result(
        200, Map.of("Content-Type", "application/gzip"),
        new ByteArrayInputStream(new byte[] {0x1f, (byte) 0x8b})));
    when(inspector.inspect(any())).thenThrow(
        new AnsibleGalaxyExceptions.BadRequest("inspection sentinel"));

    assertThrows(AnsibleGalaxyExceptions.BadRequest.class, () -> service.get(
        proxy,
        "api/v3/plugin/ansible/content/published/collections/artifacts/"
            + "acme-tools-1.2.3.tar.gz",
        null,
        BASE,
        false,
        "alice"));

    ArgumentCaptor<HttpRemoteFetcher.Request> request =
        ArgumentCaptor.forClass(HttpRemoteFetcher.Request.class);
    verify(fetcher).fetch(request.capture());
    assertEquals("*/*", request.getValue().accept());
    assertTrue(request.getValue().allowedUnsignedRedirectHosts().contains("*"));
    verify(registry).releaseLease("artifact-lease", "owner", 1L);
  }

  @Test
  void proxyProjectionKeepsLargeUpstreamIndexesOutOfDatabaseMetadata() throws Exception {
    Map<String, Object> upstream = Map.of(
        "namespace", Map.of("name", "community"),
        "collection", Map.of("name", "general"),
        "version", "1.2.3",
        "href", "/api/v3/collections/community/general/versions/1.2.3/",
        "download_url", "/api/v3/artifacts/community-general-1.2.3.tar.gz",
        "artifact", Map.of(
            "filename", "community-general-1.2.3.tar.gz",
            "sha256", SHA_A,
            "size", 123L),
        "metadata", Map.of(
            "description", "bounded projection",
            "dependencies", Map.of("acme.base", ">=1.0.0"),
            "contents", List.of("x".repeat(512 * 1024))),
        "files", List.of("y".repeat(512 * 1024)),
        "signatures", List.of(Map.of("signature", "z".repeat(512 * 1024))));

    Map<String, Object> projected =
        AnsibleGalaxyService.projectProxyDocument("1.2.3", upstream);

    assertEquals("1.2.3", projected.get("version"));
    assertEquals("bounded projection", map(projected.get("metadata")).get("description"));
    assertEquals(">=1.0.0",
        map(map(projected.get("metadata")).get("dependencies")).get("acme.base"));
    assertFalse(map(projected.get("metadata")).containsKey("contents"));
    assertFalse(projected.containsKey("files"));
    assertFalse(projected.containsKey("signatures"));
    assertTrue(mapper.writeValueAsBytes(projected).length < 2048);
  }

  private Map<String, Object> json(MavenResponse response) throws Exception {
    try (var body = response.body()) {
      return mapper.readValue(body, new TypeReference<>() { });
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private HttpRemoteFetcher.Result jsonResult(Map<String, Object> body) throws Exception {
    byte[] bytes = mapper.writeValueAsBytes(body);
    return new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "application/json"),
        new ByteArrayInputStream(bytes));
  }

  private AnsibleGalaxyService withFailingProxy(RepositoryRuntime proxy) {
    AnsibleGalaxyService resilient = spy(service);
    doThrow(new AnsibleGalaxyExceptions.BadUpstream("Upstream Galaxy is unavailable"))
        .when(resilient)
        .fetchProxyVersionNames(eq(proxy), anyString(), anyString());
    doThrow(new AnsibleGalaxyExceptions.BadUpstream("Upstream Galaxy is unavailable"))
        .when(resilient)
        .fetchProxyDocument(eq(proxy), anyString(), anyString(), anyString(), anyString());
    return resilient;
  }

  private static AnsibleGalaxyRegistryDao.ProxyVersionState proxyState(
      long repositoryId,
      String namespace,
      String name,
      String version,
      String sha256,
      Map<String, Object> identity,
      Instant cacheUntil) {
    Instant now = Instant.now();
    return new AnsibleGalaxyRegistryDao.ProxyVersionState(
        repositoryId, namespace, name, version,
        sha256 == null ? null : "acme-tools-1.2.3.tar.gz",
        null, null, sha256, null, null, cacheUntil, now,
        null, null, identity, 1L, now);
  }

  private static AnsibleGalaxyRegistryDao.CollectionVersion version(
      long repositoryId, long id, long assetId) {
    Instant now = Instant.parse("2026-07-21T08:00:00Z");
    return new AnsibleGalaxyRegistryDao.CollectionVersion(
        id, repositoryId, 10L, assetId,
        "acme", "acme", "tools", "tools", "1.2.3", "1.2.3",
        "acme-tools-1.2.3.tar.gz", SHA_A, 3L,
        Map.of("description", "fixture", "authors", List.of("kkRepo")),
        Map.of("acme.base", ">=1.0.0"), ">=2.15", "HOSTED", 11L,
        AnsibleGalaxyRegistryDao.VERSION_READY, now, now, now);
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      RepositoryType type,
      String remote,
      List<RepositoryRuntime> members) {
    return runtime(id, name, type, remote, members, true);
  }

  private static RepositoryRuntime runtime(
      long id,
      String name,
      RepositoryType type,
      String remote,
      List<RepositoryRuntime> members,
      boolean online) {
    return new RepositoryRuntime(
        id, name, RepositoryFormat.ANSIBLEGALAXY, type,
        "ansiblegalaxy-" + type.name().toLowerCase(),
        online, 1L, "ALLOW_ONCE", null, null, true,
        remote, 60, 60, members);
  }
}
