package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.AccessDecisionService;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.swift.SwiftMediaTypes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockPart;

class SwiftServiceTest {

  @Test
  void releaseListReturnsReadyAndUnavailableVersionsInSemverOrderWithProtocolHeaders()
      throws Exception {
    Fixture fixture = fixture();
    when(fixture.registry.listReleases(1L, "acme", "demo")).thenReturn(List.of(
        release(1L, "1.0.0", SwiftRegistryDao.RELEASE_READY),
        release(2L, "2.0.0-beta.1", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.registry.listTombstones(1L, "acme", "demo")).thenReturn(List.of(
        new SwiftRegistryDao.Tombstone(
            1L, "acme", "demo", "9.9.9", "administratively deleted", 3L, Instant.EPOCH)));

    MavenResponse response = fixture.service.get(
        fixture.runtime,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    assertEquals(SwiftMediaTypes.JSON, response.contentType());
    assertEquals("1", response.headers().get("Content-Version"));
    assertEquals(64, response.etag().length());
    assertTrue(response.headers().get("Link").contains("2.0.0-beta.1"));
    String body;
    try (InputStream input = response.body()) {
      body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(body.indexOf("2.0.0-beta.1") < body.indexOf("1.0.0"));
    assertTrue(body.contains("\"9.9.9\""));
    assertTrue(body.contains("\"problem\""));
    assertTrue(body.contains("\"status\":410"));
    assertTrue(body.contains("administratively deleted"));
    assertTrue(body.contains("https://repo.example/repository/swift/Acme/Demo/2.0.0-beta.1"));
  }

  @Test
  void hostedReleaseListLoadsRepositoryUrlsInOnePackageQuery() throws Exception {
    Fixture fixture = fixture();
    when(fixture.registry.listReleases(1L, "acme", "demo")).thenReturn(List.of(
        release(1L, "1.0.0", SwiftRegistryDao.RELEASE_READY),
        release(2L, "2.0.0", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.registry.listRepositoryUrls(1L, "acme", "demo")).thenReturn(List.of(
        new SwiftRegistryDao.RepositoryUrl(
            1L, 1L, 1L, "acme", "demo",
            "https://github.com/acme/demo", "https://github.com/Acme/Demo")));

    MavenResponse response = fixture.service.get(
        fixture.runtime,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    assertTrue(response.headers().get("Link").contains("https://github.com/acme/demo"));
    verify(fixture.registry).listRepositoryUrls(1L, "acme", "demo");
    verify(fixture.registry, never()).listRepositoryUrls(1L);
    verify(fixture.registry, never()).listRepositoryUrls(2L);
  }

  @Test
  void proxyReleaseListUsesPersistedDiscoveredVersionsDuringUpstreamRateLimit()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(2L, "swift-proxy");
    SwiftRegistryDao.ProxySource discovered = new SwiftRegistryDao.ProxySource(
        proxy.id(), "apple", "swift-nio", "2.70.0", "https://github.com/apple/swift-nio",
        "2.70.0", "a".repeat(40), "github-zipball-v1", null, "DISCOVERED", null,
        null, 3L, 1L, Instant.EPOCH);
    when(fixture.registry.listProxySources(proxy.id(), "apple", "swift-nio"))
        .thenReturn(List.of(discovered));
    when(fixture.registry.findNegativeCache(proxy.id(), "tags:apple/swift-nio"))
        .thenReturn(Optional.of(new SwiftRegistryDao.NegativeCache(
            proxy.id(), "tags:apple/swift-nio", 429, Instant.now().plusSeconds(30),
            Instant.now().plusSeconds(30), Instant.now())));

    MavenResponse response = fixture.service.get(
        proxy,
        "apple/swift-nio",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    assertTrue(responseBody(response).contains("2.70.0"));
  }

  @Test
  void releaseListHeadPreservesRepresentationLengthWithoutOpeningBody() {
    Fixture fixture = fixture();
    when(fixture.registry.listReleases(1L, "acme", "demo")).thenReturn(List.of(
        release(1L, "1.0.0", SwiftRegistryDao.RELEASE_READY)));

    MavenResponse response = fixture.service.get(
        fixture.runtime,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift/",
        null,
        true);

    assertEquals(200, response.status());
    assertNull(response.body());
    assertTrue(response.contentLength() > 0);
    assertEquals(SwiftMediaTypes.JSON, response.contentType());
  }

  @Test
  void groupReleaseListEtagTracksGroupAndMemberRevisionsWhenBodyIsUnchanged()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime member = hosted(1L, "swift-member");
    RepositoryRuntime group = group(2L, List.of(member));
    when(fixture.runtimes.resolveById(group.id())).thenReturn(Optional.of(group));
    when(fixture.registry.listReleases(1L, "acme", "demo")).thenReturn(List.of(
        release(11L, member.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.registry.currentRepositoryRevision(group.id())).thenReturn(10L, 11L, 11L);
    when(fixture.registry.currentRepositoryRevision(member.id())).thenReturn(3L, 3L, 4L);

    MavenResponse initial = fixture.service.get(
        group, "Acme/Demo", null, "https://repo.example/repository/swift/", null, false);
    MavenResponse groupChanged = fixture.service.get(
        group, "Acme/Demo", null, "https://repo.example/repository/swift/", null, false);
    MavenResponse memberChanged = fixture.service.get(
        group, "Acme/Demo", null, "https://repo.example/repository/swift/", null, false);

    String body = responseBody(initial);
    assertEquals(body, responseBody(groupChanged));
    assertEquals(body, responseBody(memberChanged));
    assertNotEquals(initial.etag(), groupChanged.etag());
    assertNotEquals(groupChanged.etag(), memberChanged.etag());
  }

  @Test
  void groupReleaseListEtagTracksMemberPriorityWhenBodyIsUnchanged() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime firstMember = hosted(1L, "swift-first");
    RepositoryRuntime secondMember = hosted(3L, "swift-second");
    RepositoryRuntime firstPriority = group(2L, List.of(firstMember, secondMember));
    RepositoryRuntime secondPriority = group(2L, List.of(secondMember, firstMember));
    when(fixture.runtimes.resolveById(firstPriority.id()))
        .thenReturn(Optional.of(firstPriority), Optional.of(secondPriority));
    when(fixture.registry.listReleases(firstMember.id(), "acme", "demo")).thenReturn(List.of(
        release(11L, firstMember.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.registry.listReleases(secondMember.id(), "acme", "demo")).thenReturn(List.of(
        release(33L, secondMember.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.registry.currentRepositoryRevision(firstPriority.id())).thenReturn(9L);
    when(fixture.registry.currentRepositoryRevision(firstMember.id())).thenReturn(4L);
    when(fixture.registry.currentRepositoryRevision(secondMember.id())).thenReturn(6L);

    MavenResponse first = fixture.service.get(
        firstPriority, "Acme/Demo", null, "https://repo.example/repository/swift/", null, false);
    MavenResponse second = fixture.service.get(
        secondPriority, "Acme/Demo", null, "https://repo.example/repository/swift/", null, false);

    assertEquals(responseBody(first), responseBody(second));
    assertNotEquals(first.etag(), second.etag());
  }

  @Test
  void groupFirstMemberTombstoneIsTerminalForReleaseListAndResolution() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime firstMember = hosted(1L, "swift-deleted");
    RepositoryRuntime secondMember = hosted(3L, "swift-fallback");
    RepositoryRuntime group = group(2L, List.of(firstMember, secondMember));
    SwiftRegistryDao.Tombstone tombstone = new SwiftRegistryDao.Tombstone(
        firstMember.id(), "acme", "demo", "1.2.3", "permanently deleted", 8L,
        Instant.EPOCH);
    when(fixture.runtimes.resolveById(group.id())).thenReturn(Optional.of(group));
    when(fixture.registry.listTombstones(firstMember.id(), "acme", "demo"))
        .thenReturn(List.of(tombstone));
    when(fixture.registry.listReleases(secondMember.id(), "acme", "demo")).thenReturn(List.of(
        release(33L, secondMember.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.registry.currentRepositoryRevision(group.id())).thenReturn(7L);

    MavenResponse list = fixture.service.get(
        group,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift-group/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    String body = responseBody(list);
    assertTrue(body.contains("\"status\":410"));
    assertTrue(body.contains("permanently deleted"));
    assertFalse(body.contains(
        "https://repo.example/repository/swift-group/Acme/Demo/1.2.3"));
    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.get(
        group,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift-group/",
        SwiftMediaTypes.VENDOR_JSON,
        false));
    verify(fixture.registry, never()).findRelease(
        secondMember.id(), "acme", "demo", "1.2.3");
  }

  @Test
  void groupFallsThroughCachedProxyNotFoundOnEveryReleaseListRequest() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(1L, "swift-proxy");
    RepositoryRuntime hosted = hosted(3L, "swift-hosted");
    RepositoryRuntime group = group(2L, List.of(proxy, hosted));
    when(fixture.runtimes.resolveById(group.id())).thenReturn(Optional.of(group));
    when(fixture.registry.findNegativeCache(proxy.id(), "tags:acme/demo"))
        .thenReturn(Optional.of(new SwiftRegistryDao.NegativeCache(
            proxy.id(), "tags:acme/demo", 404, null, Instant.now().plusSeconds(300),
            Instant.now())));
    when(fixture.registry.listReleases(hosted.id(), "acme", "demo")).thenReturn(List.of(
        release(33L, hosted.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY)));

    MavenResponse first = fixture.service.get(
        group, "Acme/Demo", null,
        "https://repo.example/repository/swift-group/", SwiftMediaTypes.VENDOR_JSON, false);
    MavenResponse second = fixture.service.get(
        group, "Acme/Demo", null,
        "https://repo.example/repository/swift-group/", SwiftMediaTypes.VENDOR_JSON, false);

    assertTrue(responseBody(first).contains("1.2.3"));
    assertTrue(responseBody(second).contains("1.2.3"));
    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.get(
        proxy, "Acme/Demo", null,
        "https://repo.example/repository/swift-proxy/", SwiftMediaTypes.VENDOR_JSON, false));
    verify(fixture.github, never()).tags(any(), any());
  }

  @Test
  void groupDoesNotSwallowCachedProxyRateLimitOrBadGateway() {
    for (int status : List.of(429, 502)) {
      Fixture fixture = fixture();
      RepositoryRuntime proxy = proxy(1L, "swift-proxy");
      RepositoryRuntime hosted = hosted(3L, "swift-hosted");
      RepositoryRuntime group = group(2L, List.of(proxy, hosted));
      when(fixture.runtimes.resolveById(group.id())).thenReturn(Optional.of(group));
      when(fixture.registry.findNegativeCache(proxy.id(), "tags:acme/demo"))
          .thenReturn(Optional.of(new SwiftRegistryDao.NegativeCache(
              proxy.id(), "tags:acme/demo", status,
              status == 429 ? Instant.now().plusSeconds(60) : null,
              Instant.now().plusSeconds(60), Instant.now())));

      Class<? extends RuntimeException> expected = status == 429
          ? SwiftExceptions.UpstreamRateLimited.class
          : SwiftExceptions.BadUpstream.class;
      assertThrows(expected, () -> fixture.service.get(
          group, "Acme/Demo", null,
          "https://repo.example/repository/swift-group/", SwiftMediaTypes.VENDOR_JSON, false));
      verify(fixture.registry, never()).listReleases(hosted.id(), "acme", "demo");
    }
  }

  @Test
  void groupReleaseListReloadsCurrentMembershipBeforeEveryAggregationPass()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime staleMember = hosted(1L, "swift-stale");
    RepositoryRuntime currentMember = hosted(3L, "swift-current");
    RepositoryRuntime staleGroup = group(2L, List.of(staleMember));
    RepositoryRuntime currentGroup = group(2L, List.of(currentMember));
    when(fixture.runtimes.resolveById(staleGroup.id())).thenReturn(Optional.of(currentGroup));
    when(fixture.registry.listReleases(currentMember.id(), "acme", "demo")).thenReturn(List.of(
        release(33L, currentMember.id(), "2.0.0", SwiftRegistryDao.RELEASE_READY)));

    MavenResponse response = fixture.service.get(
        staleGroup,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift-group/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    String body = responseBody(response);
    assertTrue(body.contains("2.0.0"));
    assertFalse(body.contains("1.0.0"));
    verify(fixture.registry, never()).listReleases(staleMember.id(), "acme", "demo");
    verify(fixture.registry, never()).listTombstones(staleMember.id(), "acme", "demo");
  }

  @Test
  void groupIdentifiersReloadMembershipAndSkipMembersWithoutReadPermission()
      throws Exception {
    Fixture fixture = fixture();
    PermissionSubject subject = new PermissionSubject("Local", "alice", Set.of(), null);
    RepositoryRuntime staleMember = proxy(1L, "swift-stale");
    RepositoryRuntime allowedMember = hosted(3L, "swift-allowed");
    RepositoryRuntime deniedMember = proxy(4L, "swift-private");
    RepositoryRuntime staleGroup = group(2L, List.of(staleMember));
    RepositoryRuntime currentGroup = group(2L, List.of(allowedMember, deniedMember));
    when(fixture.runtimes.resolveById(staleGroup.id())).thenReturn(Optional.of(currentGroup));
    when(fixture.accessDecisions.decide(eq(subject), any(RepositoryPermission.class)))
        .thenAnswer(invocation -> {
          RepositoryPermission permission = invocation.getArgument(1);
          return "swift-allowed".equals(permission.repository())
              ? AccessDecision.allow()
              : AccessDecision.deny("private member");
        });
    when(fixture.registry.findIdentities(
        allowedMember.id(), "https://github.com/acme/demo"))
        .thenReturn(List.of(new SwiftRegistryDao.PackageIdentity(
            "visible", "Visible", "package", "Package")));

    MavenResponse response = fixture.service.get(
        staleGroup,
        "identifiers",
        "url=https%3A%2F%2Fgithub.com%2Facme%2Fdemo.git",
        "https://repo.example/repository/swift-group/",
        SwiftMediaTypes.VENDOR_JSON,
        false,
        subject);

    String body = responseBody(response);
    assertTrue(body.contains("Visible.Package"));
    assertFalse(body.contains("acme.demo"));
    verify(fixture.registry, never()).findIdentities(eq(staleMember.id()), anyString());
    verify(fixture.registry, never()).findIdentities(eq(deniedMember.id()), anyString());
    verify(fixture.accessDecisions).decide(
        eq(subject),
        argThat(permission -> "swift-allowed".equals(permission.repository())
            && "identifiers".equals(permission.pathPattern())));
    verify(fixture.accessDecisions).decide(
        eq(subject),
        argThat(permission -> "swift-private".equals(permission.repository())
            && "identifiers".equals(permission.pathPattern())));
  }

  @Test
  void releaseMetadataAndArchiveExposeChecksumAndStoredSourceSignature() throws Exception {
    Fixture fixture = fixture();
    byte[] signature = new byte[] {7, 8, 9};
    SwiftRegistryDao.Release release = signedRelease(1L, "1.2.3", 12L);
    when(fixture.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(release));
    when(fixture.registry.listReleases(1L, "acme", "demo")).thenReturn(List.of(release));
    when(fixture.assets.bytes(1L, 12L)).thenReturn(signature);

    MavenResponse metadata = fixture.service.get(
        fixture.runtime,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        false);
    String metadataJson;
    try (InputStream input = metadata.body()) {
      metadataJson = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(metadataJson.contains("\"checksum\":\"" + "a".repeat(64) + "\""));
    assertTrue(metadataJson.contains("\"signatureFormat\":\"cms-1.0.0\""));
    assertTrue(metadataJson.contains(
        "\"signatureBase64Encoded\":\""
            + Base64.getEncoder().encodeToString(signature) + "\""));
    assertTrue(
        metadataJson.contains("\"publishedAt\":\"2026-07-16T00:00:00Z\""),
        metadataJson);
    assertFalse(metadataJson.contains("2026-07-16T00:00:00.123Z"));
    verify(fixture.registry, never()).listRepositoryUrls(1L);

    byte[] archiveBytes = new byte[] {1, 2, 3};
    when(fixture.assets.serve(1L, 10L, false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream(archiveBytes),
        archiveBytes.length,
        SwiftMediaTypes.ARCHIVE,
        "archive-etag",
        Instant.EPOCH));
    MavenResponse archive = fixture.service.get(
        fixture.runtime,
        "Acme/Demo/1.2.3.zip",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_ZIP,
        false);

    assertEquals("bytes", archive.headers().get("Accept-Ranges"));
    assertEquals("1", archive.headers().get("Content-Version"));
    assertTrue(archive.headers().get("Digest").startsWith("sha-256="));
    assertEquals("attachment; filename=\"Demo-1.2.3.zip\"",
        archive.headers().get("Content-Disposition"));
    assertEquals("cms-1.0.0", archive.headers().get("X-Swift-Package-Signature-Format"));
    assertEquals(Base64.getEncoder().encodeToString(signature),
        archive.headers().get("X-Swift-Package-Signature"));
  }

  @Test
  void groupReloadsFreshMembershipWhenDatabaseRevisionFencesStaleBinding() {
    Fixture fixture = fixture();
    RepositoryRuntime staleMember = hosted(1L, "swift-old");
    RepositoryRuntime currentMember = hosted(3L, "swift-current");
    RepositoryRuntime staleGroup = group(2L, List.of(staleMember));
    RepositoryRuntime currentGroup = group(2L, List.of(currentMember));
    SwiftRegistryDao.Release staleRelease = release(
        11L, staleMember.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY);
    SwiftRegistryDao.Release currentRelease = release(
        33L, currentMember.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY);

    when(fixture.registry.currentRepositoryRevision(2L)).thenReturn(7L, 8L);
    when(fixture.registry.listReleases(1L, "acme", "demo"))
        .thenReturn(List.of(staleRelease));
    when(fixture.registry.listReleases(3L, "acme", "demo"))
        .thenReturn(List.of(currentRelease));
    when(fixture.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(staleRelease));
    when(fixture.registry.findRelease(3L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(currentRelease));
    when(fixture.registry.upsertGroupSourceBindingIfCurrent(any()))
        .thenReturn(false, true);
    SwiftRegistryDao.GroupSourceBinding currentBinding =
        new SwiftRegistryDao.GroupSourceBinding(
            staleGroup.id(), "acme", "demo", "1.2.3", currentMember.id(),
            currentRelease.id(), currentRelease.revision(), 8L, Instant.EPOCH);
    when(fixture.registry.findGroupSourceBinding(
        staleGroup.id(), "acme", "demo", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(currentBinding));
    when(fixture.registry.findReleaseById(currentRelease.id()))
        .thenReturn(Optional.of(currentRelease));
    when(fixture.runtimes.resolveById(2L))
        .thenReturn(Optional.of(staleGroup), Optional.of(currentGroup));
    when(fixture.runtimes.resolveById(currentMember.id()))
        .thenReturn(Optional.of(currentMember));

    MavenResponse response = fixture.service.get(
        staleGroup,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    ArgumentCaptor<SwiftRegistryDao.GroupSourceBinding> bindings =
        ArgumentCaptor.forClass(SwiftRegistryDao.GroupSourceBinding.class);
    verify(fixture.registry, times(2)).upsertGroupSourceBindingIfCurrent(bindings.capture());
    assertEquals(1L, bindings.getAllValues().get(0).memberRepositoryId());
    assertEquals(7L, bindings.getAllValues().get(0).groupConfigRevision());
    assertEquals(3L, bindings.getAllValues().get(1).memberRepositoryId());
    assertEquals(8L, bindings.getAllValues().get(1).groupConfigRevision());
  }

  @Test
  void groupInitializesMissingSharedRevisionBeforeRecordingBinding() {
    Fixture fixture = fixture();
    RepositoryRuntime member = hosted(1L, "swift-member");
    RepositoryRuntime group = group(2L, List.of(member));
    SwiftRegistryDao.Release release = release(
        11L, member.id(), "1.2.3", SwiftRegistryDao.RELEASE_READY);
    when(fixture.registry.currentRepositoryRevision(2L)).thenReturn(0L);
    when(fixture.registry.nextRepositoryRevision(2L)).thenReturn(1L);
    when(fixture.registry.listReleases(1L, "acme", "demo")).thenReturn(List.of(release));
    when(fixture.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(release));
    when(fixture.registry.upsertGroupSourceBindingIfCurrent(any())).thenReturn(true);
    SwiftRegistryDao.GroupSourceBinding storedBinding =
        new SwiftRegistryDao.GroupSourceBinding(
            group.id(), "acme", "demo", "1.2.3", member.id(), release.id(),
            release.revision(), 1L, Instant.EPOCH);
    when(fixture.registry.findGroupSourceBinding(group.id(), "acme", "demo", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.of(storedBinding));
    when(fixture.registry.findReleaseById(release.id())).thenReturn(Optional.of(release));
    when(fixture.runtimes.resolveById(2L)).thenReturn(Optional.of(group));
    when(fixture.runtimes.resolveById(member.id())).thenReturn(Optional.of(member));

    MavenResponse response = fixture.service.get(
        group,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    ArgumentCaptor<SwiftRegistryDao.GroupSourceBinding> binding =
        ArgumentCaptor.forClass(SwiftRegistryDao.GroupSourceBinding.class);
    verify(fixture.registry).upsertGroupSourceBindingIfCurrent(binding.capture());
    assertEquals(1L, binding.getValue().groupConfigRevision());
  }

  @Test
  void groupServesCanonicalStoredWinnerWhenConcurrentCandidateLoses() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime localMember = hosted(1L, "swift-local-candidate");
    RepositoryRuntime canonicalMember = hosted(3L, "swift-canonical-winner");
    RepositoryRuntime group = group(2L, List.of(localMember, canonicalMember));
    SwiftRegistryDao.Release localRelease = releaseWithChecksum(
        11L, localMember.id(), "1.2.3", "a".repeat(64));
    SwiftRegistryDao.Release canonicalRelease = releaseWithChecksum(
        33L, canonicalMember.id(), "1.2.3", "b".repeat(64));
    SwiftRegistryDao.GroupSourceBinding canonicalBinding =
        new SwiftRegistryDao.GroupSourceBinding(
            group.id(), "acme", "demo", "1.2.3", canonicalMember.id(),
            canonicalRelease.id(), canonicalRelease.revision(), 7L, Instant.EPOCH);
    when(fixture.registry.currentRepositoryRevision(group.id())).thenReturn(7L);
    when(fixture.registry.findGroupSourceBinding(group.id(), "acme", "demo", "1.2.3"))
        .thenReturn(Optional.empty(), Optional.of(canonicalBinding));
    when(fixture.registry.listReleases(localMember.id(), "acme", "demo"))
        .thenReturn(List.of(localRelease));
    when(fixture.registry.listReleases(canonicalMember.id(), "acme", "demo"))
        .thenReturn(List.of(canonicalRelease));
    when(fixture.registry.findRelease(
        localMember.id(), "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(localRelease));
    when(fixture.registry.findReleaseById(canonicalRelease.id()))
        .thenReturn(Optional.of(canonicalRelease));
    when(fixture.registry.upsertGroupSourceBindingIfCurrent(any())).thenReturn(true);
    when(fixture.runtimes.resolveById(group.id())).thenReturn(Optional.of(group));
    when(fixture.runtimes.resolveById(canonicalMember.id()))
        .thenReturn(Optional.of(canonicalMember));

    MavenResponse response = fixture.service.get(
        group,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift-group/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    String body = responseBody(response);
    assertTrue(body.contains("\"checksum\":\"" + "b".repeat(64) + "\""));
    assertFalse(body.contains("\"checksum\":\"" + "a".repeat(64) + "\""));
    verify(fixture.registry).upsertGroupSourceBindingIfCurrent(any());
  }

  @Test
  void groupReleaseMetadataLinksUseFreshMembershipSnapshot() {
    Fixture fixture = fixture();
    RepositoryRuntime staleMember = hosted(1L, "swift-stale-member");
    RepositoryRuntime currentMember = hosted(3L, "swift-current-member");
    RepositoryRuntime staleGroup = group(2L, List.of(staleMember));
    RepositoryRuntime currentGroup = group(2L, List.of(currentMember));
    SwiftRegistryDao.Release requested = release(
        32L, currentMember.id(), "2.0.0", SwiftRegistryDao.RELEASE_READY);
    SwiftRegistryDao.GroupSourceBinding binding = new SwiftRegistryDao.GroupSourceBinding(
        staleGroup.id(), "acme", "demo", "2.0.0", currentMember.id(), requested.id(),
        requested.revision(), 7L, Instant.EPOCH);
    when(fixture.registry.currentRepositoryRevision(staleGroup.id())).thenReturn(7L);
    when(fixture.registry.findGroupSourceBinding(
        staleGroup.id(), "acme", "demo", "2.0.0")).thenReturn(Optional.of(binding));
    when(fixture.registry.findReleaseById(requested.id())).thenReturn(Optional.of(requested));
    when(fixture.registry.listReleases(currentMember.id(), "acme", "demo")).thenReturn(List.of(
        release(31L, currentMember.id(), "1.0.0", SwiftRegistryDao.RELEASE_READY),
        requested,
        release(33L, currentMember.id(), "3.0.0", SwiftRegistryDao.RELEASE_READY)));
    when(fixture.runtimes.resolveById(currentMember.id()))
        .thenReturn(Optional.of(currentMember));
    when(fixture.runtimes.resolveById(staleGroup.id()))
        .thenReturn(Optional.of(currentGroup));

    MavenResponse response = fixture.service.get(
        staleGroup,
        "Acme/Demo/2.0.0",
        null,
        "https://repo.example/repository/swift-group/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    String links = response.headers().get("Link");
    assertTrue(links.contains("/Acme/Demo/3.0.0"));
    assertTrue(links.contains("latest-version"));
    assertTrue(links.contains("successor-version"));
    assertTrue(links.contains("/Acme/Demo/1.0.0"));
    assertTrue(links.contains("predecessor-version"));
    assertFalse(links.contains("9.9.9"));
    verify(fixture.registry, never()).listReleases(
        staleMember.id(), "acme", "demo");
  }

  @Test
  void proxyTombstoneHidesUpstreamTagAndPreventsRematerialization() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(4L, "swift-proxy");
    SwiftRegistryDao.Tombstone tombstone = new SwiftRegistryDao.Tombstone(
        proxy.id(), "acme", "demo", "1.2.3", "deleted", 9L, Instant.EPOCH);
    when(fixture.registry.findTombstone(proxy.id(), "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(tombstone));
    when(fixture.registry.listTombstones(proxy.id(), "acme", "demo"))
        .thenReturn(List.of(tombstone));
    when(fixture.github.tags(any(), any())).thenReturn(List.of(
        new SwiftGitHubClient.Tag("1.2.3", "v1.2.3", "a".repeat(40))));

    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.get(
        proxy,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false));
    MavenResponse tombstonedList = fixture.service.get(
        proxy,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, tombstonedList.status());
    assertTrue(responseBody(tombstonedList).contains("\"status\":410"));
    verify(fixture.github, never()).archive(any(), any(), anyString());
    verify(fixture.registry, never()).bindProxySources(any());
  }

  @Test
  void proxyReleaseListUsesSharedPositiveMetadataTtlAcrossReplicas() {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(4L, "swift-proxy");
    when(fixture.registry.listProxySources(proxy.id(), "acme", "demo")).thenReturn(List.of(
        new SwiftRegistryDao.ProxySource(
            proxy.id(), "acme", "demo", "1.2.3", "https://github.com/acme/demo",
            "v1.2.3", "a".repeat(40), "github-zipball-v1", null, "DISCOVERED",
            null, null, 3L, 1L, Instant.now())));

    MavenResponse response = fixture.service.get(
        proxy,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    assertTrue(response.headers().get("Link").contains(
        "<https://github.com/acme/demo>; rel=\"canonical\""));
    verify(fixture.github, never()).tags(any(), any());
  }

  @Test
  void proxyMovedTagKeepsPinnedCommitAndWritesPersistentConflictAudit() {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(4L, "swift-proxy");
    String pinnedCommit = "a".repeat(40);
    String observedCommit = "b".repeat(40);
    SwiftRegistryDao.ProxySource pinned = new SwiftRegistryDao.ProxySource(
        proxy.id(), "acme", "demo", "1.2.3", "https://github.com/acme/demo",
        "v1.2.3", pinnedCommit, "github-zipball-v1", null, "DISCOVERED",
        null, null, 3L, 1L, Instant.EPOCH);
    when(fixture.registry.listProxySources(proxy.id(), "acme", "demo"))
        .thenReturn(List.of(), List.of(pinned));
    when(fixture.github.tags(any(), any())).thenReturn(List.of(
        new SwiftGitHubClient.Tag("1.2.3", "v1.2.3", observedCommit)));
    when(fixture.registry.bindProxySources(any())).thenReturn(List.of(pinned));

    MavenResponse response = fixture.service.get(
        proxy,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    ArgumentCaptor<SecurityAuditDao.AuditLogRecord> audit =
        ArgumentCaptor.forClass(SecurityAuditDao.AuditLogRecord.class);
    verify(fixture.audit).insert(audit.capture());
    assertEquals("CONFLICT", audit.getValue().outcome());
    assertEquals(409, audit.getValue().status());
    assertEquals("swift_proxy_tag_moved", audit.getValue().details().get("event"));
    assertEquals(pinnedCommit, audit.getValue().details().get("pinnedCommit"));
    assertEquals(observedCommit, audit.getValue().details().get("observedCommit"));
  }

  @Test
  void proxyTagRefreshBindsLargeInventoriesInOneBatchAndOneRevision() throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(4L, "swift-proxy");
    ArrayList<SwiftGitHubClient.Tag> tags = new ArrayList<>();
    ArrayList<SwiftRegistryDao.ProxySource> bound = new ArrayList<>();
    for (int index = 0; index < 1_200; index++) {
      String version = "1.0." + index;
      String commit = "%040x".formatted(index + 1);
      tags.add(new SwiftGitHubClient.Tag(version, "v" + version, commit));
      bound.add(new SwiftRegistryDao.ProxySource(
          proxy.id(), "acme", "demo", version, "https://github.com/acme/demo",
          "v" + version, commit, "github-zipball-v1", null, "DISCOVERED", null,
          null, 7L, 1L, Instant.now()));
    }
    when(fixture.github.tags(any(), any())).thenReturn(tags);
    when(fixture.registry.nextRepositoryRevision(proxy.id())).thenReturn(7L);
    when(fixture.registry.bindProxySources(any())).thenReturn(bound);

    MavenResponse response = fixture.service.get(
        proxy,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    verify(fixture.registry).bindProxySources(argThat(candidates -> candidates.size() == 1_200));
    verify(fixture.registry).nextRepositoryRevision(proxy.id());
    verify(fixture.registry, never()).bindProxySource(any());
    verify(fixture.registry, never()).findProxySource(
        eq(proxy.id()), eq("acme"), eq("demo"), anyString());
    verify(fixture.registry, never()).findTombstone(
        eq(proxy.id()), eq("acme"), eq("demo"), anyString());
  }

  @Test
  void directProxyMaterializationPersistsTheCompleteTagInventoryBeforeSelectingVersion()
      throws Exception {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(4L, "swift-proxy");
    SwiftRegistryDao.Release materialized = release(
        77L, proxy.id(), "2.0.0", SwiftRegistryDao.RELEASE_READY);
    SwiftRegistryDao.ProxySource first = new SwiftRegistryDao.ProxySource(
        proxy.id(), "acme", "demo", "1.0.0", "https://github.com/acme/demo",
        "v1.0.0", "a".repeat(40), "github-zipball-v1", null, "DISCOVERED", null,
        null, 8L, 1L, Instant.now());
    SwiftRegistryDao.ProxySource requested = new SwiftRegistryDao.ProxySource(
        proxy.id(), "acme", "demo", "2.0.0", "https://github.com/acme/demo",
        "v2.0.0", "b".repeat(40), "github-zipball-v1", null, "READY", 77L,
        Instant.now(), 8L, 1L, Instant.now());
    when(fixture.github.tags(any(), any())).thenReturn(List.of(
        new SwiftGitHubClient.Tag("1.0.0", "v1.0.0", "a".repeat(40)),
        new SwiftGitHubClient.Tag("2.0.0", "v2.0.0", "b".repeat(40))));
    when(fixture.registry.nextRepositoryRevision(proxy.id())).thenReturn(8L);
    when(fixture.registry.bindProxySources(any())).thenReturn(List.of(first, requested));
    when(fixture.registry.listProxySources(proxy.id(), "acme", "demo"))
        .thenReturn(List.of(), List.of(first, requested));
    when(fixture.registry.findReleaseById(77L)).thenReturn(Optional.of(materialized));

    MavenResponse response = fixture.service.get(
        proxy,
        "Acme/Demo/2.0.0",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false);

    assertEquals(200, response.status());
    verify(fixture.registry).bindProxySources(argThat(candidates -> candidates.size() == 2));
    verify(fixture.github, never()).archive(any(), any(), anyString());
  }

  @Test
  void proxyArchiveNotFoundUsesSharedNegativeCacheBeforeRetryingGitHub() {
    Fixture fixture = fixture();
    RepositoryRuntime proxy = proxy(4L, "swift-proxy");
    SwiftRegistryDao.ProxySource source = new SwiftRegistryDao.ProxySource(
        proxy.id(), "acme", "demo", "1.2.3", "https://github.com/acme/demo",
        "v1.2.3", "a".repeat(40), "github-zipball-v1", null, "DISCOVERED",
        null, null, 3L, 1L, Instant.EPOCH);
    when(fixture.registry.findProxySource(proxy.id(), "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(source));
    SwiftPublishLeaseManager.Lease lease = mock(SwiftPublishLeaseManager.Lease.class);
    when(fixture.leases.acquireForCoalescedRead(anyString())).thenReturn(lease);
    when(fixture.github.archive(eq(proxy), any(), eq("a".repeat(40))))
        .thenThrow(new SwiftExceptions.NotFound("gone"));

    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.get(
        proxy,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false));

    ArgumentCaptor<SwiftRegistryDao.NegativeCache> cached =
        ArgumentCaptor.forClass(SwiftRegistryDao.NegativeCache.class);
    verify(fixture.registry).putNegativeCache(cached.capture());
    assertEquals("archive:acme/demo/1.2.3", cached.getValue().cacheKey());
    assertEquals(404, cached.getValue().statusCode());
    when(fixture.registry.findNegativeCache(
        proxy.id(), "archive:acme/demo/1.2.3"))
        .thenReturn(Optional.of(cached.getValue()));

    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.get(
        proxy,
        "Acme/Demo/1.2.3",
        null,
        "https://repo.example/repository/swift-proxy/",
        SwiftMediaTypes.VENDOR_JSON,
        false));
    verify(fixture.github, times(1)).archive(eq(proxy), any(), eq("a".repeat(40)));
  }

  @Test
  void manifestReturnsExactVariantLinksAndRedirectsUnknownToolsVersion() {
    Fixture fixture = fixture();
    SwiftRegistryDao.Release release = release(1L, "1.2.3", SwiftRegistryDao.RELEASE_READY);
    SwiftRegistryDao.Manifest defaultManifest = new SwiftRegistryDao.Manifest(
        1L, "Package.swift", "", 20L, "b".repeat(64));
    SwiftRegistryDao.Manifest versionedManifest = new SwiftRegistryDao.Manifest(
        1L, "Package@swift-5.9.swift", "5.9", 21L, "c".repeat(64));
    when(fixture.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(release));
    when(fixture.registry.findManifest(1L, "")).thenReturn(Optional.of(defaultManifest));
    when(fixture.registry.findManifest(1L, "5.8")).thenReturn(Optional.empty());
    when(fixture.registry.listManifests(1L)).thenReturn(List.of(defaultManifest, versionedManifest));
    when(fixture.assets.serve(1L, 20L, false)).thenReturn(MavenResponse.ok(
        new ByteArrayInputStream("// swift-tools-version:5.7\n".getBytes(StandardCharsets.UTF_8)),
        27L,
        SwiftMediaTypes.MANIFEST,
        "manifest-etag",
        Instant.EPOCH));
    when(fixture.assets.bytes(1L, 21L)).thenReturn(
        "// swift-tools-version:5.9.1\n".getBytes(StandardCharsets.UTF_8));

    MavenResponse exact = fixture.service.get(
        fixture.runtime,
        "Acme/Demo/1.2.3/Package.swift",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_SWIFT,
        false);

    assertEquals(200, exact.status());
    assertEquals("1", exact.headers().get("Content-Version"));
    assertEquals("attachment; filename=\"Package.swift\"",
        exact.headers().get("Content-Disposition"));
    assertTrue(exact.headers().get("Link").contains("swift-version=5.9"));
    assertTrue(exact.headers().get("Link").contains("filename=\"Package@swift-5.9.swift\""));
    assertTrue(exact.headers().get("Link").contains("swift-tools-version=\"5.9.1\""));

    MavenResponse fallback = fixture.service.get(
        fixture.runtime,
        "Acme/Demo/1.2.3/Package.swift",
        "swift-version=5.8",
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_SWIFT,
        false);
    assertEquals(303, fallback.status());
    assertEquals("1", fallback.headers().get("Content-Version"));
    assertEquals("https://repo.example/repository/swift/Acme/Demo/1.2.3/Package.swift",
        fallback.headers().get("Location"));
  }

  @Test
  void rejectsInvalidAndUnsupportedMediaNegotiationBeforeStorageLookup() {
    Fixture fixture = fixture();

    assertThrows(SwiftExceptions.BadRequest.class, () -> fixture.service.get(
        fixture.runtime,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift/",
        "application/vnd.swift.registry.vnext+json",
        false));
    assertThrows(SwiftExceptions.UnsupportedMediaType.class, () -> fixture.service.get(
        fixture.runtime,
        "Acme/Demo",
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_ZIP,
        false));
    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.get(
        fixture.runtime,
        "",
        null,
        "https://repo.example/repository/swift/",
        null,
        false));
  }

  @Test
  void hostedPublishStoresArchiveManifestAndMetadataAsOneImmutableRelease() throws Exception {
    Fixture fixture = fixture();
    Path source = Files.createTempFile("swift-service-test-", ".zip");
    Files.write(source, new byte[] {1, 2, 3});
    byte[] manifestBytes = ("// swift-tools-version: 5.9\n"
        + "import PackageDescription\n").getBytes(StandardCharsets.UTF_8);
    SwiftArchiveInspector.InspectedArchive inspected = new SwiftArchiveInspector.InspectedArchive(
        source,
        3L,
        "a".repeat(64),
        "YWFh",
        List.of(new SwiftArchiveInspector.ManifestEntry(
            "Package.swift", "", "5.9", manifestBytes, "b".repeat(64))));
    when(fixture.inspector.inspect(any(InputStream.class))).thenReturn(inspected);
    SwiftPublishLeaseManager.Lease lease = mock(SwiftPublishLeaseManager.Lease.class);
    when(fixture.leases.acquire(anyString(), any())).thenReturn(lease);
    when(fixture.assets.stageFile(
        eq(fixture.runtime), eq("acme/demo/1.2.3.zip"), eq(source),
        eq(SwiftMediaTypes.ARCHIVE), any(), eq("alice"), eq("192.0.2.10")))
        .thenReturn(asset(10L, ".swift/staging/archive/source.zip"));
    when(fixture.assets.stageBytes(
        eq(fixture.runtime), eq("acme/demo/1.2.3/Package.swift"), eq(manifestBytes),
        eq(SwiftMediaTypes.MANIFEST), any(), eq("alice"), eq("192.0.2.10")))
        .thenReturn(asset(11L, ".swift/staging/manifest/Package.swift"));
    SwiftRegistryDao.Release stored = release(88L, "1.2.3", SwiftRegistryDao.RELEASE_READY);
    when(fixture.components.publishFenced(eq(fixture.runtime), any(), eq(lease)))
        .thenReturn(stored);

    MockPart archive = part(
        "source-archive", "source.zip", "application/zip", new byte[] {9});
    MockPart metadata = part(
        "metadata",
        null,
        "application/json",
        "{\"repositoryURLs\":[\"https://github.com/Acme/Demo.git\"]}"
            .getBytes(StandardCharsets.UTF_8));
    MavenResponse response = fixture.service.publish(
        fixture.runtime,
        "Acme/Demo/1.2.3",
        null,
        List.<Part>of(archive, metadata),
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        "alice",
        "192.0.2.10");

    assertEquals(201, response.status());
    assertEquals("1", response.headers().get("Content-Version"));
    assertEquals("https://repo.example/repository/swift/Acme/Demo/1.2.3",
        response.headers().get("Location"));
    assertFalse(Files.exists(source), "the buffered upload must be deleted after persistence");
    verify(lease).assertHeld();
    verify(lease).close();
    verify(fixture.assets).stageFile(
        eq(fixture.runtime),
        eq("acme/demo/1.2.3.zip"),
        eq(source),
        eq(SwiftMediaTypes.ARCHIVE),
        any(),
        eq("alice"),
        eq("192.0.2.10"));
    verify(fixture.assets).stageBytes(
        eq(fixture.runtime),
        eq("acme/demo/1.2.3/Package.swift"),
        eq(manifestBytes),
        eq(SwiftMediaTypes.MANIFEST),
        any(),
        eq("alice"),
        eq("192.0.2.10"));

    ArgumentCaptor<SwiftComponentService.Publication> publication =
        ArgumentCaptor.forClass(SwiftComponentService.Publication.class);
    verify(fixture.components).publishFenced(
        eq(fixture.runtime), publication.capture(), eq(lease));
    assertEquals("acme", publication.getValue().scopeLc());
    assertEquals("Acme", publication.getValue().scopeDisplay());
    assertEquals("demo", publication.getValue().nameLc());
    assertEquals("Demo", publication.getValue().nameDisplay());
    assertEquals("https://github.com/acme/demo",
        publication.getValue().repositoryUrls().getFirst().normalizedUrl());
    assertEquals("", publication.getValue().manifests().getFirst().toolsVersion());
  }

  @Test
  void publishRejectsOfflineAndDenyBeforeReadingTheMultipartBody() {
    Fixture fixture = fixture();
    MockPart archive = part(
        "source-archive", "source.zip", "application/zip", new byte[] {1});

    assertThrows(SwiftExceptions.NotFound.class, () -> fixture.service.publish(
        hosted(false, "ALLOW_ONCE"),
        "Acme/Demo/1.2.3",
        null,
        List.of(archive),
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        "alice",
        "192.0.2.10"));
    assertThrows(SwiftExceptions.Forbidden.class, () -> fixture.service.publish(
        hosted(true, "DENY"),
        "Acme/Demo/1.2.3",
        null,
        List.of(archive),
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        "alice",
        "192.0.2.10"));
    assertThrows(SwiftExceptions.Forbidden.class, () -> fixture.service.publishUpload(
        hosted(true, "DENY"),
        "Acme",
        "Demo",
        "1.2.3",
        new ByteArrayInputStream(new byte[] {1}),
        "{}",
        null,
        null,
        null,
        null,
        "alice",
        "192.0.2.10"));

    verify(fixture.inspector, never()).inspect(any(InputStream.class));
    verify(fixture.leases, never()).acquire(anyString());
  }

  @Test
  void publishPreflightRejectsExistingOrTombstonedCoordinateBeforeMultipartParsing() {
    Fixture existing = fixture();
    when(existing.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(release(
            1L, "1.2.3", SwiftRegistryDao.RELEASE_READY)));

    assertThrows(SwiftExceptions.Conflict.class, () ->
        existing.service.validatePublishRequest(
            existing.runtime, "Acme/Demo/1.2.3", null, SwiftMediaTypes.VENDOR_JSON));

    Fixture tombstoned = fixture();
    when(tombstoned.registry.findTombstone(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(new SwiftRegistryDao.Tombstone(
            1L, "acme", "demo", "1.2.3", "deleted", 9L, Instant.EPOCH)));
    assertThrows(SwiftExceptions.Conflict.class, () ->
        tombstoned.service.validatePublishRequest(
            tombstoned.runtime, "Acme/Demo/1.2.3", null, SwiftMediaTypes.VENDOR_JSON));

    Fixture readOnly = fixture();
    assertThrows(SwiftExceptions.MethodNotAllowed.class, () ->
        readOnly.service.validatePublishRequest(
            group(2L, List.of(readOnly.runtime)), "Acme/Demo/1.2.3", null, null));
    assertThrows(SwiftExceptions.MethodNotAllowed.class, () ->
        readOnly.service.validatePublishRequest(
            proxy(3L, "swift-proxy"), "Acme/Demo/1.2.3", null, null));
    verify(readOnly.registry, never()).findTombstone(
        anyLong(), anyString(), anyString(), anyString());
    verify(readOnly.registry, never()).findRelease(
        anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void concurrentPublishFailureAlwaysRemovesThisRequestsUniqueStagingAssets() throws Exception {
    Fixture fixture = fixture();
    Path source = Files.createTempFile("swift-service-conflict-", ".zip");
    Files.write(source, new byte[] {1, 2, 3});
    byte[] manifestBytes = "// swift-tools-version: 5.9\n"
        .getBytes(StandardCharsets.UTF_8);
    SwiftArchiveInspector.InspectedArchive inspected = new SwiftArchiveInspector.InspectedArchive(
        source,
        3L,
        "a".repeat(64),
        "YWFh",
        List.of(new SwiftArchiveInspector.ManifestEntry(
            "Package.swift", "", "5.9", manifestBytes, "b".repeat(64))));
    when(fixture.inspector.inspect(any(InputStream.class))).thenReturn(inspected);
    SwiftPublishLeaseManager.Lease lease = mock(SwiftPublishLeaseManager.Lease.class);
    when(fixture.leases.acquire(anyString(), any())).thenReturn(lease);
    String stagedArchive = ".swift/staging/request-a/source.zip";
    String stagedManifest = ".swift/staging/request-a/Package.swift";
    when(fixture.assets.stageFile(
        eq(fixture.runtime), eq("acme/demo/1.2.3.zip"), eq(source),
        eq(SwiftMediaTypes.ARCHIVE), any(), eq("alice"), eq("192.0.2.10")))
        .thenReturn(asset(10L, stagedArchive));
    when(fixture.assets.stageBytes(
        eq(fixture.runtime), eq("acme/demo/1.2.3/Package.swift"), eq(manifestBytes),
        eq(SwiftMediaTypes.MANIFEST), any(), eq("alice"), eq("192.0.2.10")))
        .thenReturn(asset(11L, stagedManifest));
    when(fixture.components.publishFenced(eq(fixture.runtime), any(), eq(lease)))
        .thenThrow(new SwiftExceptions.Conflict("concurrent winner"));

    MockPart archive = part(
        "source-archive", "source.zip", "application/zip", new byte[] {9});
    assertThrows(SwiftExceptions.Conflict.class, () -> fixture.service.publish(
        fixture.runtime,
        "Acme/Demo/1.2.3",
        null,
        List.of(archive),
        null,
        "https://repo.example/repository/swift/",
        SwiftMediaTypes.VENDOR_JSON,
        "alice",
        "192.0.2.10"));

    verify(fixture.assets).delete(fixture.runtime, stagedManifest);
    verify(fixture.assets).delete(fixture.runtime, stagedArchive);
    assertFalse(Files.exists(source), "the losing request must delete its buffered upload");
    verify(lease).close();
  }

  @Test
  void publishRejectsDuplicateCoordinatesAndInvalidMetadataBeforeInspectingArchive() {
    Fixture fixture = fixture();
    when(fixture.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(Optional.of(release(1L, "1.2.3", SwiftRegistryDao.RELEASE_READY)));
    MockPart archive = part(
        "source-archive", "source.zip", "application/zip", new byte[] {1});

    assertThrows(SwiftExceptions.Conflict.class, () -> fixture.service.publish(
        fixture.runtime,
        "Acme/Demo/1.2.3",
        null,
        List.of(archive),
        null,
        "https://repo.example/repository/swift/",
        null,
        "alice",
        null));

    Fixture invalid = fixture();
    MockPart badMetadata = part(
        "metadata", null, "application/json", "[]".getBytes(StandardCharsets.UTF_8));
    assertThrows(SwiftExceptions.UnprocessableEntity.class, () -> invalid.service.publish(
        invalid.runtime,
        "Acme/Demo/1.2.3",
        null,
        List.of(archive, badMetadata),
        null,
        "https://repo.example/repository/swift/",
        null,
        "alice",
        null));

    for (String invalidJson : List.of(
        "{\"description\":42}",
        "{\"author\":\"alice\"}",
        "{\"author\":{}}",
        "{\"author\":{\"name\":\"Alice\",\"email\":7}}",
        "{\"author\":{\"name\":\"Alice\",\"email\":\"not-an-email\"}}",
        "{\"author\":{\"name\":\"Alice\",\"organization\":{}}}",
        "{\"repositoryURLs\":[\"https://example.test/repo?token=secret\"]}",
        "{\"readmeURL\":\"https://example.test/readme?api_key=secret\"}")) {
      Fixture schema = fixture();
      MockPart schemaMetadata = part(
          "metadata", null, "application/json", invalidJson.getBytes(StandardCharsets.UTF_8));
      assertThrows(SwiftExceptions.UnprocessableEntity.class, () -> schema.service.publish(
          schema.runtime,
          "Acme/Demo/1.2.3",
          null,
          List.of(archive, schemaMetadata),
          null,
          "https://repo.example/repository/swift/",
          null,
          "alice",
          null), invalidJson);
    }
  }

  @Test
  void publishAcceptsAuthorAndOrganizationWithoutOptionalUrls() {
    Fixture fixture = fixture();
    when(fixture.registry.findRelease(1L, "acme", "demo", "1.2.3"))
        .thenReturn(
            Optional.empty(),
            Optional.of(release(1L, "1.2.3", SwiftRegistryDao.RELEASE_READY)));
    MockPart archive = part(
        "source-archive", "source.zip", "application/zip", new byte[] {1});
    MockPart metadata = part(
        "metadata",
        null,
        "application/json",
        "{\"author\":{\"name\":\"Alice\",\"organization\":{\"name\":\"Acme\"}}}"
            .getBytes(StandardCharsets.UTF_8));

    assertThrows(SwiftExceptions.Conflict.class, () -> fixture.service.publish(
        fixture.runtime,
        "Acme/Demo/1.2.3",
        null,
        List.of(archive, metadata),
        null,
        "https://repo.example/repository/swift/",
        null,
        "alice",
        null));
  }

  private static Fixture fixture() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    SwiftAssetSupport assets = mock(SwiftAssetSupport.class);
    SwiftArchiveInspector inspector = mock(SwiftArchiveInspector.class);
    SwiftGitHubClient github = mock(SwiftGitHubClient.class);
    SwiftPublishLeaseManager leases = mock(SwiftPublishLeaseManager.class);
    SwiftPublishLeaseManager.Lease defaultLease = mock(SwiftPublishLeaseManager.Lease.class);
    when(leases.acquire(anyString())).thenReturn(defaultLease);
    when(leases.acquireForCoalescedRead(anyString())).thenReturn(defaultLease);
    when(leases.acquire(anyString(), any())).thenReturn(defaultLease);
    SwiftComponentService components = mock(SwiftComponentService.class);
    SecurityAuditDao audit = mock(SecurityAuditDao.class);
    AccessDecisionService accessDecisions = mock(AccessDecisionService.class);
    SwiftService service = new SwiftService(
        new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
        registry,
        runtimes,
        assets,
        inspector,
        github,
        leases,
        components,
        audit,
        accessDecisions);
    return new Fixture(
        service,
        hosted(),
        registry,
        runtimes,
        assets,
        inspector,
        github,
        leases,
        components,
        audit,
        accessDecisions);
  }

  private static RepositoryRuntime hosted() {
    return hosted(1L, "swift");
  }

  private static RepositoryRuntime hosted(long id, String name) {
    return hosted(id, name, true, "ALLOW_ONCE");
  }

  private static RepositoryRuntime hosted(boolean online, String writePolicy) {
    return hosted(1L, "swift", online, writePolicy);
  }

  private static RepositoryRuntime hosted(
      long id, String name, boolean online, String writePolicy) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.SWIFT,
        RepositoryType.HOSTED,
        "swift-hosted",
        online,
        1L,
        writePolicy,
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        List.of());
  }

  private static RepositoryRuntime group(long id, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id,
        "swift-group",
        RepositoryFormat.SWIFT,
        RepositoryType.GROUP,
        "swift-group",
        true,
        1L,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        members);
  }

  private static RepositoryRuntime proxy(long id, String name) {
    return new RepositoryRuntime(
        id,
        name,
        RepositoryFormat.SWIFT,
        RepositoryType.PROXY,
        "swift-proxy",
        true,
        1L,
        null,
        null,
        null,
        true,
        "https://github.com/",
        1440,
        1440,
        List.of());
  }

  private static SwiftRegistryDao.Release release(Long id, String version, String status) {
    return release(id, 1L, version, status);
  }

  private static SwiftRegistryDao.Release release(
      Long id, long repositoryId, String version, String status) {
    return releaseWithChecksum(id, repositoryId, version, "a".repeat(64), status);
  }

  private static SwiftRegistryDao.Release releaseWithChecksum(
      Long id, long repositoryId, String version, String checksum) {
    return releaseWithChecksum(
        id, repositoryId, version, checksum, SwiftRegistryDao.RELEASE_READY);
  }

  private static SwiftRegistryDao.Release releaseWithChecksum(
      Long id, long repositoryId, String version, String checksum, String status) {
    Instant now = Instant.parse("2026-07-16T00:00:00Z");
    return new SwiftRegistryDao.Release(
        id,
        repositoryId,
        99L,
        "acme",
        "Acme",
        "demo",
        "Demo",
        version,
        now,
        "{}",
        checksum,
        10L,
        null,
        null,
        null,
        "HOSTED",
        id == null ? 1L : id,
        status,
        now,
        now);
  }

  private static SwiftRegistryDao.Release signedRelease(
      Long id, String version, Long signatureAssetId) {
    Instant now = Instant.parse("2026-07-16T00:00:00.123Z");
    return new SwiftRegistryDao.Release(
        id,
        1L,
        99L,
        "acme",
        "Acme",
        "demo",
        "Demo",
        version,
        now,
        "{}",
        "a".repeat(64),
        10L,
        "cms-1.0.0",
        signatureAssetId,
        null,
        "HOSTED",
        id == null ? 1L : id,
        SwiftRegistryDao.RELEASE_READY,
        now,
        now);
  }

  private static AssetRecord asset(long id, String path) {
    return new AssetRecord(
        id,
        1L,
        null,
        null,
        RepositoryFormat.SWIFT,
        path,
        new byte[32],
        path.substring(path.lastIndexOf('/') + 1),
        "swift",
        "application/octet-stream",
        1L,
        null,
        Instant.EPOCH,
        Map.of());
  }

  private static MockPart part(String name, String filename, String contentType, byte[] bytes) {
    MockPart part = new MockPart(name, filename, bytes);
    if (contentType != null) {
      part.getHeaders().set("Content-Type", contentType);
    }
    return part;
  }

  private static String responseBody(MavenResponse response) throws Exception {
    try (InputStream input = response.body()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private record Fixture(
      SwiftService service,
      RepositoryRuntime runtime,
      SwiftRegistryDao registry,
      RepositoryRuntimeRegistry runtimes,
      SwiftAssetSupport assets,
      SwiftArchiveInspector inspector,
      SwiftGitHubClient github,
      SwiftPublishLeaseManager leases,
      SwiftComponentService components,
      SecurityAuditDao audit,
      AccessDecisionService accessDecisions) {}
}
