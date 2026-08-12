package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.ConanRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.conan.ConanManifest;
import com.github.klboke.kkrepo.protocol.conan.ConanMediaTypes;
import com.github.klboke.kkrepo.protocol.conan.ConanReference;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConanServiceTest {
  private static final String SHA1 = "a".repeat(40);
  private static final String SHA256 = "b".repeat(64);
  private static final String MD5 = "c".repeat(32);
  private static final Instant PUBLISHED = Instant.parse("2026-08-01T01:02:03Z");
  private static final String ROOT = "v2/conans/demo/1.0/acme/stable";
  private static final String RREV = ROOT + "/revisions/rrev";
  private static final String PACKAGE = RREV + "/packages/pkg";
  private static final String PREV = PACKAGE + "/revisions/prev";
  private static final AuthenticatedSubject ALICE =
      new AuthenticatedSubject("LOCAL", "alice", "realm", null, null);

  @Test
  void implementsPingAuthenticationAndStrictRouteBoundaries() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    MavenResponse ping = fixture.service.get(hosted, "v1/ping", null, false, null);
    assertEquals(200, ping.status());
    assertTrue(ping.headers().get("X-Conan-Server-Capabilities").contains("revisions"));

    when(fixture.auth.issue(1L, ALICE)).thenReturn("issued-token");
    assertEquals("issued-token", text(fixture.service.get(
        hosted, "v2/users/authenticate", null, false, ALICE)));
    assertEquals("alice", text(fixture.service.get(
        hosted, "v2/users/check_credentials", null, false, ALICE)));

    assertThrows(ConanExceptions.Unauthorized.class, () -> fixture.service.get(
        hosted, "v2/users/check_credentials", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        hosted, "v1/ping", null, true, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        hosted, "unknown", null, false, null));
    assertThrows(ConanExceptions.BadRequest.class, () -> fixture.service.get(
        hosted, "v1/ping", "unexpected=true", false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        null, "v1/ping", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        runtime(1L, RepositoryFormat.MAVEN2, RepositoryType.HOSTED, true, List.of()),
        "v1/ping", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        runtime(1L, RepositoryFormat.CONAN, RepositoryType.HOSTED, false, List.of()),
        "v1/ping", null, false, null));
  }

  @Test
  void servesEveryHostedDiscoveryAndFileShapeFromTypedRegistryRows() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    JsonNode search = json(fixture.service.get(
        hosted, "v2/conans/search", "q=demo*&ignorecase=True", false, null));
    assertEquals("demo/1.0@acme/stable", search.path("results").get(0).asText());
    JsonNode revisions = json(fixture.service.get(
        hosted, ROOT + "/revisions", null, false, null));
    assertEquals("rrev", revisions.path("revisions").get(0).path("revision").asText());
    assertEquals("rrev", json(fixture.service.get(
        hosted, ROOT + "/latest", null, false, null)).path("revision").asText());
    assertTrue(json(fixture.service.get(
        hosted, RREV + "/files", null, false, null)).path("files").has("conanfile.py"));
    MavenResponse recipeFile = fixture.service.get(
        hosted, RREV + "/files/conanfile.py", null, false, null);
    assertEquals("stored", text(recipeFile));

    JsonNode packages = json(fixture.service.get(
        hosted, ROOT + "/search", "list_only=False", false, null));
    assertTrue(packages.path("pkg").path("content").asText().contains("os=Linux"));
    assertEquals("prev", json(fixture.service.get(
        hosted, PACKAGE + "/revisions", null, false, null))
        .path("revisions").get(0).path("revision").asText());
    assertEquals("prev", json(fixture.service.get(
        hosted, PACKAGE + "/latest", null, false, null)).path("revision").asText());
    assertTrue(json(fixture.service.get(
        hosted, PREV + "/files", null, false, null))
        .path("files").has("conan_package.tgz"));
    assertEquals("stored", text(fixture.service.get(
        hosted, PREV + "/files/conan_package.tgz", null, false, null)));

    verify(fixture.registry).searchRecipes(1L, "demo*", true, null, 10_000);
    verify(fixture.assets, times(2)).serve(any(), any(), anyString(), eq(false));
  }

  @Test
  void mapsMissingHostedRowsToStableNotFoundResponses() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    doReturn(Optional.empty()).when(fixture.registry).findRecipe(any());

    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        hosted, ROOT + "/latest", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        hosted, RREV + "/files", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        hosted, ROOT, null, false, null));
  }

  @Test
  void acceptsResumableFilesAndCommitsACompleteManifestLastRecipeAtomically() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    fixture.configureIncompleteUpload(hosted, "conanfile.py");
    MavenResponse staged = fixture.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        "text/x-python", SHA1, false, ALICE, "127.0.0.1");
    assertEquals(200, staged.status());
    verify(fixture.registry, never()).commitRevision(any());

    reset(fixture.registry, fixture.assets, fixture.archives, fixture.components);
    fixture.configureDefaults();
    fixture.configureCompleteRecipeUpload(hosted);
    MavenResponse committed = fixture.service.put(
        hosted, RREV + "/files/" + ConanManifest.FILE_NAME,
        InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "127.0.0.1");

    assertEquals(200, committed.status());
    ArgumentCaptor<ConanRegistryDao.RevisionCommit> commit =
        ArgumentCaptor.forClass(ConanRegistryDao.RevisionCommit.class);
    verify(fixture.registry).commitRevision(commit.capture());
    assertEquals(ConanRegistryDao.OWNER_RECIPE, commit.getValue().ownerKind());
    assertEquals(ConanRegistryDao.STATUS_COMMITTED, commit.getValue().status());
    assertEquals(2, commit.getValue().files().size());
    verify(fixture.registry).deleteUploadSession(77L);
    verify(fixture.assets, times(2)).deleteByAssetId(eq(hosted), anyLong());
  }

  @Test
  void publishesMetadataOnlyAfterResolvingTheImmutableOwner() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    ConanAssetSupport.Staged staged = fixture.staged(90L, "proxy-metadata", SHA1);
    when(fixture.assets.stageProxy(
        eq(hosted), eq("metadata/sign"), any(), eq("application/octet-stream"),
        eq("hosted-metadata"))).thenReturn(staged);
    when(fixture.assets.promote(
        eq(hosted), any(), eq("metadata/sign"), eq(staged), anyString(),
        eq("alice"), eq("ip"), any()))
        .thenReturn(asset(190L, 500L, "metadata/sign", 901L));

    assertEquals(200, fixture.service.put(
        hosted, RREV + "/files/metadata/sign", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip").status());

    verify(fixture.registry).upsertMetadataFile(
        eq(ConanRegistryDao.OWNER_RECIPE), anyLong(), any(), eq(1L));
    verify(fixture.assets).discard(hosted, staged);
  }

  @Test
  void rejectsInvalidUploadsBeforeTheyCanCreateDurableState() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    RepositoryRuntime denied = new RepositoryRuntime(
        hosted.id(), hosted.name(), hosted.format(), hosted.type(), hosted.recipeName(),
        true, hosted.blobStoreId(), "DENY", null, null, true, null, null, null, List.of());

    assertThrows(ConanExceptions.MethodNotAllowed.class, () -> fixture.service.put(
        runtime(2L, RepositoryType.PROXY, List.of()), RREV + "/files/conanfile.py",
        InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, true, ALICE, "ip"));
    assertThrows(ConanExceptions.ContentTooLarge.class, () -> fixture.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(),
        20L * 1024 * 1024 * 1024 + 1, null, SHA1, false, ALICE, "ip"));
    assertThrows(ConanExceptions.Forbidden.class, () -> fixture.service.put(
        denied, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));
    assertThrows(ConanExceptions.MethodNotAllowed.class, () -> fixture.service.put(
        hosted, ROOT, InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));
    assertThrows(ConanExceptions.BadRequest.class, () -> fixture.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, "invalid", false, ALICE, "ip"));
    assertThrows(ConanExceptions.Unauthorized.class, () -> fixture.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, false, null, "ip"));

    verify(fixture.registry, never()).openUploadSession(any());
  }

  @Test
  void supportsInternalAndMigrationActorsWithoutWeakeningNormalWritePolicy() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    fixture.configureIncompleteUpload(hosted, "conanfile.py");

    assertEquals(200, fixture.service.putInternal(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, "", "ip").status());
    ArgumentCaptor<ConanRegistryDao.UploadSession> internal =
        ArgumentCaptor.forClass(ConanRegistryDao.UploadSession.class);
    verify(fixture.registry).openUploadSession(internal.capture());
    assertTrue(internal.getValue().actorKey().contains("component-upload"));

    reset(fixture.registry, fixture.assets, fixture.archives, fixture.components);
    fixture.configureDefaults();
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    fixture.configureIncompleteUpload(proxy, "conanfile.py");
    assertEquals(200, fixture.service.putMigration(
        proxy, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, "", "ip", null).status());
  }

  @Test
  void deletesEverySupportedCoordinateAndItsBlobAndComponentProjections() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    ConanRegistryDao.DeletedCoordinate deleted =
        new ConanRegistryDao.DeletedCoordinate(true, List.of(11L, 12L), List.of(21L), 9L);
    when(fixture.registry.deleteCoordinate(any(), anyString(), any())).thenReturn(deleted);
    when(fixture.registry.deleteAllPackages(any(), anyString(), anyString(), any()))
        .thenReturn(deleted);

    for (String path : List.of(ROOT, RREV, RREV + "/packages", PACKAGE, PREV)) {
      assertEquals(200, fixture.service.delete(hosted, path).status());
    }

    verify(fixture.registry, times(4)).deleteCoordinate(any(), anyString(), any());
    verify(fixture.registry).deleteAllPackages(any(), eq("rrev"), anyString(), any());
    verify(fixture.assets, times(10)).deleteByAssetId(eq(hosted), anyLong());
    verify(fixture.components, times(5)).deleteIfNoAssets(21L);
  }

  @Test
  void rejectsUnsupportedOrMissingDeletesAndCleansWholeRecipeComponents() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    when(fixture.registry.deleteCoordinate(any(), anyString(), any())).thenReturn(
        new ConanRegistryDao.DeletedCoordinate(false, List.of(), List.of(), 1));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.delete(hosted, ROOT));
    assertThrows(ConanExceptions.MethodNotAllowed.class, () -> fixture.service.delete(
        runtime(2L, RepositoryType.PROXY, List.of()), ROOT));
    assertThrows(ConanExceptions.MethodNotAllowed.class, () -> fixture.service.delete(
        hosted, RREV + "/files"));

    RepositoryRuntime denied = new RepositoryRuntime(
        hosted.id(), hosted.name(), hosted.format(), hosted.type(), hosted.recipeName(),
        true, hosted.blobStoreId(), "DENY", null, null, true, null, null, null, List.of());
    assertThrows(ConanExceptions.Forbidden.class, () -> fixture.service.delete(denied, ROOT));

    when(fixture.registry.findRecipeByComponent(1L, 501L))
        .thenReturn(Optional.of(recipe(1L)));
    when(fixture.registry.deleteCoordinate(any(), anyString(), any())).thenReturn(
        new ConanRegistryDao.DeletedCoordinate(true, List.of(1L, 2L), List.of(501L), 2));
    assertEquals(2, fixture.service.deleteComponentForCleanup(hosted, 501L, null));
    verify(fixture.assets).deleteByAssetId(hosted, 1L);
    verify(fixture.components).deleteIfNoAssets(501L);
    assertThrows(ConanExceptions.MethodNotAllowed.class, () ->
        fixture.service.deleteComponentForCleanup(
            runtime(3L, RepositoryType.GROUP, List.of(hosted)), 501L, "alice"));
    assertThrows(ConanExceptions.NotFound.class, () ->
        fixture.service.deleteComponentForCleanup(hosted, 999L, "alice"));
  }

  @Test
  void projectsProxyDiscoveryAndMapsUpstreamFailures() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    when(fixture.remote.discovery(eq(proxy), anyString(), any()))
        .thenReturn(new ConanRemoteClient.Discovery(
            "{\"revision\":\"remote-rrev\",\"time\":\"2026-08-01T00:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8),
            null,
            PUBLISHED));

    MavenResponse latest = fixture.service.get(
        proxy, ROOT + "/latest", null, false, null);
    assertEquals("remote-rrev", json(latest).path("revision").asText());
    ArgumentCaptor<ConanRegistryDao.RevisionCommit> discovered =
        ArgumentCaptor.forClass(ConanRegistryDao.RevisionCommit.class);
    verify(fixture.registry).recordDiscoveredRevision(discovered.capture());
    assertEquals("remote-rrev", discovered.getValue().recipeRevision());
    assertEquals(ConanRegistryDao.STATUS_DISCOVERED, discovered.getValue().status());

    when(fixture.remote.discovery(eq(proxy), anyString(), any()))
        .thenThrow(new MavenExceptions.MavenNotFoundException("missing"));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        proxy, ROOT + "/latest", null, false, null));
    when(fixture.remote.discovery(eq(proxy), anyString(), any()))
        .thenThrow(new MavenExceptions.BadUpstreamException("broken"));
    assertThrows(ConanExceptions.BadUpstream.class, () -> fixture.service.get(
        proxy, ROOT + "/latest", null, false, null));
  }

  @Test
  void fetchesBindsAndServesAnUncachedProxyFileUnderTheDatabaseLease() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    when(fixture.registry.findFile(anyString(), anyLong(), anyString()))
        .thenReturn(Optional.empty());
    HttpRemoteFetcher.Result upstream = new HttpRemoteFetcher.Result(
        200,
        Map.of("Content-Type", "application/octet-stream", "X-Checksum-Sha1", SHA1),
        new ByteArrayInputStream(new byte[] {1, 2, 3}));
    when(fixture.remote.fetchFile(proxy, RREV + "/files/conanfile.py"))
        .thenReturn(upstream);
    ConanAssetSupport.Staged staged = fixture.staged(80L, "proxy-file", SHA1);
    when(fixture.assets.stageProxy(
        eq(proxy), eq("conanfile.py"), any(), eq("application/octet-stream"),
        eq(proxy.proxyRemoteUrl()))).thenReturn(staged);
    when(fixture.assets.promote(
        eq(proxy), any(), eq("conanfile.py"), eq(staged), anyString(),
        eq("conan-proxy"), eq(proxy.proxyRemoteUrl()), any()))
        .thenReturn(asset(180L, 500L, "final", 901L));

    assertEquals("stored", text(fixture.service.get(
        proxy, RREV + "/files/conanfile.py", null, false, null)));

    verify(fixture.registry).bindDiscoveredFile(
        eq(ConanRegistryDao.OWNER_RECIPE), anyLong(), any(), eq(5L));
    verify(fixture.assets).discard(proxy, staged);
    verify(fixture.lease).assertHeld();
  }

  @Test
  void mapsProxyFileStatusAndTransportFailuresWithoutPublishing() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    when(fixture.registry.findFile(anyString(), anyLong(), anyString()))
        .thenReturn(Optional.empty());
    when(fixture.remote.fetchFile(proxy, RREV + "/files/missing"))
        .thenReturn(new HttpRemoteFetcher.Result(404, Map.of(), InputStream.nullInputStream()));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        proxy, RREV + "/files/missing", null, false, null));

    when(fixture.remote.fetchFile(proxy, RREV + "/files/failure"))
        .thenReturn(new HttpRemoteFetcher.Result(500, Map.of(), InputStream.nullInputStream()));
    assertThrows(ConanExceptions.BadUpstream.class, () -> fixture.service.get(
        proxy, RREV + "/files/failure", null, false, null));

    when(fixture.remote.fetchFile(proxy, RREV + "/files/io"))
        .thenThrow(new IOException("offline"));
    assertThrows(ConanExceptions.BadUpstream.class, () -> fixture.service.get(
        proxy, RREV + "/files/io", null, false, null));
    verify(fixture.registry, never()).bindDiscoveredFile(anyString(), anyLong(), any(), anyLong());
  }

  @Test
  void resolvesGroupBindingsAndMergesDiscoveryThroughExistingMembers() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime first = runtime(1L, RepositoryType.HOSTED, List.of());
    RepositoryRuntime second = runtime(2L, RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, List.of(first, second));

    when(fixture.registry.findGroupBinding(
        eq(3L), eq(ConanRegistryDao.OWNER_RECIPE), anyString()))
        .thenReturn(Optional.of(new ConanRegistryDao.GroupBinding(
            3L, ConanRegistryDao.OWNER_RECIPE, "binding", 1L, 100L,
            5L, 5L, null, PUBLISHED, PUBLISHED)));
    assertEquals("stored", text(fixture.service.get(
        group, RREV + "/files/conanfile.py", null, false, null)));

    JsonNode merged = json(fixture.service.get(
        group, "v2/conans/search", "q=demo*", false, null));
    assertEquals(1, merged.path("results").size());
    assertEquals("rrev", json(fixture.service.get(
        group, ROOT + "/latest", null, false, null)).path("revision").asText());
    assertEquals(1, json(fixture.service.get(
        group, PACKAGE + "/revisions", null, false, null))
        .path("revisions").size());
  }

  @Test
  void createsFreshGroupBindingAndFailsClosedWhenMembershipChanges() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, List.of(hosted));
    when(fixture.registry.findGroupBinding(anyLong(), anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertEquals(200, fixture.service.get(
        group, RREV + "/files/conanfile.py", null, false, null).status());
    verify(fixture.registry).upsertGroupBindingIfCurrent(any());

    when(fixture.registry.upsertGroupBindingIfCurrent(any())).thenReturn(false);
    assertThrows(ConanExceptions.Busy.class, () -> fixture.service.get(
        group, RREV + "/files/conanfile.py", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        runtime(4L, RepositoryType.GROUP, List.of()), ROOT + "/latest", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        runtime(4L, RepositoryType.GROUP, List.of()),
        RREV + "/files/conanfile.py", null, false, null));
    assertThrows(ConanExceptions.NotFound.class, () -> fixture.service.get(
        group, ROOT, null, false, null));
  }

  @Test
  void discardsInvalidStagedContentButKeepsValidResumableFiles() {
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    Fixture checksum = new Fixture();
    checksum.configureIncompleteUpload(hosted, "conanfile.py");
    ConanAssetSupport.Staged checksumStage = checksum.staged(70L, "staging-conanfile.py", SHA1);
    when(checksum.assets.find(hosted, checksumStage.path()))
        .thenReturn(Optional.of(checksumStage.asset()));
    assertThrows(ConanExceptions.BadRequest.class, () -> checksum.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, "d".repeat(40), false, ALICE, "ip"));
    verify(checksum.assets).discard(eq(hosted), any());

    Fixture archive = new Fixture();
    archive.configureIncompleteUpload(hosted, "conanfile.py");
    ConanAssetSupport.Staged archiveStage = archive.staged(70L, "staging-conanfile.py", SHA1);
    when(archive.assets.find(hosted, archiveStage.path()))
        .thenReturn(Optional.of(archiveStage.asset()));
    when(archive.archives.archive("conanfile.py")).thenReturn(true);
    when(archive.assets.openStaged(hosted, archiveStage.path())).thenReturn(MavenResponse.ok(
        InputStream.nullInputStream(), 0, "application/octet-stream", null, PUBLISHED));
    doThrow(new ConanExceptions.ContentTooLarge("archive limit"))
        .when(archive.archives).inspect(any(), anyLong(), eq("conanfile.py"));
    assertThrows(ConanExceptions.ContentTooLarge.class, () -> archive.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));
    verify(archive.assets).discard(eq(hosted), any());

    Fixture resumable = new Fixture();
    resumable.configureIncompleteUpload(hosted, "conanfile.py");
    ConanAssetSupport.Staged resumableStage =
        resumable.staged(70L, "staging-conanfile.py", SHA1);
    when(resumable.assets.find(hosted, resumableStage.path()))
        .thenReturn(Optional.of(resumableStage.asset()));
    when(resumable.registry.upsertUploadFile(any()))
        .thenThrow(new ConanExceptions.Conflict("retry"));
    assertThrows(ConanExceptions.Conflict.class, () -> resumable.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));
    verify(resumable.assets, never()).discard(eq(hosted), any());
  }

  @Test
  void rejectsMissingPackageSearchRowsAndInvalidManifestSnapshots() throws Exception {
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    Fixture latest = new Fixture();
    doReturn(Optional.empty()).when(latest.registry).findLatestRecipeRevision(anyLong());
    assertThrows(ConanExceptions.NotFound.class, () -> latest.service.get(
        hosted, ROOT + "/search", null, false, null));

    Fixture selected = new Fixture();
    doReturn(Optional.empty()).when(selected.registry).findRecipeRevision(anyLong(), eq("rrev"));
    assertThrows(ConanExceptions.NotFound.class, () -> selected.service.get(
        hosted, RREV + "/search", null, false, null));

    Fixture malformed = new Fixture();
    malformed.configureCompleteRecipeUpload(hosted);
    when(malformed.assets.readStaged(
        eq(hosted), eq(".conan/staging/77/" + ConanManifest.FILE_NAME), anyInt()))
        .thenReturn("broken".getBytes(StandardCharsets.UTF_8));
    assertThrows(ConanExceptions.BadRequest.class, () -> malformed.service.put(
        hosted, RREV + "/files/" + ConanManifest.FILE_NAME, InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));

    Fixture mismatch = new Fixture();
    mismatch.configureCompleteRecipeUpload(hosted);
    when(mismatch.assets.readStaged(
        eq(hosted), eq(".conan/staging/77/" + ConanManifest.FILE_NAME), anyInt()))
        .thenReturn(("1\nmissing.txt: " + "d".repeat(32) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    ConanExceptions.BadRequest mismatchFailure = assertThrows(
        ConanExceptions.BadRequest.class, () -> mismatch.service.put(
            hosted, RREV + "/files/" + ConanManifest.FILE_NAME,
            InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));
    assertTrue(mismatchFailure.getMessage().contains("missing=missing.txt"));
    assertTrue(mismatchFailure.getMessage().contains("unexpected=conanfile.py"));

    Fixture incomplete = new Fixture();
    incomplete.configureCompleteRecipeUpload(hosted);
    when(incomplete.assets.stage(
        eq(hosted), eq(77L), eq("conanfile.py"), any(), anyString(), anyString(), anyString()))
        .thenReturn(incomplete.staged(73L, "incoming-conanfile", SHA1));
    when(incomplete.assets.readStaged(
        eq(hosted), eq(".conan/staging/77/" + ConanManifest.FILE_NAME), anyInt()))
        .thenReturn(("1\nmissing.txt: " + MD5 + "\n").getBytes(StandardCharsets.UTF_8));
    assertEquals(200, incomplete.service.put(
        hosted, RREV + "/files/conanfile.py", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip").status());
    verify(incomplete.registry, never()).beginSessionCommit(anyLong(), anyLong(), any());
  }

  @Test
  void fencesCommitAgainstConcurrentOrMutatedUploadSessions() {
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    Fixture busy = new Fixture();
    busy.configureCompleteRecipeUpload(hosted);
    when(busy.registry.beginSessionCommit(eq(77L), eq(11L), any())).thenReturn(false);
    assertThrows(ConanExceptions.Busy.class, () -> busy.service.put(
        hosted, RREV + "/files/" + ConanManifest.FILE_NAME, InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));

    Fixture missing = new Fixture();
    missing.configureCompleteRecipeUpload(hosted);
    doReturn(Optional.empty()).when(missing.assets).find(hosted, ".conan/staging/77/conanfile.py");
    assertThrows(ConanExceptions.Conflict.class, () -> missing.service.put(
        hosted, RREV + "/files/" + ConanManifest.FILE_NAME, InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));

    Fixture changed = new Fixture();
    changed.configureCompleteRecipeUpload(hosted);
    when(changed.assets.blob(70L)).thenReturn(new AssetBlobRecord(
        1070L, 1L, "blob", new byte[32], "object", new byte[32], SHA1,
        "f".repeat(64), MD5, 4L, "application/octet-stream", "alice", "ip",
        PUBLISHED, PUBLISHED, Map.of()));
    assertThrows(ConanExceptions.Conflict.class, () -> changed.service.put(
        hosted, RREV + "/files/" + ConanManifest.FILE_NAME, InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));

    Fixture immutable = new Fixture();
    immutable.configureCompleteRecipeUpload(hosted);
    when(immutable.registry.commitRevision(any()))
        .thenThrow(new IllegalStateException("immutable"));
    assertThrows(ConanExceptions.Conflict.class, () -> immutable.service.put(
        hosted, RREV + "/files/" + ConanManifest.FILE_NAME, InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));
  }

  @Test
  void restoresCompleteProxyMigrationSessions() {
    Fixture fixture = new Fixture();
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    fixture.configureCompleteRecipeUpload(proxy);

    assertEquals(200, fixture.service.putMigration(
        proxy, RREV + "/files/" + ConanManifest.FILE_NAME, InputStream.nullInputStream(), 0,
        null, SHA1, "migration", "ip", PUBLISHED).status());
    verify(fixture.registry).restoreRevision(any());
    verify(fixture.registry, never()).commitRevision(any());
  }

  @Test
  void servesBothProxyCacheChecksAndProjectsPackageDiscovery() throws Exception {
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    Fixture cached = new Fixture();
    assertEquals("stored", text(cached.service.get(
        proxy, RREV + "/files/conanfile.py", null, false, null)));
    verify(cached.remote, never()).fetchFile(any(), anyString());

    Fixture afterLease = new Fixture();
    when(afterLease.registry.findFile(anyString(), anyLong(), anyString()))
        .thenReturn(Optional.empty(), Optional.of(revisionFile(
            ConanRegistryDao.OWNER_RECIPE, 100L, "conanfile.py")));
    assertEquals("stored", text(afterLease.service.get(
        proxy, RREV + "/files/conanfile.py", null, false, null)));
    verify(afterLease.remote, never()).fetchFile(any(), anyString());

    Fixture packageLatest = new Fixture();
    when(packageLatest.remote.discovery(eq(proxy), anyString(), any()))
        .thenReturn(new ConanRemoteClient.Discovery(
            "{\"revision\":\"remote-prev\",\"time\":\"invalid\"}"
                .getBytes(StandardCharsets.UTF_8),
            ConanMediaTypes.JSON, PUBLISHED));
    assertEquals("remote-prev", json(packageLatest.service.get(
        proxy, PACKAGE + "/latest", null, false, null)).path("revision").asText());
    ArgumentCaptor<ConanRegistryDao.RevisionCommit> projection =
        ArgumentCaptor.forClass(ConanRegistryDao.RevisionCommit.class);
    verify(packageLatest.registry).recordDiscoveredRevision(projection.capture());
    assertEquals("remote-prev", projection.getValue().packageRevision());

    Fixture lists = new Fixture();
    when(lists.remote.discovery(eq(proxy), anyString(), any()))
        .thenReturn(new ConanRemoteClient.Discovery(
            "{}".getBytes(StandardCharsets.UTF_8), null, PUBLISHED));
    assertEquals(200, lists.service.get(proxy, RREV + "/files", null, false, null).status());
    assertEquals(200, lists.service.get(proxy, PREV + "/files", null, false, null).status());
    verify(lists.registry, times(2)).recordDiscoveredRevision(any());

    Fixture invalid = new Fixture();
    when(invalid.remote.discovery(eq(proxy), anyString(), any()))
        .thenReturn(new ConanRemoteClient.Discovery(
            "{}".getBytes(StandardCharsets.UTF_8), null, PUBLISHED));
    assertThrows(ConanExceptions.BadUpstream.class, () -> invalid.service.get(
        proxy, ROOT + "/latest", null, false, null));
    when(invalid.remote.discovery(eq(proxy), anyString(), any()))
        .thenReturn(new ConanRemoteClient.Discovery(
            "not-json".getBytes(StandardCharsets.UTF_8), null, PUBLISHED));
    assertThrows(ConanExceptions.BadUpstream.class, () -> invalid.service.get(
        proxy, ROOT + "/revisions", null, false, null));
  }

  @Test
  void enforcesTheStreamingUploadLimitForBothReadShapes() throws Exception {
    Constructor<?> constructor = Class.forName(
        "com.github.klboke.kkrepo.server.conan.ConanService$MaxBytesInputStream")
        .getDeclaredConstructor(InputStream.class, long.class);
    constructor.setAccessible(true);

    InputStream single = (InputStream) constructor.newInstance(
        new ByteArrayInputStream(new byte[] {1, 2}), 1L);
    assertEquals(1, single.read());
    assertThrows(ConanExceptions.ContentTooLarge.class, single::read);

    InputStream bulk = (InputStream) constructor.newInstance(
        new ByteArrayInputStream(new byte[] {1, 2}), 1L);
    assertThrows(ConanExceptions.ContentTooLarge.class, () -> bulk.read(new byte[2], 0, 2));
  }

  @Test
  void commitsPackageManifestWithProjectedConanInfoAndArchiveEntries() {
    Fixture fixture = new Fixture();
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    fixture.configureCompletePackageUpload(
        hosted, "[settings]\nos=Linux\n[options]\nshared=True\n".getBytes(StandardCharsets.UTF_8),
        Map.of("lib/libdemo.so", "d".repeat(32)));

    assertEquals(200, fixture.service.put(
        hosted, PREV + "/files/" + ConanManifest.FILE_NAME,
        InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip").status());

    ArgumentCaptor<ConanRegistryDao.RevisionCommit> commit =
        ArgumentCaptor.forClass(ConanRegistryDao.RevisionCommit.class);
    verify(fixture.registry).commitRevision(commit.capture());
    assertEquals(ConanRegistryDao.OWNER_PACKAGE, commit.getValue().ownerKind());
    assertEquals("Linux", commit.getValue().settings().get("os"));
    assertEquals("True", commit.getValue().options().get("shared"));
    assertEquals(3, commit.getValue().files().size());
    verify(fixture.archives, times(2)).manifestEntries(
        any(), eq(3L), eq("conan_package.tgz"), eq(""), anyInt());
  }

  @Test
  void rejectsInvalidOrIncompletePackageManifestSnapshots() {
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());

    Fixture incomplete = new Fixture();
    incomplete.configureCompletePackageUpload(
        hosted, "[settings]\nos=Linux\n".getBytes(StandardCharsets.UTF_8), Map.of());
    when(incomplete.archives.packageArchive("conan_package.tgz")).thenReturn(false);
    ConanExceptions.BadRequest missingArchive = assertThrows(
        ConanExceptions.BadRequest.class, () -> incomplete.service.put(
            hosted, PREV + "/files/" + ConanManifest.FILE_NAME,
            InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));
    assertTrue(missingArchive.getMessage().contains("conan_package"));

    Fixture invalidInfo = new Fixture();
    invalidInfo.configureCompletePackageUpload(
        hosted, "[settings]\nos=Linux\n".getBytes(StandardCharsets.UTF_8), Map.of());
    when(invalidInfo.assets.readStaged(
        hosted, ".conan/staging/88/conaninfo.txt", com.github.klboke.kkrepo.protocol.conan.ConanInfo.MAX_BYTES))
        .thenReturn(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThrows(ConanExceptions.BadRequest.class, () -> invalidInfo.service.put(
        hosted, PREV + "/files/" + ConanManifest.FILE_NAME,
        InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));

    Fixture duplicateArchivePath = new Fixture();
    duplicateArchivePath.configureCompletePackageUpload(
        hosted, "[settings]\nos=Linux\n".getBytes(StandardCharsets.UTF_8),
        Map.of("conaninfo.txt", MD5));
    assertThrows(ConanExceptions.BadRequest.class, () -> duplicateArchivePath.service.put(
        hosted, PREV + "/files/" + ConanManifest.FILE_NAME,
        InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));

    Fixture checksumMismatch = new Fixture();
    checksumMismatch.configureCompleteRecipeUpload(hosted);
    when(checksumMismatch.assets.readStaged(
        hosted, ".conan/staging/77/" + ConanManifest.FILE_NAME, ConanManifest.MAX_BYTES))
        .thenReturn(("1\nconanfile.py: " + "d".repeat(32) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    ConanExceptions.BadRequest checksum = assertThrows(
        ConanExceptions.BadRequest.class, () -> checksumMismatch.service.put(
            hosted, RREV + "/files/" + ConanManifest.FILE_NAME,
            InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));
    assertTrue(checksum.getMessage().contains("checksum=conanfile.py"));
  }

  @Test
  void projectsProxyConanInfoBeforeBindingTheDiscoveredPackageFile() throws Exception {
    Fixture fixture = new Fixture();
    RepositoryRuntime proxy = runtime(2L, RepositoryType.PROXY, List.of());
    when(fixture.registry.findFile(anyString(), anyLong(), eq("conaninfo.txt")))
        .thenReturn(Optional.empty());
    byte[] info = "[settings]\nos=Linux\n[requires]\nzlib/1.3.1\n"
        .getBytes(StandardCharsets.UTF_8);
    when(fixture.remote.fetchFile(proxy, PREV + "/files/conaninfo.txt"))
        .thenReturn(new HttpRemoteFetcher.Result(
            200, Map.of("Content-Type", "text/plain"), new ByteArrayInputStream(info)));
    ConanAssetSupport.Staged staged = fixture.staged(81L, "proxy-info", SHA1);
    when(fixture.assets.stageProxy(
        eq(proxy), eq("conaninfo.txt"), any(), eq("text/plain"), eq(proxy.proxyRemoteUrl())))
        .thenReturn(staged);
    when(fixture.assets.readStaged(
        proxy, staged.path(), com.github.klboke.kkrepo.protocol.conan.ConanInfo.MAX_BYTES))
        .thenReturn(info);
    when(fixture.assets.promote(
        eq(proxy), any(), eq("conaninfo.txt"), eq(staged), anyString(),
        eq("conan-proxy"), eq(proxy.proxyRemoteUrl()), any()))
        .thenReturn(asset(181L, 500L, "conaninfo.txt", 901L));

    assertEquals("stored", text(fixture.service.get(
        proxy, PREV + "/files/conaninfo.txt", null, false, null)));

    ArgumentCaptor<ConanRegistryDao.RevisionCommit> commit =
        ArgumentCaptor.forClass(ConanRegistryDao.RevisionCommit.class);
    verify(fixture.registry).recordDiscoveredRevision(commit.capture());
    assertEquals("Linux", commit.getValue().settings().get("os"));
    assertEquals("", commit.getValue().requires().get("zlib/1.3.1"));
  }

  @Test
  void fallsThroughUnavailableGroupMembersAndSupportsNestedBindings() throws Exception {
    Fixture fallback = new Fixture();
    RepositoryRuntime first = runtime(1L, RepositoryType.HOSTED, List.of());
    RepositoryRuntime second = runtime(2L, RepositoryType.HOSTED, List.of());
    RepositoryRuntime offline = runtime(
        9L, RepositoryFormat.CONAN, RepositoryType.HOSTED, false, List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, List.of(offline, first, second));
    when(fallback.registry.findGroupBinding(anyLong(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(fallback.assets.serve(any(), any(), anyString(), anyBoolean())).thenAnswer(invocation -> {
      RepositoryRuntime member = invocation.getArgument(0);
      if (member.id() == first.id()) throw new ConanExceptions.NotFound("first");
      return MavenResponse.ok(
          new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)),
          6, "application/octet-stream", null, PUBLISHED);
    });
    assertEquals("second", text(fallback.service.get(
        group, RREV + "/files/conanfile.py", null, false, null)));

    doAnswer(invocation -> {
      ConanRegistryDao.RecipeCoordinate coordinate = invocation.getArgument(0);
      return coordinate.repositoryId() == first.id()
          ? Optional.empty() : Optional.of(recipe(coordinate.repositoryId()));
    }).when(fallback.registry).findRecipe(any());
    assertEquals("rrev", json(fallback.service.get(
        group, ROOT + "/latest", null, false, null)).path("revision").asText());
    JsonNode packageSearch = json(fallback.service.get(
        group, RREV + "/search", "list_only=False", false, null));
    assertTrue(packageSearch.has("pkg"));

    Fixture nested = new Fixture();
    RepositoryRuntime nestedGroup = runtime(4L, RepositoryType.GROUP, List.of(first));
    RepositoryRuntime outer = runtime(5L, RepositoryType.GROUP, List.of(nestedGroup));
    when(nested.registry.findGroupBinding(
        eq(5L), eq(ConanRegistryDao.OWNER_RECIPE), anyString()))
        .thenReturn(Optional.of(new ConanRegistryDao.GroupBinding(
            5L, ConanRegistryDao.OWNER_RECIPE, "binding", first.id(), 100L,
            5L, 5L, null, PUBLISHED, PUBLISHED)));
    assertEquals("stored", text(nested.service.get(
        outer, RREV + "/files/conanfile.py", null, false, null)));
  }

  @Test
  void boundsGroupJsonResponsesAndMapsReadFailures() throws Exception {
    Fixture fixture = new Fixture();
    Method read = ConanService.class.getDeclaredMethod("readJsonResponse", MavenResponse.class);
    read.setAccessible(true);

    assertInvocationCause(ConanExceptions.BadUpstream.class,
        () -> read.invoke(fixture.service, MavenResponse.noBody(200)));
    assertInvocationCause(ConanExceptions.BadUpstream.class,
        () -> read.invoke(fixture.service, MavenResponse.ok(
            InputStream.nullInputStream(), 32L * 1024 * 1024 + 1,
            ConanMediaTypes.JSON, null, PUBLISHED)));
    InputStream oversized = new InputStream() {
      private long remaining = 32L * 1024 * 1024 + 1;

      @Override
      public int read() {
        if (remaining == 0) return -1;
        remaining--;
        return 0;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) {
        if (remaining == 0) return -1;
        int count = (int) Math.min(remaining, length);
        remaining -= count;
        return count;
      }
    };
    assertInvocationCause(ConanExceptions.BadUpstream.class,
        () -> read.invoke(fixture.service, MavenResponse.ok(
            oversized, 0, ConanMediaTypes.JSON, null, PUBLISHED)));
    InputStream broken = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken member stream");
      }
    };
    assertInvocationCause(ConanExceptions.BadUpstream.class,
        () -> read.invoke(fixture.service, MavenResponse.ok(
            broken, 1, ConanMediaTypes.JSON, null, PUBLISHED)));
  }

  @Test
  void rejectsPackageUploadsWithoutACommittedParentAndArchiveCloseFailures() throws Exception {
    RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, List.of());
    Fixture missingParent = new Fixture();
    doReturn(Optional.empty()).when(missingParent.registry).findRecipe(any());
    assertThrows(ConanExceptions.NotFound.class, () -> missingParent.service.put(
        hosted, PREV + "/files/conaninfo.txt", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));

    Fixture closeFailure = new Fixture();
    closeFailure.configureIncompleteUpload(hosted, "conan_export.tgz");
    when(closeFailure.archives.archive("conan_export.tgz")).thenReturn(true);
    InputStream closesBadly = new ByteArrayInputStream(new byte[0]) {
      @Override
      public void close() throws IOException {
        throw new IOException("close failed");
      }
    };
    when(closeFailure.assets.openStaged(hosted, "staging-conan_export.tgz"))
        .thenReturn(MavenResponse.ok(
            closesBadly, 0, "application/gzip", null, PUBLISHED));
    assertThrows(ConanExceptions.BadRequest.class, () -> closeFailure.service.put(
        hosted, RREV + "/files/conan_export.tgz", InputStream.nullInputStream(), 0,
        null, SHA1, false, ALICE, "ip"));

    Fixture missingPackageRevision = new Fixture();
    when(missingPackageRevision.registry.findPackageRevision(anyLong(), anyString()))
        .thenReturn(Optional.empty());
    Method resolvePackage = ConanService.class.getDeclaredMethod(
        "resolvePackage", long.class, ConanReference.class, boolean.class);
    resolvePackage.setAccessible(true);
    ConanReference reference = new ConanReference(
        "demo", "1.0", "acme", "stable", "rrev", "pkg", "prev");
    assertInvocationCause(ConanExceptions.NotFound.class,
        () -> resolvePackage.invoke(missingPackageRevision.service, 1L, reference, true));

    Fixture manifestCloseFailure = new Fixture();
    manifestCloseFailure.configureCompletePackageUpload(
        hosted, "[settings]\nos=Linux\n".getBytes(StandardCharsets.UTF_8), Map.of());
    InputStream manifestArchive = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
      @Override
      public void close() throws IOException {
        throw new IOException("close failed");
      }
    };
    when(manifestCloseFailure.assets.openStaged(
        hosted, ".conan/staging/88/conan_package.tgz"))
        .thenReturn(MavenResponse.ok(
            manifestArchive, 3, "application/gzip", null, PUBLISHED));
    assertThrows(ConanExceptions.BadRequest.class, () -> manifestCloseFailure.service.put(
        hosted, PREV + "/files/" + ConanManifest.FILE_NAME,
        InputStream.nullInputStream(), 0, null, SHA1, false, ALICE, "ip"));
  }

  @Test
  void createsBodylessEncodedResponsesForHeadRequests() throws Exception {
    Method bytes = ConanService.class.getDeclaredMethod(
        "bytes", byte[].class, boolean.class, String.class, Instant.class);
    bytes.setAccessible(true);
    MavenResponse response = (MavenResponse) bytes.invoke(
        null, "payload".getBytes(StandardCharsets.UTF_8), true, ConanMediaTypes.JSON, PUBLISHED);

    assertEquals(200, response.status());
    assertNull(response.body());
    assertTrue(response.contentLength() > 0);
  }

  private static void assertInvocationCause(
      Class<? extends Throwable> expected, ThrowingInvocation invocation) {
    InvocationTargetException failure = assertThrows(InvocationTargetException.class,
        invocation::invoke);
    assertTrue(expected.isInstance(failure.getCause()));
  }

  @FunctionalInterface
  private interface ThrowingInvocation {
    void invoke() throws Exception;
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, List<RepositoryRuntime> members) {
    return runtime(id, RepositoryFormat.CONAN, type, true, members);
  }

  private static RepositoryRuntime runtime(
      long id,
      RepositoryFormat format,
      RepositoryType type,
      boolean online,
      List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "conan-" + type.name().toLowerCase(), format, type,
        "conan-" + type.name().toLowerCase(), online, 1L, "ALLOW", null, null, true,
        type == RepositoryType.PROXY ? "https://repo.example/conan" : null,
        1440, 60, true, null, members);
  }

  private static ConanRegistryDao.Recipe recipe(long repositoryId) {
    return new ConanRegistryDao.Recipe(
        repositoryId * 10, repositoryId, 901L, "demo", "1.0", "acme", "stable",
        repositoryId * 100, PUBLISHED, PUBLISHED);
  }

  private static ConanRegistryDao.RecipeRevision recipeRevision(long recipeId, String revision) {
    return new ConanRegistryDao.RecipeRevision(
        recipeId * 10, recipeId, revision, SHA256, ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, 5L, PUBLISHED, PUBLISHED);
  }

  private static ConanRegistryDao.Package conanPackage(long recipeRevisionId) {
    return new ConanRegistryDao.Package(
        recipeRevisionId * 10, recipeRevisionId, "pkg", Map.of("os", "Linux"),
        Map.of("shared", "False"), Map.of(), recipeRevisionId * 100,
        PUBLISHED, PUBLISHED);
  }

  private static ConanRegistryDao.PackageRevision packageRevision(long packageId) {
    return new ConanRegistryDao.PackageRevision(
        packageId * 10, packageId, "prev", SHA256, ConanRegistryDao.SOURCE_HOSTED,
        ConanRegistryDao.STATUS_COMMITTED, 5L, PUBLISHED, PUBLISHED);
  }

  private static ConanRegistryDao.RevisionFile revisionFile(
      String ownerKind, long ownerId, String path) {
    return new ConanRegistryDao.RevisionFile(
        ownerId * 10, ownerKind, ownerId, path, 180L, MD5, SHA1, SHA256, 3,
        "application/octet-stream", null, PUBLISHED, PUBLISHED);
  }

  private static ComponentRecord component(long repositoryId) {
    return new ComponentRecord(
        901L, repositoryId, RepositoryFormat.CONAN, "acme/stable", "demo", "1.0",
        "conan-recipe", new byte[32], Map.of(), PUBLISHED);
  }

  private static AssetRecord asset(long id, Long blobId, String path, Long componentId) {
    return new AssetRecord(
        id, 1L, componentId, blobId, RepositoryFormat.CONAN, path, new byte[32],
        path.substring(path.lastIndexOf('/') + 1), "conan", "application/octet-stream",
        3L, null, PUBLISHED, Map.of());
  }

  private static AssetBlobRecord blob(long id, String sha1) {
    return new AssetBlobRecord(
        id, 1L, "blob-" + id, new byte[32], "object-" + id, new byte[32], sha1,
        SHA256, MD5, 3L, "application/octet-stream", "alice", "ip",
        PUBLISHED, PUBLISHED, Map.of());
  }

  private static String text(MavenResponse response) throws IOException {
    try (InputStream body = response.body()) {
      return body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static JsonNode json(MavenResponse response) throws IOException {
    return new ObjectMapper().readTree(text(response));
  }

  private static final class Fixture {
    final ConanRegistryDao registry = mock(ConanRegistryDao.class);
    final ConanAssetSupport assets = mock(ConanAssetSupport.class);
    final ConanComponentService components = mock(ConanComponentService.class);
    final ConanArchiveInspector archives = mock(ConanArchiveInspector.class);
    final ConanLeaseManager leases = mock(ConanLeaseManager.class);
    final ConanLeaseManager.Lease lease = mock(ConanLeaseManager.Lease.class);
    final ConanAuthService auth = mock(ConanAuthService.class);
    final ConanRemoteClient remote = mock(ConanRemoteClient.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ConanService service = new ConanService(
        registry, assets, components, archives, leases, auth, remote, mapper,
        new ImmediateTransactionManager());

    Fixture() {
      configureDefaults();
    }

    void configureDefaults() {
      when(leases.acquire(anyLong(), anyString())).thenReturn(lease);
      when(lease.fencingToken()).thenReturn(11L);
      when(lease.expiresAt()).thenReturn(PUBLISHED.plusSeconds(300));
      when(registry.findRecipe(any())).thenAnswer(invocation -> {
        ConanRegistryDao.RecipeCoordinate coordinate = invocation.getArgument(0);
        return Optional.of(recipe(coordinate.repositoryId()));
      });
      when(registry.findRecipeRevision(anyLong(), anyString())).thenAnswer(invocation ->
          Optional.of(recipeRevision(invocation.getArgument(0), invocation.getArgument(1))));
      when(registry.findLatestRecipeRevision(anyLong())).thenAnswer(invocation ->
          Optional.of(recipeRevision(invocation.getArgument(0), "rrev")));
      when(registry.listRecipeRevisions(anyLong(), any(), anyInt())).thenAnswer(invocation ->
          List.of(recipeRevision(invocation.getArgument(0), "rrev")));
      when(registry.searchRecipes(anyLong(), anyString(), anyBoolean(), any(), anyInt()))
          .thenAnswer(invocation -> List.of(recipe(invocation.getArgument(0))));
      when(registry.findPackage(anyLong(), anyString())).thenAnswer(invocation ->
          Optional.of(conanPackage(invocation.getArgument(0))));
      when(registry.listPackages(anyLong(), any(), anyInt())).thenAnswer(invocation ->
          List.of(conanPackage(invocation.getArgument(0))));
      when(registry.findPackageRevision(anyLong(), anyString())).thenAnswer(invocation ->
          Optional.of(packageRevision(invocation.getArgument(0))));
      when(registry.findLatestPackageRevision(anyLong())).thenAnswer(invocation ->
          Optional.of(packageRevision(invocation.getArgument(0))));
      when(registry.listPackageRevisions(anyLong(), any(), anyInt())).thenAnswer(invocation ->
          List.of(packageRevision(invocation.getArgument(0))));
      when(registry.listFiles(anyString(), anyLong(), any(), anyInt())).thenAnswer(invocation -> {
        String kind = invocation.getArgument(0);
        return List.of(revisionFile(
            kind, invocation.getArgument(1),
            ConanRegistryDao.OWNER_RECIPE.equals(kind) ? "conanfile.py" : "conan_package.tgz"));
      });
      when(registry.findFile(anyString(), anyLong(), anyString())).thenAnswer(invocation ->
          Optional.of(revisionFile(
              invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
      when(registry.currentRepositoryRevision(anyLong())).thenReturn(5L);
      when(registry.upsertGroupBindingIfCurrent(any())).thenReturn(true);
      when(registry.recordDiscoveredRevision(any())).thenReturn(
          new ConanRegistryDao.CommittedRevision(10L, 100L, null, null, 100L, 5L, false));
      when(components.component(any(), any(), any())).thenAnswer(invocation ->
          component(((RepositoryRuntime) invocation.getArgument(0)).id()));
      when(assets.serve(any(), any(), anyString(), anyBoolean())).thenAnswer(invocation ->
          MavenResponse.ok(
              new ByteArrayInputStream("stored".getBytes(StandardCharsets.UTF_8)),
              6, "application/octet-stream", null, PUBLISHED));
      when(archives.archive(anyString())).thenReturn(false);
    }

    ConanAssetSupport.Staged staged(long assetId, String path, String sha1) {
      AssetBlobRecord blob = blob(assetId + 1000, sha1);
      AssetRecord asset = asset(assetId, blob.id(), path, null);
      return new ConanAssetSupport.Staged(path, asset, blob);
    }

    void configureIncompleteUpload(RepositoryRuntime runtime, String filePath) {
      ConanRegistryDao.UploadSession session = new ConanRegistryDao.UploadSession(
          77L, runtime.id(), ConanRegistryDao.OWNER_RECIPE, "coordinate", "actor",
          ConanRegistryDao.SESSION_OPEN, "node", 0, null,
          PUBLISHED.plusSeconds(3600), PUBLISHED, PUBLISHED);
      when(registry.openUploadSession(any())).thenReturn(session);
      ConanAssetSupport.Staged staged = staged(70L, "staging-" + filePath, SHA1);
      when(assets.stage(
          eq(runtime), eq(77L), eq(filePath), any(), anyString(), anyString(), anyString()))
          .thenReturn(staged);
      ConanRegistryDao.UploadFile file = new ConanRegistryDao.UploadFile(
          1L, 77L, filePath, staged.asset().id(), MD5, SHA1, SHA256, 3,
          "application/octet-stream", PUBLISHED, PUBLISHED);
      when(registry.listUploadFiles(77L)).thenReturn(List.of(file));
    }

    void configureCompleteRecipeUpload(RepositoryRuntime runtime) {
      ConanRegistryDao.UploadSession session = new ConanRegistryDao.UploadSession(
          77L, runtime.id(), ConanRegistryDao.OWNER_RECIPE, "coordinate", "actor",
          ConanRegistryDao.SESSION_OPEN, "node", 0, null,
          PUBLISHED.plusSeconds(3600), PUBLISHED, PUBLISHED);
      when(registry.openUploadSession(any())).thenReturn(session);
      ConanAssetSupport.Staged incoming = staged(72L, "incoming-manifest", SHA1);
      when(assets.stage(
          eq(runtime), eq(77L), eq(ConanManifest.FILE_NAME), any(), anyString(),
          anyString(), anyString())).thenReturn(incoming);
      ConanRegistryDao.UploadFile conanfile = new ConanRegistryDao.UploadFile(
          1L, 77L, "conanfile.py", 70L, MD5, SHA1, SHA256, 3,
          "text/x-python", PUBLISHED, PUBLISHED);
      ConanRegistryDao.UploadFile manifest = new ConanRegistryDao.UploadFile(
          2L, 77L, ConanManifest.FILE_NAME, 72L, MD5, SHA1, SHA256, 3,
          "text/plain", PUBLISHED, PUBLISHED);
      List<ConanRegistryDao.UploadFile> files = List.of(conanfile, manifest);
      when(registry.listUploadFiles(77L)).thenReturn(files);
      when(assets.readStaged(
          eq(runtime), eq(".conan/staging/77/" + ConanManifest.FILE_NAME), anyInt()))
          .thenReturn(("1\nconanfile.py: " + MD5 + "\n")
              .getBytes(StandardCharsets.UTF_8));
      AssetRecord conanfileAsset = asset(70L, 1070L, ".conan/staging/77/conanfile.py", null);
      AssetRecord manifestAsset = asset(
          72L, 1072L, ".conan/staging/77/" + ConanManifest.FILE_NAME, null);
      when(assets.find(runtime, conanfileAsset.path())).thenReturn(Optional.of(conanfileAsset));
      when(assets.find(runtime, manifestAsset.path())).thenReturn(Optional.of(manifestAsset));
      when(assets.blob(70L)).thenReturn(blob(1070L, SHA1));
      when(assets.blob(72L)).thenReturn(blob(1072L, SHA1));
      when(assets.promote(eq(runtime), any(), anyString(), any(), anyString(), anyString(),
          anyString(), any())).thenAnswer(invocation -> {
            ConanAssetSupport.Staged staged = invocation.getArgument(3);
            return asset(staged.asset().id() + 100, staged.blob().id(),
                invocation.getArgument(2), 901L);
          });
      when(registry.beginSessionCommit(eq(77L), eq(11L), any())).thenReturn(true);
    }

    void configureCompletePackageUpload(
        RepositoryRuntime runtime, byte[] infoBytes, Map<String, String> archivedEntries) {
      ConanRegistryDao.UploadSession session = new ConanRegistryDao.UploadSession(
          88L, runtime.id(), ConanRegistryDao.OWNER_PACKAGE, "coordinate", "actor",
          ConanRegistryDao.SESSION_OPEN, "node", 0, null,
          PUBLISHED.plusSeconds(3600), PUBLISHED, PUBLISHED);
      when(registry.openUploadSession(any())).thenReturn(session);
      ConanAssetSupport.Staged incoming = staged(92L, "incoming-package-manifest", SHA1);
      when(assets.stage(
          eq(runtime), eq(88L), eq(ConanManifest.FILE_NAME), any(), anyString(),
          anyString(), anyString())).thenReturn(incoming);
      ConanRegistryDao.UploadFile info = new ConanRegistryDao.UploadFile(
          1L, 88L, "conaninfo.txt", 90L, MD5, SHA1, SHA256, 3,
          "text/plain", PUBLISHED, PUBLISHED);
      ConanRegistryDao.UploadFile archive = new ConanRegistryDao.UploadFile(
          2L, 88L, "conan_package.tgz", 91L, "e".repeat(32), SHA1, SHA256, 3,
          "application/gzip", PUBLISHED, PUBLISHED);
      ConanRegistryDao.UploadFile manifest = new ConanRegistryDao.UploadFile(
          3L, 88L, ConanManifest.FILE_NAME, 92L, "f".repeat(32), SHA1, SHA256, 3,
          "text/plain", PUBLISHED, PUBLISHED);
      List<ConanRegistryDao.UploadFile> files = List.of(info, archive, manifest);
      when(registry.listUploadFiles(88L)).thenReturn(files);
      StringBuilder manifestText = new StringBuilder("1\nconaninfo.txt: ").append(MD5).append('\n');
      archivedEntries.forEach((path, checksum) -> {
        if (!"conaninfo.txt".equals(path)) {
          manifestText.append(path).append(": ").append(checksum).append('\n');
        }
      });
      when(assets.readStaged(
          runtime, ".conan/staging/88/" + ConanManifest.FILE_NAME, ConanManifest.MAX_BYTES))
          .thenReturn(manifestText.toString().getBytes(StandardCharsets.UTF_8));
      when(assets.readStaged(
          runtime, ".conan/staging/88/conaninfo.txt",
          com.github.klboke.kkrepo.protocol.conan.ConanInfo.MAX_BYTES))
          .thenReturn(infoBytes);
      when(archives.archive("conan_package.tgz")).thenReturn(true);
      when(archives.packageArchive("conan_package.tgz")).thenReturn(true);
      when(assets.openStaged(runtime, ".conan/staging/88/conan_package.tgz"))
          .thenAnswer(ignored -> MavenResponse.ok(
              new ByteArrayInputStream(new byte[] {1, 2, 3}), 3,
              "application/gzip", null, PUBLISHED));
      when(archives.manifestEntries(
          any(), eq(3L), eq("conan_package.tgz"), eq(""), anyInt()))
          .thenReturn(archivedEntries);
      for (long assetId : new long[] {90L, 91L, 92L}) {
        String path = switch ((int) assetId) {
          case 90 -> ".conan/staging/88/conaninfo.txt";
          case 91 -> ".conan/staging/88/conan_package.tgz";
          default -> ".conan/staging/88/" + ConanManifest.FILE_NAME;
        };
        AssetRecord value = asset(assetId, assetId + 1000, path, null);
        when(assets.find(runtime, path)).thenReturn(Optional.of(value));
        when(assets.blob(assetId)).thenReturn(blob(assetId + 1000, SHA1));
      }
      when(assets.promote(eq(runtime), any(), anyString(), any(), anyString(), anyString(),
          anyString(), any())).thenAnswer(invocation -> {
            ConanAssetSupport.Staged staged = invocation.getArgument(3);
            return asset(staged.asset().id() + 100, staged.blob().id(),
                invocation.getArgument(2), 901L);
          });
      when(registry.beginSessionCommit(eq(88L), eq(11L), any())).thenReturn(true);
    }
  }

  private static final class ImmediateTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
