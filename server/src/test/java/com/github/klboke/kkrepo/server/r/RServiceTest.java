package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
        eq(hosted), eq(PATH), eq("demo/1.0.0/demo_1.0.0.tar.gz"), any(), any(),
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
