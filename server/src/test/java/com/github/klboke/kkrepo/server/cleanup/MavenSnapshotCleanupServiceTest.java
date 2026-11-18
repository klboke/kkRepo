package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.MavenSnapshotDao.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.server.maven.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MavenSnapshotCleanupServiceTest {
  static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  static final Instant OLD = NOW.minus(Duration.ofDays(30));
  final MavenSnapshotDao snapshots = mock(MavenSnapshotDao.class);
  final AssetDao assets = mock(AssetDao.class);
  final CleanupPolicyDao cleanup = mock(CleanupPolicyDao.class);
  final MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
  final BlobStorageRegistry storage = mock(BlobStorageRegistry.class);
  final CleanupUsageTrackingService tracking = mock(CleanupUsageTrackingService.class);
  final MavenSnapshotCleanupService service = new MavenSnapshotCleanupService(snapshots, assets, cleanup,
      cursors, storage, tracking, new CleanupRuntimeProperties());
  final Family family = new Family(1, 1, "com/example/demo/1.0-SNAPSHOT", "com.example", "demo", "1.0-SNAPSHOT");
  final Map<Long, AssetRecord> inventory = new LinkedHashMap<>();
  final List<Build> builds = new ArrayList<>();

  @BeforeEach void ready() {
    when(cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.READY)).thenReturn(OptionalLong.of(1));
    when(tracking.trackingStartedAt(1)).thenReturn(OLD.minus(Duration.ofDays(365)));
    when(snapshots.findFamily(1)).thenReturn(Optional.of(family));
    when(snapshots.page(eq(1L), anyLong(), anyInt())).thenAnswer(call -> builds.stream()
        .filter(b -> b.id() > (long) call.getArgument(1)).limit((int) call.getArgument(2)).toList());
    when(snapshots.findBuild(anyLong())).thenAnswer(call -> builds.stream()
        .filter(b -> b.id() == (long) call.getArgument(0)).findFirst());
    when(assets.findAssetsByIds(anyCollection())).thenAnswer(call -> selected(call.getArgument(0)));
    when(assets.findAssetsByIdsForUpdate(anyCollection())).thenAnswer(call -> selected(call.getArgument(0)));
    when(snapshots.retentionBoundary(eq(1L), anyInt())).thenAnswer(call -> builds.stream()
        .filter(b -> b.primaryCount() > 0).sorted(Comparator.comparingLong(Build::timestamp)
            .thenComparingInt(Build::buildNumber).reversed()).skip((int) call.getArgument(1) - 1).findFirst());
  }

  @Test void issue506RetainsFiveBuildsInsideOneBaseVersionRegardlessOfScanLimit() {
    for (int i = 1; i <= 20; i++) addBuild(i, 4, OLD, null);
    for (int limit : new int[] {100, 1000, 10000}) {
      var result = service.scan(repository(), rules(Map.of("retainCount", 5, "publishedOlderThanDays", 10,
          "lastDownloadedOlderThanDays", 7)), limit, NOW, null);
      assertEquals(20, result.scannedSubjects());
      assertEquals(15, result.candidates().size());
      assertEquals(60, result.candidates().stream().mapToInt(c -> c.subject().assetCount()).sum());
      assertEquals(List.of(1L, 2L, 3L, 4L), result.candidates().getFirst().subject().assetIds());
      assertEquals("MAVEN_SNAPSHOT_BUILD", result.candidates().getFirst().subject().kind());
      assertTrue(result.cursorWarning().contains("newest=5"));
    }
    verifyNoInteractions(storage);
  }

  @Test void downloadAndPublicationTimesBelongToEachBuildRatherThanTheSharedComponent() {
    addBuild(1, 2, OLD, NOW.minus(Duration.ofDays(2)));
    addBuild(2, 2, OLD, null);
    addBuild(3, 2, NOW.minus(Duration.ofDays(2)), null);
    addBuild(4, 2, NOW, NOW);
    var result = service.scan(repository(), rules(Map.of("retainCount", 1, "publishedOlderThanDays", 10,
        "lastDownloadedOlderThanDays", 7)), 100, NOW, null);
    assertEquals(List.of(2L), result.candidates().stream().map(c -> c.subject().identityId()).toList());
  }

  @Test void stopsBeforeReadingAssetsWhenIndexOrDownloadObservationIsNotReady() {
    when(cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.READY)).thenReturn(OptionalLong.empty());
    assertEquals("SNAPSHOT_INDEX_WARMING_UP", scan().safetyStatus());
    verify(snapshots, never()).page(anyLong(), anyLong(), anyInt());
    when(cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.READY)).thenReturn(OptionalLong.of(1));
    when(tracking.trackingStartedAt(1)).thenReturn(null);
    assertEquals("USAGE_TRACKING_NOT_ACTIVE", service.scan(repository(),
        rules(Map.of("retainCount", 1, "lastDownloadedOlderThanDays", 7)), 100, NOW, null).safetyStatus());
    when(tracking.trackingStartedAt(1)).thenReturn(NOW);
    assertEquals("USAGE_TRACKING_WARMING_UP", service.scan(repository(),
        rules(Map.of("retainCount", 1, "lastDownloadedOlderThanDays", 7)), 100, NOW, null).safetyStatus());
  }

  @Test void boundedPageResumesInsideOneLargeSnapshotFamily() {
    for (int i = 1; i <= 20; i++) addBuild(i, 2, OLD, null);
    var first = service.scan(repository(), rules(Map.of("retainCount", 1)), 3, NOW, null);
    assertEquals(3, first.candidates().size());
    assertTrue(first.truncated());
    assertEquals(3, first.nextCursor().subjectId());
    var second = service.scan(repository(), rules(Map.of("retainCount", 1)), 3, NOW, first.nextCursor());
    assertEquals(List.of(4L, 5L, 6L), second.candidates().stream().map(c -> c.subject().identityId()).toList());
    assertEquals("MAVEN_SNAPSHOT", second.nextCursor().phase());
  }

  @Test void boundsTotalAssetReadsAndKeepsOversizedBuildsWithAnExplanation() {
    for (int i = 1; i <= 7; i++) addBuild(i, i == 1 ? 4097 : 4096, OLD, null);
    var result = scan();
    assertEquals(4, result.candidates().size());
    assertEquals(5, result.nextCursor().subjectId());
    assertTrue(result.truncated());
    assertTrue(result.cursorWarning().contains("oversized_build=1"));
    verify(snapshots, never()).assetIds(eq(1L), anyInt());
    verify(snapshots, never()).assetIds(eq(6L), anyInt());
  }

  @Test void latestClassifierRecentMutationMissingAssetsAndUnknownMetadataAreHeld() {
    for (int i = 1; i <= 5; i++) addBuild(i, 2, OLD, null);
    when(snapshots.hasLatestVariant(1)).thenReturn(true);
    builds.set(1, new Build(2, 1, 1, builds.get(1).version(), 2, 2, 1, 2, 1, NOW));
    inventory.remove(9L); // Build 3 has changed since its projection was read.
    var result = scan();
    assertEquals(List.of(4L), result.candidates().stream().map(c -> c.subject().identityId()).toList());
    when(assets.findAssetByPath(1, family.path() + "/maven-metadata.xml"))
        .thenReturn(Optional.of(asset(100, "maven-metadata.xml", OLD, null)));
    assertTrue(scan().candidates().isEmpty()); // missing metadata blob => fail closed
  }

  @Test void resolutionDetectsContentAndMetadataChangesButNotUnrelatedBuildDeletions() {
    addBuild(1, 2, OLD, null); addBuild(2, 2, OLD, null);
    var before = scan().candidates().getFirst().subject();
    var current = service.resolve(repository(), 1, true).orElseThrow();
    assertEquals(before.contentToken(), current.contentToken());
    assertNull(service.revalidate(repository(), rules(Map.of("retainCount", 1)), current, NOW));
    verify(snapshots).lockFamily(1, family.path(), "com.example", "demo", "1.0-SNAPSHOT");
    inventory.put(1L, asset(1, "changed.jar", NOW, null));
    assertNotEquals(before.contentToken(), service.resolve(repository(), 1, true).orElseThrow().contentToken());
    when(snapshots.hasLatestVariant(1)).thenReturn(true);
    assertEquals("latest classifier retained", service.revalidate(repository(), rules(Map.of("retainCount", 1)), current, NOW));
    assertEquals("snapshot policy mode changed", service.revalidate(repository(), Map.of("retainCount", 1), current, NOW));
    assertTrue(service.resolve(repository(), 999, true).isEmpty());
  }

  @Test void retainsAtLeastOneBuildAndRejectsAmbiguousLegacyOrProxyConfiguration() {
    for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(), Map.of("retainCount", 0), Map.of("retainCount", -1))) {
      assertThrows(CleanupValidationException.class, () -> CleanupCriteria.parse(rules(new LinkedHashMap<>(invalid))));
    }
    assertThrows(CleanupValidationException.class, () -> CleanupCriteria.parse(Map.of(
        "mode", CleanupCriteria.SNAPSHOT_MODE, "snapshot", Map.of("retainCount", 1), "retainCount", 0)));
    assertFalse(CleanupCriteria.snapshotMode(Map.of("retainCount", 5)));
    var repo = repository();
    var proxy = new RepositoryRecord(repo.id(), repo.name(), repo.format(), RepositoryType.PROXY,
        "maven2-proxy", true, 1L, null, null, null, null, null, true, Map.of());
    assertThrows(CleanupValidationException.class, () -> service.scan(proxy, rules(Map.of("retainCount", 1)), 100, NOW, null));
  }

  @Test void metadataReferencesPinOlderBuildsAndReplacementInvalidatesTheCandidate() throws Exception {
    addBuild(1, 2, OLD, null); addBuild(2, 2, OLD, null); addBuild(3, 2, OLD, null);
    // Use the real parser's epoch timestamp; leading zeroes in the metadata build number must still match.
    long timestamp = com.github.klboke.kkrepo.protocol.maven.path.MavenSnapshotBuild
        .parse(family.path() + "/demo-1.0-20260101.120000-1.jar").orElseThrow().timestamp();
    for (int i = 0; i < builds.size(); i++) {
      var b = builds.get(i);
      builds.set(i, new Build(b.id(), 1, 1, b.version(), timestamp, b.buildNumber(), b.revision(), 2, 1, OLD));
    }
    metadata("""
        <metadata><groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0-SNAPSHOT</version>
        <versioning><snapshot><timestamp>20260101.120000</timestamp><buildNumber>001</buildNumber></snapshot>
        <snapshotVersions><snapshotVersion><extension>jar</extension><value>1.0-20260101.120000-001</value></snapshotVersion></snapshotVersions>
        </versioning></metadata>
        """);
    var result = scan();
    assertEquals(List.of(2L), result.candidates().stream().map(c -> c.subject().identityId()).toList());
    var original = result.candidates().getFirst().subject();
    when(assets.findAssetByPath(1, family.path() + "/maven-metadata.xml"))
        .thenReturn(Optional.of(asset(200, "maven-metadata.xml", NOW, null)));
    assertNotEquals(original.contentToken(), service.resolve(repository(), 2, true).orElseThrow().contentToken());
  }

  @Test void invalidUnreadableAndOversizedMetadataFailClosed() throws Exception {
    addBuild(1, 2, OLD, null); addBuild(2, 2, OLD, null);
    for (String xml : List.of("not XML", "<metadata/>",
        "<metadata><groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0-SNAPSHOT</version><versioning><snapshotVersions><snapshotVersion/></snapshotVersions></versioning></metadata>",
        "<metadata><groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0-SNAPSHOT</version><versioning><snapshot><timestamp>nonsense</timestamp><buildNumber>1</buildNumber></snapshot></versioning></metadata>")) {
      metadata(xml);
      assertTrue(scan().candidates().isEmpty(), xml);
      assertTrue(scan().cursorWarning().contains("metadata_unavailable"));
    }
    metadata("x".repeat(MavenSnapshotCleanupService.MAX_METADATA_BYTES + 1));
    assertTrue(scan().candidates().isEmpty());
    var objectStore = metadata("<metadata/>");
    when(objectStore.get(any())).thenThrow(new IllegalStateException("unavailable"));
    assertTrue(scan().candidates().isEmpty());
  }

  @Test void usageRevisionAndInheritedComponentProtectionFollowTheWholeBuild() {
    addBuild(1, 2, OLD, null); addBuild(2, 2, OLD, null);
    var usage = new CleanupUsage(1, 1, OLD, NOW.minusSeconds(60), 4, NOW);
    when(cleanup.findAssetUsage(any())).thenReturn(Map.of(1L, usage));
    var candidate = scan().candidates().getFirst();
    assertEquals(4, candidate.subject().usageRevision());
    assertEquals(NOW.minusSeconds(60), candidate.subject().lastDownloadedAt());
    assertEquals("snapshot age or download changed", service.revalidate(repository(),
        rules(Map.of("retainCount", 1, "lastDownloadedOlderThanDays", 7)), candidate.subject(), NOW));
    var lookups = org.mockito.ArgumentCaptor.forClass(Collection.class);
    verify(cleanup).findActiveProtections(eq(1L), lookups.capture(), eq(NOW));
    assertTrue(((Collection<?>) lookups.getValue()).stream().map(CleanupProtectionLookup.class::cast)
        .anyMatch(lookup -> "COMPONENT".equals(lookup.subjectKind()) && "component:77".equals(lookup.subjectKey())));
    assertTrue(((Collection<?>) lookups.getValue()).stream().map(CleanupProtectionLookup.class::cast)
        .anyMatch(lookup -> "ASSET".equals(lookup.subjectKind()) && "asset:1".equals(lookup.subjectKey())));
  }

  @Test void finalRevalidationCoversRetentionReadinessDisappearingAndRecentlyMutatedBuilds() {
    addBuild(1, 2, OLD, null); addBuild(2, 2, OLD, null);
    var subject = scan().candidates().getFirst().subject();
    assertEquals("newest build retained", service.revalidate(repository(), rules(Map.of("retainCount", 2)), subject, NOW));
    when(tracking.trackingStartedAt(1)).thenReturn(null);
    assertEquals("download observation is not ready", service.revalidate(repository(),
        rules(Map.of("retainCount", 1, "lastDownloadedOlderThanDays", 7)), subject, NOW));
    var b = builds.getFirst();
    builds.set(0, new Build(1, 1, 1, b.version(), 1, 1, 2, 2, 1, NOW));
    assertEquals("recent snapshot mutation", service.revalidate(repository(), rules(Map.of("retainCount", 1)), subject, NOW));
    service.setWriteGrace(Duration.ZERO);
    assertNull(service.revalidate(repository(), rules(Map.of("retainCount", 1)), subject, NOW));
    assertThrows(IllegalArgumentException.class, () -> service.setWriteGrace(Duration.ofSeconds(-1)));
    builds.removeFirst();
    assertEquals("snapshot build disappeared", service.revalidate(repository(), rules(Map.of("retainCount", 1)), subject, NOW));
  }

  @Test void skipsUnmatchedAndChecksumOnlyBuildsWithoutReadingObjectStorage() {
    addBuild(1, 1, OLD, null); addBuild(2, 1, OLD, null);
    assertTrue(service.scan(repository(), rules(Map.of("retainCount", 1, "pattern", "other-*")), 10, NOW, null)
        .candidates().isEmpty());
    var b = builds.getFirst();
    builds.set(0, new Build(1, 1, 1, b.version(), 1, 1, 1, 1, 0, OLD));
    assertTrue(scan().cursorWarning().contains("no_primary_artifact"));
    assertTrue(scan().candidates().isEmpty());
    verifyNoInteractions(storage);
  }

  private BlobStorage metadata(String xml) throws Exception {
    var objectStore = mock(BlobStorage.class);
    when(storage.forBlobStoreId(1)).thenReturn(objectStore);
    when(assets.findAssetByPath(1, family.path() + "/maven-metadata.xml"))
        .thenReturn(Optional.of(asset(100, "maven-metadata.xml", OLD, null)));
    when(assets.findBlobById(200)).thenReturn(Optional.of(new AssetBlobRecord(200L, 1,
        "blob://bucket/metadata", new byte[32], "metadata", new byte[32], null, null, null,
        xml.length(), "application/xml", null, null, OLD, OLD, Map.of())));
    when(objectStore.get(any())).thenAnswer(call -> Optional.of(new java.io.ByteArrayInputStream(
        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    return objectStore;
  }

  private CleanupSubjectScanner.ScanResult scan() {
    return service.scan(repository(), rules(Map.of("retainCount", 1)), 100, NOW, null);
  }
  private Map<Long, AssetRecord> selected(Collection<Long> ids) {
    Map<Long, AssetRecord> result = new LinkedHashMap<>();
    ids.forEach(id -> { if (inventory.containsKey(id)) result.put(id, inventory.get(id)); });
    return result;
  }
  private void addBuild(int id, int count, Instant published, Instant downloaded) {
    List<Long> ids = new ArrayList<>();
    long first = (long) (id - 1) * (count > 100 ? 5000 : 4) + 1;
    for (int i = 0; i < count; i++) {
      long assetId = first + i;
      ids.add(assetId);
      inventory.put(assetId, asset(assetId, "demo-1.0-20260101.120000-" + id + ".jar" + (i == 0 ? "" : ".sha" + i), published, downloaded));
    }
    builds.add(new Build(id, 1, 1, "1.0-20260101.120000-" + id, id, id, 1, count, 1, published));
    when(snapshots.assetIds(eq((long) id), anyInt())).thenReturn(ids);
  }
  private AssetRecord asset(long id, String name, Instant published, Instant downloaded) {
    return new AssetRecord(id, 1, 77L, id + 100, RepositoryFormat.MAVEN2, family.path() + "/" + name,
        new byte[32], name, "artifact", "application/octet-stream", 100L, downloaded, published, Map.of());
  }
  static Map<String, Object> rules(Map<String, Object> rules) {
    return Map.of("mode", CleanupCriteria.SNAPSHOT_MODE, "snapshot", rules);
  }
  static RepositoryRecord repository() {
    return new RepositoryRecord(1L, "snapshots", RepositoryFormat.MAVEN2, RepositoryType.HOSTED,
        "maven2-hosted", true, 1L, null, null, null, null, null, true, Map.of());
  }
}
