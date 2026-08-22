package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RServiceTest {
  private static final String PATH = "src/contrib/demo_1.0.0.tar.gz";

  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final RPublishedSnapshotCache published = mock(RPublishedSnapshotCache.class);
  private final RAssetSupport assets = mock(RAssetSupport.class);
  private final RIndexBuilder indexBuilder = mock(RIndexBuilder.class);
  private final RLeaseManager leases = mock(RLeaseManager.class);
  private final RLeaseManager.Lease lease = mock(RLeaseManager.Lease.class);
  private final RProxyProjectionService proxy = mock(RProxyProjectionService.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final RepositoryRuntime hosted = runtime(1L, RepositoryType.HOSTED, "ALLOW", List.of());
  private RService service;

  @BeforeEach
  void setUp() {
    service = new RService(
        registry,
        published,
        new RSourcePackageInspector(),
        new RComponentFactory(),
        assets,
        indexBuilder,
        leases,
        proxy,
        runtimes);
    when(leases.acquire(anyString())).thenReturn(lease);
    when(leases.tryAcquire(anyString())).thenReturn(Optional.of(lease));
    when(assets.storePackage(any(), anyString(), anyString(), any(), any(), any(), any(), any()))
        .thenReturn(asset(10L, 20L, 30L, PATH, 1L));
    when(assets.serve(any(), anyString(), anyBoolean())).thenReturn(MavenResponse.noBody(200));
    RRegistryDao.SuiteState clean = suite(1L, 1L, 1L);
    when(registry.findSuite(anyLong(), anyString())).thenReturn(Optional.of(clean));
    when(registry.ensureSuite(anyLong(), anyString(), any())).thenReturn(clean);
    when(published.find(anyLong(), anyString())).thenReturn(Optional.of(
        snapshot(1L, 1L, Map.of("src/contrib/PACKAGES.gz", ".r/index"))));
    when(registry.savePackage(any())).thenAnswer(invocation -> withId(
        invocation.getArgument(0), 50L, 1L));
  }

  @Test
  void publishesSourcePackageIdempotentlyAndRejectsCoordinateDrift() throws Exception {
    byte[] fixture = RTestPackage.source("demo", "1.0.0");

    RService.PublishedPackage first = service.publish(
        hosted, "demo_1.0.0.tar.gz", new ByteArrayInputStream(fixture),
        "alice", "127.0.0.1");

    assertEquals(PATH, first.path());
    assertEquals("demo", first.packageName());
    assertEquals("1.0.0", first.version());
    assertEquals(32, first.md5().length());
    assertEquals(64, first.sha256().length());
    ArgumentCaptor<RRegistryDao.PackageRecord> saved =
        ArgumentCaptor.forClass(RRegistryDao.PackageRecord.class);
    verify(registry).savePackage(saved.capture());
    assertEquals("HOSTED", saved.getValue().sourceKind());
    assertEquals("demo", saved.getValue().controlFields().get("Package"));
    verify(registry).replacePackageRelations(eq(hosted.id()), eq(50L), any());
    verify(assets).storePackage(
        eq(hosted), eq(PATH), eq("src/contrib/demo/1.0.0/demo_1.0.0.tar.gz"), any(), any(),
        eq("alice"), eq("127.0.0.1"), any());

    RRegistryDao.PackageRecord existing = withId(saved.getValue(), 50L, 1L);
    when(registry.findPackage(
        hosted.id(), "src/contrib", "source", "demo", "1.0.0", "source"))
        .thenReturn(Optional.of(existing));
    RService.PublishedPackage duplicate = service.publish(
        hosted, "demo_1.0.0.tar.gz", new ByteArrayInputStream(fixture), "alice", null);
    assertEquals(first.sha256(), duplicate.sha256());
    verify(assets, times(1)).storePackage(
        any(), anyString(), anyString(), any(), any(), any(), any(), any());

    RRegistryDao.PackageRecord conflict = new RRegistryDao.PackageRecord(
        existing.id(), existing.repositoryId(), existing.distribution(), existing.component(),
        existing.architecture(), existing.packageName(), existing.version(),
        existing.versionOrderKey(), existing.packageArchitecture(), existing.filename(),
        existing.path(), existing.controlFields(), existing.identity(), existing.dataSha256(),
        "0".repeat(64), existing.size(), existing.assetId(), existing.componentId(),
        existing.sourceKind(), existing.revision(), existing.indexedAt(), existing.createdAt(),
        existing.updatedAt());
    when(registry.findPackage(
        hosted.id(), "src/contrib", "source", "demo", "1.0.0", "source"))
        .thenReturn(Optional.of(conflict));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        hosted, "demo_1.0.0.tar.gz", new ByteArrayInputStream(fixture), "alice", null));

    RRegistryDao.PackageRecord moved = new RRegistryDao.PackageRecord(
        existing.id(), existing.repositoryId(), existing.distribution(), existing.component(),
        existing.architecture(), existing.packageName(), existing.version(),
        existing.versionOrderKey(), existing.packageArchitecture(), existing.filename(),
        "src/contrib/moved_1.0.0.tar.gz", existing.controlFields(), existing.identity(),
        existing.dataSha256(), existing.sha256(), existing.size(), existing.assetId(),
        existing.componentId(), existing.sourceKind(), existing.revision(), existing.indexedAt(),
        existing.createdAt(), existing.updatedAt());
    when(registry.findPackage(
        hosted.id(), "src/contrib", "source", "demo", "1.0.0", "source"))
        .thenReturn(Optional.of(moved));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        hosted, "demo_1.0.0.tar.gz", new ByteArrayInputStream(fixture), "alice", null));

    when(registry.findPackage(
        hosted.id(), "src/contrib", "source", "demo", "1.0.0", "source"))
        .thenReturn(Optional.empty());
    when(registry.findPackageByPath(hosted.id(), PATH)).thenReturn(Optional.of(existing));
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        hosted, "demo_1.0.0.tar.gz", new ByteArrayInputStream(fixture), "alice", null));

    RepositoryRuntime deny = runtime(9L, RepositoryType.HOSTED, "DENY", List.of());
    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.publish(
        deny, "demo_1.0.0.tar.gz", new ByteArrayInputStream(fixture), "alice", null));
  }

  @Test
  void delegatesStandaloneProxyReads() {
    RepositoryRuntime proxyRuntime = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    var path = new RPathParser().parse(PATH);
    when(proxy.get(proxyRuntime, path, true)).thenReturn(MavenResponse.noBody(206));

    assertEquals(206, service.get(proxyRuntime, PATH, true).status());

    verify(proxy).get(proxyRuntime, path, true);
  }

  @Test
  void restrictsHostedWritesAndServesPublishedMetadataAndPackageBytes() throws Exception {
    byte[] fixture = RTestPackage.source("demo", "1.0.0");
    MavenResponse put = service.put(
        hosted, PATH, new ByteArrayInputStream(fixture), null, "alice", null);
    assertEquals(200, put.status());
    assertEquals(PATH, put.headers().get("Location"));

    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.put(
        hosted, "src/contrib/PACKAGES.gz", new ByteArrayInputStream(fixture), null, null, null));
    assertThrows(MavenExceptions.MethodNotAllowed.class, () -> service.put(
        runtime(2L, RepositoryType.PROXY, "ALLOW", List.of()), PATH,
        new ByteArrayInputStream(fixture), null, null, null));

    assertEquals("public, max-age=31536000, immutable",
        service.get(hosted, PATH, true).headers().get("Cache-Control"));
    assertEquals("public, max-age=0, must-revalidate",
        service.get(hosted, "src/contrib/PACKAGES.gz", false)
            .headers().get("Cache-Control"));
    verify(assets).serve(hosted, ".r/index", false);
  }

  @Test
  void deletesIndexFirstAndRoutesCleanupThroughTheSameMutation() {
    RRegistryDao.PackageRecord row = row(50L, hosted.id(), PATH, 10L, 20L, 1L);
    when(registry.findPackageByPath(hosted.id(), PATH)).thenReturn(Optional.of(row));
    when(registry.deletePackage(
        eq(hosted.id()), eq("src/contrib"), eq("source"), eq("demo"), eq("1.0.0"),
        eq("source"), eq("manual"), any(Instant.class))).thenReturn(Optional.of(row));

    assertEquals(204, service.delete(hosted, PATH, "manual", true).status());
    verify(assets).retirePackageProjection(10L);
    verify(assets, never()).delete(hosted, PATH);

    AssetRecord valid = asset(10L, 20L, 30L, PATH, hosted.id());
    when(assets.listAssetsByComponent(30L)).thenReturn(List.of(valid));
    when(registry.deletePackage(
        eq(hosted.id()), eq("src/contrib"), eq("source"), eq("demo"), eq("1.0.0"),
        eq("source"), eq("cleanup"), any(Instant.class))).thenReturn(Optional.of(row));
    assertEquals(List.of(1),
        service.deleteComponentsForCleanup(hosted, List.of(30L), "cleanup"));
  }

  @Test
  void publishesDirtySnapshotWithFencingAndRetainsFailureForWorkerTakeover() {
    RRegistryDao.SuiteState dirty = suite(hosted.id(), 2L, 1L);
    when(registry.findSuite(hosted.id(), "src/contrib")).thenReturn(Optional.of(dirty));
    when(published.find(hosted.id(), "src/contrib")).thenReturn(Optional.empty());
    RIndexBuilder.BuiltSnapshot built = new RIndexBuilder.BuiltSnapshot(
        Map.of("src/contrib/PACKAGES.gz", ".r/2/index"), "a".repeat(64), 100L, 1,
        Instant.EPOCH);
    when(indexBuilder.build(hosted, dirty)).thenReturn(built);
    when(registry.publishSnapshot(any(), any(), anyLong())).thenReturn(true);
    when(lease.owner()).thenReturn("owner");
    when(lease.fencingToken()).thenReturn(7L);

    assertTrue(service.publishPendingIfAvailable(hosted, "src/contrib"));
    ArgumentCaptor<RRegistryDao.Snapshot> captured =
        ArgumentCaptor.forClass(RRegistryDao.Snapshot.class);
    verify(registry).publishSnapshot(captured.capture(), eq("owner"), eq(7L));
    assertEquals(2L, captured.getValue().revision());
    verify(published).published(captured.getValue());

    when(indexBuilder.build(hosted, dirty))
        .thenThrow(new IllegalStateException("build failed"));
    assertThrows(IllegalStateException.class,
        () -> service.publishPendingIfAvailable(hosted, "src/contrib"));
    verify(registry).recordBuildFailure(
        eq(hosted.id()), eq("src/contrib"), eq(2L), eq("build failed"), any());

    when(leases.tryAcquire(anyString())).thenReturn(Optional.empty());
    assertFalse(service.publishPendingIfAvailable(hosted, "src/contrib"));
  }

  @Test
  void groupPackageReadsUseSnapshotScopedMemberBinding() {
    RepositoryRuntime member = runtime(2L, RepositoryType.HOSTED, "ALLOW", List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, "ALLOW", List.of(member));
    String memberFingerprint = RService.fingerprint(new LinkedHashMap<>(Map.of(2L, 4L)));
    when(registry.findSuite(member.id(), "src/contrib"))
        .thenReturn(Optional.of(suite(member.id(), 4L, 4L)));
    when(published.find(group.id(), "src/contrib")).thenReturn(Optional.of(snapshot(
        group.id(), 9L,
        Map.of("src/contrib/PACKAGES.gz", ".r/group/index", "@members", memberFingerprint))));
    RRegistryDao.GroupBinding binding = new RRegistryDao.GroupBinding(
        1L, group.id(), "src/contrib", 9L, PATH, member.id(), 4L, PATH,
        "a".repeat(32), "b".repeat(64), 12L, Instant.EPOCH);
    when(registry.findGroupBinding(group.id(), "src/contrib", 9L, PATH))
        .thenReturn(Optional.of(binding));
    when(runtimes.resolveById(member.id())).thenReturn(Optional.of(member));

    MavenResponse response = service.get(group, PATH, true);

    assertEquals(member.name(), response.headers().get("X-kkRepo-Source-Repository"));
    verify(assets).serve(member, PATH, true);
  }

  @Test
  void groupIndexPublishesRefreshedProxyProjectionBeforeComparingMemberFingerprint() {
    RepositoryRuntime proxyRuntime = runtime(2L, RepositoryType.PROXY, "ALLOW", List.of());
    RepositoryRuntime group = runtime(3L, RepositoryType.GROUP, "ALLOW", List.of(proxyRuntime));
    String memberFingerprint = RService.fingerprint(new LinkedHashMap<>(Map.of(2L, 4L)));
    RRegistryDao.SuiteState dirtyProxy = suite(proxyRuntime.id(), 4L, 3L);
    when(proxy.prepareGroupMember(eq(proxyRuntime), any(Instant.class))).thenReturn(4L);
    when(registry.findSuite(proxyRuntime.id(), "src/contrib"))
        .thenReturn(Optional.of(dirtyProxy));
    when(published.find(proxyRuntime.id(), "src/contrib")).thenReturn(
        Optional.of(snapshot(proxyRuntime.id(), 3L,
            Map.of("src/contrib/PACKAGES.gz", ".r/proxy/3/index"))),
        Optional.of(snapshot(proxyRuntime.id(), 4L,
            Map.of("src/contrib/PACKAGES.gz", ".r/proxy/4/index"))));
    when(indexBuilder.build(proxyRuntime, dirtyProxy)).thenReturn(new RIndexBuilder.BuiltSnapshot(
        Map.of("src/contrib/PACKAGES.gz", ".r/proxy/4/index"),
        "d".repeat(64), 100L, 1, Instant.EPOCH));
    when(registry.publishSnapshot(any(), any(), anyLong())).thenReturn(true);
    when(lease.owner()).thenReturn("owner");
    when(lease.fencingToken()).thenReturn(7L);
    when(published.find(group.id(), "src/contrib")).thenReturn(Optional.of(snapshot(
        group.id(), 9L,
        Map.of("src/contrib/PACKAGES.gz", ".r/group/index", "@members", memberFingerprint))));

    MavenResponse response = service.get(group, "src/contrib/PACKAGES.gz", false);

    assertEquals(200, response.status());
    verify(proxy).prepareGroupMember(eq(proxyRuntime), any(Instant.class));
    verify(registry).publishSnapshot(any(), eq("owner"), eq(7L));
    verify(assets).serve(group, ".r/group/index", false);
    verify(registry, never()).markSuiteDirty(
        eq(group.id()), eq("src/contrib"), any(Instant.class));
  }

  @Test
  void validatesRuntimePathNamespaceAndReportsDurableStatus() {
    RepositoryRuntime wrongFormat = new RepositoryRuntime(
        9L, "maven", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted",
        true, 1L, "ALLOW", null, null, true, null, null, null, true, null, List.of());
    RRegistryDao.SuiteState state = new RRegistryDao.SuiteState(
        hosted.id(), "src/contrib", 5L, Instant.EPOCH, 4L, 1, Instant.EPOCH,
        "last failure", Instant.EPOCH, Instant.EPOCH);
    RRegistryDao.ProxyDistribution distribution = new RRegistryDao.ProxyDistribution(
        hosted.id(), "src/contrib", "release", Map.of(), true, Instant.EPOCH, Instant.EPOCH);
    when(registry.listSuites(hosted.id())).thenReturn(List.of(state));
    when(registry.listProxyDistributions(hosted.id())).thenReturn(List.of(distribution));

    RService.Status status = service.status(hosted);

    assertEquals(5L, status.namespaces().getFirst().desiredRevision());
    assertEquals("last failure", status.namespaces().getFirst().lastError());
    assertEquals("release", status.proxyNamespaces().getFirst().indexIdentity());
    assertTrue(status.proxyNamespaces().getFirst().projectionVerified());
    service.rebuild(hosted);
    verify(registry).markSuiteDirty(eq(hosted.id()), eq("src/contrib"), any(Instant.class));

    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(null, PATH, false));
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.get(wrongFormat, PATH, false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "", false));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(hosted, "src/contrib/PACKAGES", false));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> service.publishPendingIfAvailable(hosted, "bin/windows"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> service.publish(hosted, "PACKAGES.gz", new ByteArrayInputStream(new byte[0]),
            "alice", null));
  }

  @Test
  void rejectsDeletePolicyMissingRowsAndIgnoresForeignCleanupAssets() {
    RepositoryRuntime deny = runtime(4L, RepositoryType.HOSTED, "DENY", List.of());
    assertThrows(MavenExceptions.MethodNotAllowed.class,
        () -> service.delete(hosted, "src/contrib/PACKAGES.gz", "manual", true));
    assertThrows(MavenExceptions.WritePolicyDenied.class,
        () -> service.delete(deny, PATH, "manual", true));
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.delete(hosted, PATH, "manual", false));
    assertEquals(List.of(), service.deleteComponentsForCleanup(hosted, null, "cleanup"));
    assertEquals(List.of(), service.deleteComponentsForCleanup(hosted, List.of(), "cleanup"));

    AssetRecord foreign = asset(11L, 21L, 31L, PATH, 99L);
    AssetRecord wrongFormat = new AssetRecord(
        12L, hosted.id(), 31L, 22L, RepositoryFormat.MAVEN2, PATH, new byte[32],
        "demo.jar", "jar", "application/java-archive", 12L, null, Instant.EPOCH, Map.of());
    AssetRecord missingProjection = asset(13L, 23L, 31L, "src/contrib/missing_1.0.tar.gz",
        hosted.id());
    when(assets.listAssetsByComponent(31L))
        .thenReturn(List.of(foreign, wrongFormat, missingProjection));

    assertEquals(List.of(0, 0, 0, 0), service.deleteComponentsForCleanup(
        hosted, java.util.Arrays.asList(null, 0L, -1L, 31L), "cleanup"));
  }

  @Test
  void restoresVerifiedMigrationPackagesAndRejectsPathDrift() throws Exception {
    byte[] fixture = RTestPackage.source("demo", "1.0.0");
    RPathParser parser = new RPathParser();
    try (RSourcePackageInspector.InspectedPackage inspected =
        new RSourcePackageInspector().inspect(new ByteArrayInputStream(fixture),
            "demo_1.0.0.tar.gz")) {
      assertThrows(IllegalArgumentException.class,
          () -> service.restoreHostedPackageForMigration(
              hosted, null, inspected, "migration", null));
      assertThrows(IllegalArgumentException.class,
          () -> service.restoreHostedPackageForMigration(
              hosted, parser.parse("src/contrib/other_1.0.0.tar.gz"), inspected,
              "migration", null));

      RService.PublishedPackage restored = service.restoreHostedPackageForMigration(
          hosted, parser.parse(PATH), inspected, "migration", "127.0.0.1");
      assertEquals(PATH, restored.path());
      verify(registry).savePackage(any());
    }
  }

  @Test
  void publishesLatestGroupPackageAndPersistsSnapshotScopedBinding() {
    RepositoryRuntime first = runtime(2L, RepositoryType.HOSTED, "ALLOW", List.of());
    RepositoryRuntime second = runtime(3L, RepositoryType.HOSTED, "ALLOW", List.of());
    RepositoryRuntime group = runtime(4L, RepositoryType.GROUP, "ALLOW", List.of(first, second));
    RRegistryDao.SuiteState dirty = suite(group.id(), 9L, 8L);
    RRegistryDao.PackageRecord old = groupRow(
        20L, first.id(), "demo", "1.0.0", RRegistryDao.SOURCE_HOSTED, "a".repeat(32));
    RRegistryDao.PackageRecord latest = groupRow(
        30L, second.id(), "demo", "2.0.0", RRegistryDao.SOURCE_HOSTED, "b".repeat(32));
    when(registry.findSuite(group.id(), "src/contrib")).thenReturn(Optional.of(dirty));
    when(published.find(group.id(), "src/contrib")).thenReturn(Optional.empty());
    when(published.find(first.id(), "src/contrib"))
        .thenReturn(Optional.of(snapshot(first.id(), 4L, Map.of())));
    when(published.find(second.id(), "src/contrib"))
        .thenReturn(Optional.of(snapshot(second.id(), 6L, Map.of())));
    when(registry.listPackagePage(
        eq(first.id()), eq("src/contrib"), anyString(), anyLong(), anyInt()))
        .thenReturn(List.of(old));
    when(registry.listPackagePage(
        eq(second.id()), eq("src/contrib"), anyString(), anyLong(), anyInt()))
        .thenReturn(List.of(latest));
    ArrayList<RRegistryDao.PackageRecord> visited = new ArrayList<>();
    RIndexBuilder.BuiltSnapshot built = new RIndexBuilder.BuiltSnapshot(
        Map.of("src/contrib/PACKAGES.gz", ".r/group/9/index"), "d".repeat(64),
        100L, 1, Instant.EPOCH);
    when(indexBuilder.build(eq(group), eq(dirty), any(RIndexBuilder.PackageSource.class)))
        .thenAnswer(invocation -> {
          RIndexBuilder.PackageSource source = invocation.getArgument(2);
          source.visit(visited::add);
          return built;
        });
    when(lease.owner()).thenReturn("owner");
    when(lease.fencingToken()).thenReturn(7L);
    when(registry.publishGroupSnapshot(any(), eq("owner"), eq(7L))).thenReturn(true);

    assertTrue(service.publishPendingIfAvailable(group, "src/contrib"));

    assertEquals(List.of(latest), visited);
    verify(registry).beginGroupSnapshot(group.id(), "src/contrib", 9L, 7L);
    verify(registry).appendGroupBindings(eq(7L), org.mockito.ArgumentMatchers.argThat(
        bindings -> bindings.size() == 1
            && bindings.getFirst().memberRepositoryId() == second.id()
            && bindings.getFirst().memberSnapshotRevision() == 6L));
    ArgumentCaptor<RRegistryDao.Snapshot> captured =
        ArgumentCaptor.forClass(RRegistryDao.Snapshot.class);
    verify(registry).publishGroupSnapshot(captured.capture(), eq("owner"), eq(7L));
    LinkedHashMap<Long, Long> memberRevisions = new LinkedHashMap<>();
    memberRevisions.put(2L, 4L);
    memberRevisions.put(3L, 6L);
    assertEquals(RService.fingerprint(memberRevisions),
        captured.getValue().manifest().get("@members"));
    verify(published).published(captured.getValue());
    verify(registry, never()).discardGroupSnapshot(anyLong(), anyString(), anyLong(), anyLong());
  }

  @Test
  void rejectsGroupProxyRowsWithoutMd5AndDiscardsPartialSnapshot() {
    RepositoryRuntime member = runtime(5L, RepositoryType.PROXY, "ALLOW", List.of());
    RepositoryRuntime group = runtime(6L, RepositoryType.GROUP, "ALLOW", List.of(member));
    RRegistryDao.SuiteState dirty = suite(group.id(), 3L, 2L);
    RRegistryDao.PackageRecord invalid = groupRow(
        50L, member.id(), "broken", "1.0.0", RRegistryDao.SOURCE_PROXY, "missing");
    when(registry.findSuite(group.id(), "src/contrib")).thenReturn(Optional.of(dirty));
    when(published.find(group.id(), "src/contrib")).thenReturn(Optional.empty());
    when(proxy.prepareGroupMember(eq(member), any(Instant.class))).thenReturn(4L);
    when(published.find(member.id(), "src/contrib"))
        .thenReturn(Optional.of(snapshot(member.id(), 4L, Map.of())));
    when(registry.listPackagePage(
        eq(member.id()), eq("src/contrib"), anyString(), anyLong(), anyInt()))
        .thenReturn(List.of(invalid));
    when(indexBuilder.build(eq(group), eq(dirty), any(RIndexBuilder.PackageSource.class)))
        .thenAnswer(invocation -> {
          RIndexBuilder.PackageSource source = invocation.getArgument(2);
          source.visit(ignored -> { });
          throw new AssertionError("invalid proxy row should stop the package source");
        });
    when(lease.fencingToken()).thenReturn(11L);

    assertThrows(MavenExceptions.BadUpstreamException.class,
        () -> service.publishPendingIfAvailable(group, "src/contrib"));
    verify(registry).discardGroupSnapshot(group.id(), "src/contrib", 3L, 11L);
    verify(registry).recordBuildFailure(
        eq(group.id()), eq("src/contrib"), eq(3L), anyString(), any(Instant.class));
  }

  @Test
  void detectsGroupCyclesAndFiltersOfflineOrForeignMembers() {
    ArrayList<RepositoryRuntime> cyclicMembers = new ArrayList<>();
    RepositoryRuntime cyclic = runtime(20L, RepositoryType.GROUP, "ALLOW", cyclicMembers);
    cyclicMembers.add(cyclic);
    assertThrows(IllegalStateException.class,
        () -> service.memberFingerprint(cyclic, "src/contrib"));

    RepositoryRuntime offline = new RepositoryRuntime(
        21L, "offline", RepositoryFormat.R, RepositoryType.HOSTED, "r-hosted", false,
        1L, "ALLOW", null, null, true, null, null, null, true, null, List.of());
    RepositoryRuntime foreign = new RepositoryRuntime(
        22L, "maven", RepositoryFormat.MAVEN2, RepositoryType.HOSTED, "maven2-hosted", true,
        1L, "ALLOW", null, null, true, null, null, null, true, null, List.of());
    RepositoryRuntime filtered = runtime(
        23L, RepositoryType.GROUP, "ALLOW", List.of(offline, foreign));
    assertEquals(RService.fingerprint(Map.of()),
        service.memberFingerprint(filtered, "src/contrib"));
  }

  @Test
  void servesProxyBoundGroupPackagesAndFailsWhenBindingSourceDisappears() {
    RepositoryRuntime member = runtime(31L, RepositoryType.PROXY, "ALLOW", List.of());
    RepositoryRuntime group = runtime(32L, RepositoryType.GROUP, "ALLOW", List.of(member));
    RRegistryDao.Snapshot groupSnapshot = snapshot(group.id(), 8L, Map.of());
    RRegistryDao.GroupBinding binding = new RRegistryDao.GroupBinding(
        1L, group.id(), "src/contrib", 8L, PATH, member.id(), 4L, PATH,
        "a".repeat(32), "b".repeat(64), 12L, Instant.EPOCH);
    when(published.find(group.id(), "src/contrib")).thenReturn(Optional.of(groupSnapshot));
    when(registry.findGroupBinding(group.id(), "src/contrib", 8L, PATH))
        .thenReturn(Optional.of(binding));
    when(runtimes.resolveById(member.id())).thenReturn(Optional.of(member));
    when(proxy.getBoundGroupPackage(member, binding, false)).thenReturn(MavenResponse.noBody(206));

    MavenResponse response = service.get(group, PATH, false);

    assertEquals(206, response.status());
    assertEquals(member.name(), response.headers().get("X-kkRepo-Source-Repository"));
    when(runtimes.resolveById(member.id())).thenReturn(Optional.empty());
    assertThrows(MavenExceptions.MavenNotFoundException.class,
        () -> service.get(group, PATH, false));
  }

  @Test
  void failsSynchronousPublicationAfterRepeatedFencingLoss() {
    RRegistryDao.SuiteState dirty = suite(hosted.id(), 2L, 1L);
    when(registry.findSuite(hosted.id(), "src/contrib")).thenReturn(Optional.of(dirty));
    when(published.find(hosted.id(), "src/contrib")).thenReturn(Optional.empty());
    when(indexBuilder.build(hosted, dirty)).thenReturn(new RIndexBuilder.BuiltSnapshot(
        Map.of("src/contrib/PACKAGES.gz", ".r/2/index"), "a".repeat(64),
        100L, 1, Instant.EPOCH));
    when(registry.publishSnapshot(any(), any(), anyLong())).thenReturn(false);

    assertThrows(MavenExceptions.WritePolicyDenied.class, () -> service.rebuild(hosted));
    verify(registry, times(4)).publishSnapshot(any(), any(), anyLong());
  }

  private static RRegistryDao.PackageRecord withId(
      RRegistryDao.PackageRecord row, long id, long revision) {
    return new RRegistryDao.PackageRecord(
        id, row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.versionOrderKey(), row.packageArchitecture(),
        row.filename(), row.path(), row.controlFields(), row.identity(), row.dataSha256(),
        row.sha256(), row.size(), row.assetId(), row.componentId(), row.sourceKind(), revision,
        row.indexedAt(), row.createdAt(), row.updatedAt());
  }

  private static RRegistryDao.PackageRecord row(
      long id, long repositoryId, String path, Long assetId, Long componentId, long revision) {
    return new RRegistryDao.PackageRecord(
        id, repositoryId, "src/contrib", "source", "source", "demo", "1.0.0",
        "r1|0001:1|".getBytes(StandardCharsets.US_ASCII), "source",
        "demo_1.0.0.tar.gz", path, Map.of("Package", "demo", "Version", "1.0.0"),
        "a".repeat(32), "b".repeat(64), "b".repeat(64), 12L, assetId, componentId,
        RRegistryDao.SOURCE_HOSTED, revision, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static RRegistryDao.PackageRecord groupRow(
      long id,
      long repositoryId,
      String packageName,
      String version,
      String sourceKind,
      String identity) {
    String filename = packageName + "_" + version + ".tar.gz";
    return new RRegistryDao.PackageRecord(
        id, repositoryId, "src/contrib", "source", "source", packageName, version,
        ("r1|" + version).getBytes(StandardCharsets.US_ASCII), "source", filename,
        "src/contrib/" + filename, Map.of("Package", packageName, "Version", version),
        identity, "b".repeat(64), "b".repeat(64), 12L, id + 100, id + 200,
        sourceKind, 1L, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
  }

  private static AssetRecord asset(
      long id, Long blobId, Long componentId, String path, long repositoryId) {
    return new AssetRecord(
        id, repositoryId, componentId, blobId, RepositoryFormat.R, path, new byte[32],
        "demo_1.0.0.tar.gz", "r", "application/x-gzip", 12L, null, Instant.EPOCH,
        Map.of("rInputSchema", "r-source-package-v1"));
  }

  private static RRegistryDao.SuiteState suite(
      long repositoryId, long desired, long publishedRevision) {
    return new RRegistryDao.SuiteState(
        repositoryId, "src/contrib", desired, Instant.EPOCH, publishedRevision, 1,
        Instant.EPOCH, null, null, Instant.EPOCH);
  }

  private static RRegistryDao.Snapshot snapshot(
      long repositoryId, long revision, Map<String, String> manifest) {
    return new RRegistryDao.Snapshot(
        repositoryId, "src/contrib", revision, 1, manifest, "c".repeat(64), Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryType type, String writePolicy, List<RepositoryRuntime> members) {
    return new RepositoryRuntime(
        id, "r-" + id, RepositoryFormat.R, type, "r-" + type.name().toLowerCase(), true,
        1L, writePolicy, null, null, true,
        type == RepositoryType.PROXY ? "https://example.invalid/" : null,
        60, 60, true, null, members);
  }
}
