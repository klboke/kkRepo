package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.alpine.AlpineMediaTypes;
import com.github.klboke.kkrepo.protocol.alpine.AlpineSignature;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawProxyService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlpineServiceTest {
  private static final String NAMESPACE = "v3.20/main/x86_64";
  private static final String PACKAGE_PATH = NAMESPACE + "/demo-1.0-r0.apk";

  private final AlpineRegistryDao registry = mock(AlpineRegistryDao.class);
  private final AlpinePublishedSnapshotCache published = mock(AlpinePublishedSnapshotCache.class);
  private final AlpineRepositorySettings repositorySettings = mock(AlpineRepositorySettings.class);
  private final AlpineAssetSupport assets = mock(AlpineAssetSupport.class);
  private final AlpineIndexBuilder indexBuilder = mock(AlpineIndexBuilder.class);
  private final AlpineSigningService signing = mock(AlpineSigningService.class);
  private final AlpineLeaseManager leases = mock(AlpineLeaseManager.class);
  private final AlpineLeaseManager.Lease lease = mock(AlpineLeaseManager.Lease.class);
  private final RawProxyService proxy = mock(RawProxyService.class);
  private final AlpineProxyProjectionService projection = mock(AlpineProxyProjectionService.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final AlpinePackageInspector inspector = new AlpinePackageInspector();
  private final AlpineComponentFactory components = new AlpineComponentFactory();
  private final RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, "ALLOW", List.of());
  private final AlpineRepositorySettings.Settings settings = settings(true, true);
  private AlpineService service;

  @BeforeEach
  void setUp() {
    service = new AlpineService(
        registry, published, repositorySettings, inspector, components, assets, indexBuilder,
        signing, leases, proxy, projection, runtimes);
    when(repositorySettings.get(any())).thenReturn(settings);
    when(leases.acquire(anyString())).thenReturn(lease);
    when(leases.tryAcquire(anyString())).thenReturn(Optional.of(lease));
    when(assets.serve(any(), anyString(), anyBoolean())).thenReturn(MavenResponse.noBody(200));
    when(assets.storePackage(any(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(asset(10L, 20L, 30L, PACKAGE_PATH, RepositoryFormat.ALPINE, 1L));
    AlpineRegistryDao.SuiteState clean = suite(1L, NAMESPACE, 1L, 1L);
    when(registry.findSuite(anyLong(), anyString())).thenReturn(Optional.of(clean));
    when(registry.ensureSuite(anyLong(), anyString(), any())).thenReturn(clean);
    when(published.find(anyLong(), anyString())).thenReturn(Optional.of(snapshot(1L, 1L,
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/index"))));
    when(registry.savePackage(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 50L));
    when(signing.active(any())).thenReturn(material());
  }

  @Test
  void publishesRealApkIdempotentlyAndRejectsImmutableCoordinateChanges() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");

    AlpineService.PublishedPackage first = service.publish(
        hosted, "v3.20", "main", "x86_64", "demo-1.0-r0.apk",
        new ByteArrayInputStream(fixture.bytes()), "alice", "127.0.0.1");

    assertEquals(PACKAGE_PATH, first.path());
    assertEquals("demo", first.packageName());
    assertEquals("1.0-r0", first.version());
    assertEquals("x86_64", first.architecture());
    assertEquals(64, first.sha256().length());
    ArgumentCaptor<AlpineRegistryDao.PackageRecord> saved =
        ArgumentCaptor.forClass(AlpineRegistryDao.PackageRecord.class);
    verify(registry).savePackage(saved.capture());
    assertEquals("HOSTED", saved.getValue().sourceKind());
    assertEquals("demo", saved.getValue().controlFields().get("P"));
    verify(registry).replacePackageRelations(eq(50L), any());

    when(registry.findPackage(
        hosted.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.of(withId(saved.getValue(), 50L)));
    AlpineService.PublishedPackage duplicate = service.publish(
        hosted, "v3.20", "main", "x86_64", "demo-1.0-r0.apk",
        new ByteArrayInputStream(fixture.bytes()), "alice", null);
    assertEquals(first.sha256(), duplicate.sha256());
    verify(assets, times(1)).storePackage(
        any(), anyString(), anyString(), any(), any(), any(), any(), any());

    AlpineRegistryDao.PackageRecord conflicting = packageRecord(
        50L, hosted.id(), PACKAGE_PATH, "different", 10L, 20L, AlpineRegistryDao.SOURCE_HOSTED);
    when(registry.findPackage(
        hosted.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.of(conflicting));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        hosted, "v3.20", "main", "x86_64", "demo-1.0-r0.apk",
        new ByteArrayInputStream(fixture.bytes()), "alice", null));
  }

  @Test
  void putValidatesPathsArchitectureNamespaceAndWritePolicy() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "noarch");
    MavenResponse response = service.put(
        hosted, PACKAGE_PATH, new ByteArrayInputStream(fixture.bytes()),
        AlpineMediaTypes.APK_PACKAGE, "alice", null);
    assertEquals(200, response.status());
    assertEquals(PACKAGE_PATH, response.headers().get("Location"));

    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.put(
        hosted, NAMESPACE + "/APKINDEX.tar.gz", new ByteArrayInputStream(fixture.bytes()),
        null, null, null));
    assertThrows(MavenExceptions.BadRequestException.class, () -> service.put(
        hosted, NAMESPACE + "/Packages.adb", new ByteArrayInputStream(fixture.bytes()),
        null, null, null));
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.put(
        runtime(2L, RepositoryType.PROXY, "ALLOW", List.of()), PACKAGE_PATH,
        new ByteArrayInputStream(fixture.bytes()), null, null, null));

    AlpineTestPackage.Fixture wrongArch = AlpineTestPackage.apk("demo", "1.0-r0", "aarch64");
    assertThrows(MavenExceptions.BadRequestException.class, () -> service.put(
        hosted, PACKAGE_PATH, new ByteArrayInputStream(wrongArch.bytes()), null, null, null));

    when(repositorySettings.get(hosted)).thenReturn(new AlpineRepositorySettings.Settings(
        List.of("edge"), List.of(), List.of(), true, true, true,
        "key.rsa.pub", "RSA", "", List.of()));
    assertThrows(MavenExceptions.MavenNotFoundException.class, () -> service.put(
        hosted, PACKAGE_PATH, new ByteArrayInputStream(fixture.bytes()), null, null, null));

    RepositoryRuntime denied = runtime(3L, RepositoryType.HOSTED, "DENY", List.of());
    when(repositorySettings.get(denied)).thenReturn(settings);
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        denied, "v3.20", "main", "x86_64", "demo-1.0-r0.apk",
        new ByteArrayInputStream(fixture.bytes()), null, null));
  }

  @Test
  void servesHostedSnapshotsPackagesAndPublicKeyWithProtocolCacheSemantics() throws Exception {
    MavenResponse packageResponse = service.get(hosted, PACKAGE_PATH, true);
    assertEquals("public, max-age=31536000, immutable",
        packageResponse.headers().get("Cache-Control"));
    verify(assets).serve(hosted, PACKAGE_PATH, true);

    MavenResponse index = service.get(hosted, NAMESPACE + "/APKINDEX.tar.gz", false);
    assertEquals("public, max-age=0, must-revalidate", index.headers().get("Cache-Control"));
    verify(assets).serve(hosted, ".alpine/index", false);

    when(published.find(hosted.id(), "edge/main/x86_64"))
        .thenReturn(Optional.of(snapshot(hosted.id(), 1L, Map.of())));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "edge/main/x86_64/APKINDEX.tar.gz", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, NAMESPACE + "/Packages.adb", false));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(null, PACKAGE_PATH, false));

    when(signing.active(hosted)).thenReturn(new AlpineSigningService.SigningMaterial(
        1, "fixture.rsa.pub", "fingerprint", "PUBLIC", null, null, AlpineSignature.Type.RSA));
    MavenResponse head = service.publicKey(hosted, true);
    assertEquals(200, head.status());
    assertFalse(head.hasBody());
    assertEquals(6, head.contentLength());
    MavenResponse body = service.publicKey(hosted, false);
    assertTrue(body.hasBody());
    assertEquals("PUBLIC", new String(body.body().readAllBytes(), StandardCharsets.US_ASCII));
    assertEquals("attachment; filename=\"fixture.rsa.pub\"",
        body.headers().get("Content-Disposition"));
  }

  @Test
  void proxyPassthroughUsesRawCacheWithoutRewritingResponse() {
    RepositoryRuntime proxyRuntime = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    AlpineRepositorySettings.Settings passthrough = settings(false, true);
    when(repositorySettings.get(proxyRuntime)).thenReturn(passthrough);
    MavenResponse metadata = MavenResponse.noBody(200);
    MavenResponse apk = MavenResponse.noBody(200);
    when(proxy.getMetadataFromUrlUnindexed(
        eq(proxyRuntime), eq(NAMESPACE + "/APKINDEX.tar.gz"), anyString(), eq(false)))
        .thenReturn(metadata);
    when(proxy.getPinnedAssetFromUrlUnindexed(
        eq(proxyRuntime), eq(PACKAGE_PATH), anyString(), eq(true))).thenReturn(apk);

    assertEquals("public, max-age=0, must-revalidate",
        service.get(proxyRuntime, NAMESPACE + "/APKINDEX.tar.gz", false)
            .headers().get("Cache-Control"));
    assertEquals("public, max-age=31536000, immutable",
        service.get(proxyRuntime, PACKAGE_PATH, true).headers().get("Cache-Control"));
    verify(projection, times(2)).observePassthrough(
        eq(proxyRuntime), eq(passthrough), any(com.github.klboke.kkrepo.protocol.alpine.AlpinePath.class));
  }

  @Test
  void resignedProxyRefreshesProjectionAndCanServeLastGoodSnapshotOnError() {
    RepositoryRuntime proxyRuntime = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    when(repositorySettings.get(proxyRuntime)).thenReturn(settings);
    when(projection.refreshDue(eq(proxyRuntime), eq(settings), eq(NAMESPACE), any()))
        .thenReturn(true);
    when(registry.findPackageByPath(proxyRuntime.id(), PACKAGE_PATH))
        .thenReturn(Optional.of(packageRecord(
            1L, proxyRuntime.id(), PACKAGE_PATH, "a".repeat(64), 10L, 20L,
            AlpineRegistryDao.SOURCE_PROXY)));

    MavenResponse response = service.get(proxyRuntime, PACKAGE_PATH, false);
    assertEquals("public, max-age=31536000, immutable",
        response.headers().get("Cache-Control"));
    verify(registry).ensureSuite(eq(proxyRuntime.id()), eq(NAMESPACE), any());
    verify(registry).markSuiteDirty(eq(proxyRuntime.id()), eq(NAMESPACE), any());

    when(projection.refreshDue(eq(proxyRuntime), eq(settings), eq(NAMESPACE), any()))
        .thenThrow(new IllegalStateException("upstream down"));
    assertEquals(200, service.get(proxyRuntime, PACKAGE_PATH, false).status());

    AlpineRepositorySettings.Settings failClosed = settings(true, false);
    when(repositorySettings.get(proxyRuntime)).thenReturn(failClosed);
    when(projection.refreshDue(eq(proxyRuntime), eq(failClosed), eq(NAMESPACE), any()))
        .thenThrow(new IllegalStateException("upstream down"));
    assertThrows(IllegalStateException.class,
        () -> service.get(proxyRuntime, PACKAGE_PATH, false));
  }

  @Test
  void deletesPackagesAndCleanupComponentsWhilePreservingSnapshotBytes() {
    AlpineRegistryDao.PackageRecord row = packageRecord(
        50L, hosted.id(), PACKAGE_PATH, "a".repeat(64), 10L, 20L,
        AlpineRegistryDao.SOURCE_HOSTED);
    when(registry.findPackageByPath(hosted.id(), PACKAGE_PATH)).thenReturn(Optional.of(row));
    when(registry.deletePackage(
        eq(hosted.id()), eq(NAMESPACE), eq("main"), eq("demo"), eq("1.0-r0"),
        eq("x86_64"), eq("manual"), any(Instant.class))).thenReturn(Optional.of(row));

    assertEquals(204, service.delete(hosted, PACKAGE_PATH, "manual", true).status());
    verify(assets).retirePackageProjection(10L);
    verify(assets, never()).delete(hosted, PACKAGE_PATH);

    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.delete(hosted, NAMESPACE + "/APKINDEX.tar.gz", "manual", false));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.delete(runtime(3L, RepositoryType.HOSTED, "DENY", List.of()),
            PACKAGE_PATH, "manual", true));

    AssetRecord valid = asset(10L, 20L, 30L, PACKAGE_PATH, RepositoryFormat.ALPINE, hosted.id());
    AssetRecord otherRepo = asset(11L, 20L, 30L, PACKAGE_PATH, RepositoryFormat.ALPINE, 99L);
    AssetRecord otherFormat = asset(12L, 20L, 30L, "raw", RepositoryFormat.RAW, hosted.id());
    when(assets.listAssetsByComponent(30L)).thenReturn(List.of(valid, otherRepo, otherFormat));
    when(registry.deletePackage(
        eq(hosted.id()), eq(NAMESPACE), eq("main"), eq("demo"), eq("1.0-r0"),
        eq("x86_64"), eq("cleanup"), any(Instant.class))).thenReturn(Optional.of(row));
    assertEquals(List.of(0, 0, 1),
        service.deleteComponentsForCleanup(
            hosted, java.util.Arrays.asList(null, -1L, 30L), "cleanup"));
    assertEquals(List.of(), service.deleteComponentsForCleanup(hosted, null, "cleanup"));
  }

  @Test
  void exposesStatusRotatesKeysAndRebuildsEveryKnownNamespace() {
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    AlpineRegistryDao.SigningKey key = new AlpineRegistryDao.SigningKey(
        hosted.id(), 2, "fixture.rsa.pub", "fingerprint", "secret", "public", "RSA256", true,
        now);
    when(signing.rotate(eq(hosted), eq("private"), eq("fixture.rsa.pub"), eq("RSA256")))
        .thenReturn(key);
    when(signing.rotateGenerated(eq(hosted), eq("key.rsa.pub"), eq("RSA"))).thenReturn(key);
    when(registry.listDistributions(hosted.id())).thenReturn(List.of(NAMESPACE));
    when(registry.listSuites(hosted.id())).thenReturn(List.of(new AlpineRegistryDao.SuiteState(
        hosted.id(), NAMESPACE, 3L, now, 2L, 1, now, "failed", now, now)));
    when(registry.findActiveSigningKey(hosted.id())).thenReturn(Optional.of(key));
    when(registry.listProxyDistributions(hosted.id())).thenReturn(List.of(
        new AlpineRegistryDao.ProxyDistribution(
            hosted.id(), NAMESPACE, "release", Map.of(), true, now, now)));

    assertEquals(key, service.rotateKey(hosted, "private", "fixture.rsa.pub", "RSA256"));
    assertEquals(key, service.rotateGeneratedKey(hosted));
    verify(registry, times(2)).markSuiteDirty(eq(hosted.id()), eq(NAMESPACE), any());

    service.rebuild(hosted, "  " + NAMESPACE + "  ");
    assertThrows(MavenExceptions.BadRequestException.class, () -> service.rebuild(hosted, "bad"));
    AlpineService.Status status = service.status(hosted);
    assertEquals(1, status.namespaces().size());
    assertEquals("failed", status.namespaces().getFirst().lastError());
    assertEquals(2, status.activeKey().revision());
    assertEquals("release", status.proxyNamespaces().getFirst().indexIdentity());

    when(registry.findActiveSigningKey(hosted.id())).thenReturn(Optional.empty());
    assertNull(service.status(hosted).activeKey());
  }

  @Test
  void publishesDirtyHostedSnapshotWithFencingAndRecordsFailures() {
    AlpineRegistryDao.SuiteState dirty = suite(hosted.id(), NAMESPACE, 2L, 1L);
    when(registry.findSuite(hosted.id(), NAMESPACE)).thenReturn(Optional.of(dirty));
    when(published.find(hosted.id(), NAMESPACE)).thenReturn(Optional.empty());
    AlpineIndexBuilder.BuiltSnapshot built = new AlpineIndexBuilder.BuiltSnapshot(
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/2/index"), "a".repeat(64), 100L,
        1, Instant.EPOCH);
    when(indexBuilder.build(hosted, settings, dirty, material())).thenReturn(built);
    when(registry.publishSnapshot(any(), any(), anyLong())).thenReturn(true);
    when(lease.owner()).thenReturn("owner");
    when(lease.fencingToken()).thenReturn(7L);

    assertTrue(service.publishPendingIfAvailable(hosted, NAMESPACE));
    ArgumentCaptor<AlpineRegistryDao.Snapshot> snapshot =
        ArgumentCaptor.forClass(AlpineRegistryDao.Snapshot.class);
    verify(registry).publishSnapshot(snapshot.capture(), eq("owner"), eq(7L));
    assertEquals(2L, snapshot.getValue().revision());
    verify(published).published(snapshot.getValue());

    when(indexBuilder.build(hosted, settings, dirty, material()))
        .thenThrow(new IllegalStateException("build failed"));
    assertThrows(IllegalStateException.class,
        () -> service.publishPendingIfAvailable(hosted, NAMESPACE));
    verify(registry).recordBuildFailure(
        eq(hosted.id()), eq(NAMESPACE), eq(2L), eq("build failed"), any());

    when(leases.tryAcquire(anyString())).thenReturn(Optional.empty());
    assertFalse(service.publishPendingIfAvailable(hosted, NAMESPACE));
  }

  @Test
  void groupPublishesDeterministicBindingAndServesMemberPackage() {
    RepositoryRuntime member = runtime(2L, RepositoryType.HOSTED, "ALLOW", List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, "ALLOW", List.of(member));
    AlpineRegistryDao.SuiteState dirty = suite(group.id(), NAMESPACE, 2L, 1L);
    AlpineRegistryDao.Snapshot memberSnapshot = snapshot(member.id(), 4L,
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/member"));
    when(registry.findSuite(group.id(), NAMESPACE)).thenReturn(Optional.of(dirty));
    when(published.find(anyLong(), eq(NAMESPACE))).thenAnswer(invocation -> {
      long repositoryId = invocation.getArgument(0);
      return repositoryId == member.id() ? Optional.of(memberSnapshot) : Optional.empty();
    });
    when(registry.listPackages(member.id(), NAMESPACE)).thenReturn(List.of(packageRecord(
        50L, member.id(), PACKAGE_PATH, "a".repeat(64), 10L, 20L,
        AlpineRegistryDao.SOURCE_HOSTED)));
    AlpineIndexBuilder.BuiltSnapshot built = new AlpineIndexBuilder.BuiltSnapshot(
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/group"), "b".repeat(64), 100L,
        1, Instant.EPOCH);
    when(indexBuilder.build(eq(group), eq(settings), eq(dirty), eq(material()),
        any(AlpineIndexBuilder.PackageSource.class))).thenReturn(built);
    when(registry.publishGroupSnapshot(any(), any(), any(), anyLong())).thenReturn(true);
    when(lease.owner()).thenReturn("owner");
    when(lease.fencingToken()).thenReturn(8L);

    assertTrue(service.publishPendingIfAvailable(group, NAMESPACE));
    ArgumentCaptor<List<AlpineRegistryDao.GroupBinding>> bindings = ArgumentCaptor.forClass(List.class);
    verify(registry).publishGroupSnapshot(any(), bindings.capture(), eq("owner"), eq(8L));
    assertEquals(1, bindings.getValue().size());
    assertEquals(member.id(), bindings.getValue().getFirst().memberRepositoryId());

    String fingerprint = AlpineService.fingerprint(Map.of(member.id(), 4L));
    AlpineRegistryDao.Snapshot groupSnapshot = snapshot(group.id(), 2L, Map.of(
        "@members", fingerprint, NAMESPACE + "/APKINDEX.tar.gz", ".alpine/group"));
    when(published.find(group.id(), NAMESPACE)).thenReturn(Optional.of(groupSnapshot));
    when(registry.findSuite(member.id(), NAMESPACE))
        .thenReturn(Optional.of(suite(member.id(), NAMESPACE, 4L, 4L)));
    when(registry.findGroupBinding(group.id(), NAMESPACE, 2L, PACKAGE_PATH))
        .thenReturn(Optional.of(bindings.getValue().getFirst()));
    when(runtimes.resolveById(member.id())).thenReturn(Optional.of(member));
    MavenResponse response = service.get(group, PACKAGE_PATH, false);
    assertEquals(member.name(), response.headers().get("X-kkRepo-Source-Repository"));
    verify(assets).serve(member, PACKAGE_PATH, false);
  }

  @Test
  void groupRejectsPassthroughProxyMembersAndMembershipCycles() {
    RepositoryRuntime proxyMember = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, "ALLOW", List.of(proxyMember));
    when(repositorySettings.get(proxyMember)).thenReturn(settings(false, true));
    when(registry.findSuite(group.id(), NAMESPACE))
        .thenReturn(Optional.of(suite(group.id(), NAMESPACE, 2L, 1L)));
    when(published.find(group.id(), NAMESPACE)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class,
        () -> service.publishPendingIfAvailable(group, NAMESPACE));

    RepositoryRuntime cyclic = mock(RepositoryRuntime.class);
    when(cyclic.id()).thenReturn(9L);
    when(cyclic.format()).thenReturn(RepositoryFormat.ALPINE);
    when(cyclic.online()).thenReturn(true);
    when(cyclic.isGroup()).thenReturn(true);
    when(cyclic.members()).thenReturn(List.of(cyclic));
    assertThrows(IllegalStateException.class, () -> service.memberFingerprint(cyclic, NAMESPACE));
  }

  @Test
  void restoresMigrationPackagesWithoutPublishingAndValidatesTheSuppliedPath() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    var parser = new com.github.klboke.kkrepo.protocol.alpine.AlpinePathParser();
    try (AlpinePackageInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(fixture.bytes()), "demo-1.0-r0.apk")) {
      AlpineService.PublishedPackage restored = service.restoreHostedPackageForMigration(
          hosted, parser.parse(PACKAGE_PATH), inspected, "migration", "127.0.0.1");
      assertEquals(PACKAGE_PATH, restored.path());
      verify(registry).savePackage(any());
      verify(published, never()).published(any());

      assertThrows(IllegalArgumentException.class, () -> service.restoreHostedPackageForMigration(
          hosted, null, inspected, "migration", null));
      assertThrows(IllegalArgumentException.class, () -> service.restoreHostedPackageForMigration(
          hosted, parser.parse(NAMESPACE + "/APKINDEX.tar.gz"), inspected, "migration", null));
      assertThrows(IllegalArgumentException.class, () -> service.restoreHostedPackageForMigration(
          hosted, parser.parse(NAMESPACE + "/other-1.0-r0.apk"), inspected, "migration", null));
    }
  }

  @Test
  void rejectsCoordinatesBoundToAnotherPathAndPathsBoundToAnotherCoordinate() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String sha256 = AlpineTestPackage.sha256(fixture.bytes());
    AlpineRegistryDao.PackageRecord moved = packageRecord(
        50L, hosted.id(), NAMESPACE + "/moved.apk", sha256, 10L, 20L,
        AlpineRegistryDao.SOURCE_HOSTED);
    when(registry.findPackage(
        hosted.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.of(moved));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        hosted, "v3.20", "main", "x86_64", "demo-1.0-r0.apk",
        new ByteArrayInputStream(fixture.bytes()), "alice", null));

    when(registry.findPackage(
        hosted.id(), NAMESPACE, "main", "demo", "1.0-r0", "x86_64"))
        .thenReturn(Optional.empty());
    when(registry.findPackageByPath(hosted.id(), PACKAGE_PATH)).thenReturn(Optional.of(moved));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        hosted, "v3.20", "main", "x86_64", "demo-1.0-r0.apk",
        new ByteArrayInputStream(fixture.bytes()), "alice", null));
  }

  @Test
  void deleteFailsWhenProjectionIsMissingOrConcurrentDeletionWins() {
    when(registry.findPackageByPath(hosted.id(), PACKAGE_PATH)).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.delete(hosted, PACKAGE_PATH, "manual", false));

    AlpineRegistryDao.PackageRecord row = packageRecord(
        50L, hosted.id(), PACKAGE_PATH, "a".repeat(64), 10L, 20L,
        AlpineRegistryDao.SOURCE_HOSTED);
    when(registry.findPackageByPath(hosted.id(), PACKAGE_PATH)).thenReturn(Optional.of(row));
    when(registry.deletePackage(
        eq(hosted.id()), eq(NAMESPACE), eq("main"), eq("demo"), eq("1.0-r0"),
        eq("x86_64"), eq("manual"), any(Instant.class))).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.delete(hosted, PACKAGE_PATH, "manual", false));
  }

  @Test
  void resignedProxyRequiresAProjectedPackageBeforeServing() {
    RepositoryRuntime proxyRuntime = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    when(repositorySettings.get(proxyRuntime)).thenReturn(settings);
    when(projection.refreshDue(eq(proxyRuntime), eq(settings), eq(NAMESPACE), any()))
        .thenReturn(false);
    when(registry.findPackageByPath(proxyRuntime.id(), PACKAGE_PATH)).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(proxyRuntime, PACKAGE_PATH, false));
  }

  @Test
  void groupServesIndexesAndProxyBindingsAndFailsClosedForMissingBindings() {
    RepositoryRuntime proxyMember = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, "ALLOW", List.of(proxyMember));
    when(repositorySettings.get(proxyMember)).thenReturn(settings);
    when(registry.findSuite(proxyMember.id(), NAMESPACE))
        .thenReturn(Optional.of(suite(proxyMember.id(), NAMESPACE, 4L, 4L)));
    String fingerprint = AlpineService.fingerprint(Map.of(proxyMember.id(), 4L));
    AlpineRegistryDao.Snapshot snapshot = snapshot(group.id(), 2L, Map.of(
        "@members", fingerprint, NAMESPACE + "/APKINDEX.tar.gz", ".alpine/group"));
    when(published.find(group.id(), NAMESPACE)).thenReturn(Optional.of(snapshot));

    assertEquals(200, service.get(group, NAMESPACE + "/APKINDEX.tar.gz", true).status());
    verify(assets).serve(group, ".alpine/group", true);

    when(registry.findGroupBinding(group.id(), NAMESPACE, 2L, PACKAGE_PATH))
        .thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, PACKAGE_PATH, false));

    AlpineRegistryDao.GroupBinding binding = new AlpineRegistryDao.GroupBinding(
        null, group.id(), NAMESPACE, 2L, PACKAGE_PATH, proxyMember.id(), 4L, PACKAGE_PATH,
        "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "a".repeat(64), 100L, Instant.EPOCH);
    when(registry.findGroupBinding(group.id(), NAMESPACE, 2L, PACKAGE_PATH))
        .thenReturn(Optional.of(binding));
    when(runtimes.resolveById(proxyMember.id())).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, PACKAGE_PATH, false));

    when(runtimes.resolveById(proxyMember.id())).thenReturn(Optional.of(proxyMember));
    when(proxy.getPinnedAssetFromUrlUnindexed(
        eq(proxyMember), eq(PACKAGE_PATH), anyString(), eq(false)))
        .thenReturn(MavenResponse.noBody(200));
    MavenResponse response = service.get(group, PACKAGE_PATH, false);
    assertEquals(proxyMember.name(), response.headers().get("X-kkRepo-Source-Repository"));
  }

  @Test
  void retriesPublicationCasAndDistinguishesBackgroundFromSynchronousCallers() {
    AlpineRegistryDao.SuiteState dirty = suite(hosted.id(), NAMESPACE, 2L, 1L);
    when(registry.findSuite(hosted.id(), NAMESPACE)).thenReturn(Optional.of(dirty));
    when(published.find(hosted.id(), NAMESPACE)).thenReturn(Optional.empty());
    AlpineIndexBuilder.BuiltSnapshot built = new AlpineIndexBuilder.BuiltSnapshot(
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/2/index"), "a".repeat(64), 100L,
        1, Instant.EPOCH);
    when(indexBuilder.build(hosted, settings, dirty, material())).thenReturn(built);
    when(registry.publishSnapshot(any(), any(), anyLong())).thenReturn(false);
    when(lease.owner()).thenReturn("owner");
    when(lease.fencingToken()).thenReturn(7L);

    assertFalse(service.publishPendingIfAvailable(hosted, NAMESPACE));
    verify(registry, times(4)).publishSnapshot(any(), eq("owner"), eq(7L));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.rebuild(hosted, NAMESPACE));
  }

  @Test
  void groupPublicationDetectsCyclesWhileCollectingPackages() {
    long cyclicId = 9L;
    RepositoryRuntime cyclic = mock(RepositoryRuntime.class);
    when(cyclic.id()).thenReturn(cyclicId);
    when(cyclic.format()).thenReturn(RepositoryFormat.ALPINE);
    when(cyclic.type()).thenReturn(RepositoryType.GROUP);
    when(cyclic.isGroup()).thenReturn(true);
    when(cyclic.online()).thenReturn(true);
    when(cyclic.members()).thenReturn(List.of(cyclic));
    when(registry.findSuite(cyclicId, NAMESPACE))
        .thenReturn(Optional.of(suite(cyclicId, NAMESPACE, 2L, 1L)));
    when(published.find(cyclicId, NAMESPACE)).thenReturn(Optional.empty());
    when(repositorySettings.get(cyclic)).thenReturn(settings);

    assertThrows(IllegalStateException.class,
        () -> service.publishPendingIfAvailable(cyclic, NAMESPACE));
  }

  @Test
  void coldHostedReadCreatesTheSuiteAndWaitsForItsFirstPublishedSnapshot() {
    AlpineRegistryDao.SuiteState clean = suite(hosted.id(), NAMESPACE, 1L, 1L);
    AlpineRegistryDao.Snapshot ready = snapshot(hosted.id(), 1L,
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/ready"));
    AtomicInteger suiteReads = new AtomicInteger();
    when(registry.findSuite(hosted.id(), NAMESPACE)).thenAnswer(ignored ->
        suiteReads.incrementAndGet() <= 2 ? Optional.empty() : Optional.of(clean));
    when(registry.ensureSuite(eq(hosted.id()), eq(NAMESPACE), any())).thenReturn(clean);
    AtomicInteger snapshotReads = new AtomicInteger();
    when(published.find(hosted.id(), NAMESPACE)).thenAnswer(ignored ->
        snapshotReads.incrementAndGet() >= 3 ? Optional.of(ready) : Optional.empty());

    MavenResponse response = service.get(hosted, NAMESPACE + "/APKINDEX.tar.gz", false);

    assertEquals(200, response.status());
    verify(registry).markSuiteDirty(eq(hosted.id()), eq(NAMESPACE), any());
    verify(assets).serve(hosted, ".alpine/ready", false);
  }

  @Test
  void coldGroupReadMarksMembershipDirtyBeforeUsingThePublishedGeneration() {
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, "ALLOW", List.of());
    AlpineRegistryDao.SuiteState clean = suite(group.id(), NAMESPACE, 1L, 1L);
    AlpineRegistryDao.Snapshot ready = snapshot(group.id(), 1L,
        Map.of(NAMESPACE + "/APKINDEX.tar.gz", ".alpine/group-ready"));
    when(registry.findSuite(group.id(), NAMESPACE)).thenReturn(Optional.of(clean));
    AtomicInteger snapshotReads = new AtomicInteger();
    when(published.find(group.id(), NAMESPACE)).thenAnswer(ignored ->
        snapshotReads.incrementAndGet() >= 2 ? Optional.of(ready) : Optional.empty());

    MavenResponse response = service.get(group, NAMESPACE + "/APKINDEX.tar.gz", false);

    assertEquals(200, response.status());
    verify(registry).ensureSuite(eq(group.id()), eq(NAMESPACE), any());
    verify(registry).markSuiteDirty(eq(group.id()), eq(NAMESPACE), any());
    verify(assets).serve(group, ".alpine/group-ready", false);
  }

  private static AlpineRepositorySettings.Settings settings(boolean resign, boolean stale) {
    return new AlpineRepositorySettings.Settings(
        List.of(), List.of(), List.of(), resign, true, stale, "key.rsa.pub", "RSA", "test",
        List.of());
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, String writePolicy, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "alpine-" + id, RepositoryFormat.ALPINE, type,
        "alpine-" + type.name().toLowerCase(), true, 1L, writePolicy, null, null, true,
        type == RepositoryType.PROXY ? "https://example.invalid/alpine/" : null,
        60, 60, true, null, members);
  }

  private static AlpineRegistryDao.SuiteState suite(
      long repositoryId, String namespace, long desired, long published) {
    return new AlpineRegistryDao.SuiteState(
        repositoryId, namespace, desired, Instant.EPOCH, published, 1, Instant.EPOCH,
        null, null, Instant.EPOCH);
  }

  private static AlpineRegistryDao.Snapshot snapshot(
      long repositoryId, long revision, Map<String, String> manifest) {
    return new AlpineRegistryDao.Snapshot(
        repositoryId, NAMESPACE, revision, 1, manifest, "a".repeat(64), Instant.EPOCH);
  }

  private static AlpineSigningService.SigningMaterial material() {
    return new AlpineSigningService.SigningMaterial(
        1, "key.rsa.pub", "fingerprint", "PUBLIC", null, null, AlpineSignature.Type.RSA);
  }

  private static AssetRecord asset(
      Long id, Long blobId, Long componentId, String path, RepositoryFormat format,
      long repositoryId) {
    return new AssetRecord(
        id, repositoryId, componentId, blobId, format, path, new byte[32],
        path.substring(path.lastIndexOf('/') + 1), "package", AlpineMediaTypes.APK_PACKAGE,
        100L, null, Instant.EPOCH, Map.of());
  }

  private static AlpineRegistryDao.PackageRecord packageRecord(
      Long id,
      long repositoryId,
      String path,
      String sha256,
      Long assetId,
      Long componentId,
      String source) {
    return new AlpineRegistryDao.PackageRecord(
        id, repositoryId, NAMESPACE, "main", "x86_64", "demo", "1.0-r0", "x86_64",
        "demo-1.0-r0.apk", path,
        Map.of("P", "demo", "V", "1.0-r0", "A", "x86_64", "I", "7"),
        "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "c".repeat(64), sha256, 100L,
        assetId, componentId, source, 1L, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AlpineRegistryDao.PackageRecord withId(
      AlpineRegistryDao.PackageRecord row, long id) {
    return new AlpineRegistryDao.PackageRecord(
        id, row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.packageArchitecture(), row.filename(), row.path(),
        row.controlFields(), row.identity(), row.dataSha256(), row.sha256(), row.size(),
        row.assetId(), row.componentId(), row.sourceKind(), row.revision(), row.indexedAt(),
        row.createdAt(), row.updatedAt());
  }
}
