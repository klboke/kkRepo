package com.github.klboke.kkrepo.persistence.jdbc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CondaRegistryDaoDefaultsTest {
  private static final Instant EARLY = Instant.parse("2026-08-07T00:00:00Z");
  private static final Instant LATE = EARLY.plusSeconds(30);

  @Test
  void batchesDistinctRevisionLookupsAndHandlesNullCollections() {
    StubDao dao = realDefaults();
    dao.revisions.put(1L, 11L);
    dao.revisions.put(2L, 22L);

    assertEquals(Map.of(), dao.currentRepositoryRevisions(null));
    assertEquals(Map.of(1L, 11L, 2L, 22L),
        dao.currentRepositoryRevisions(List.of(1L, 2L, 1L)));
    assertEquals(List.of(1L, 2L), dao.revisionLookups);
  }

  @Test
  void materializesReplayableProxySourcesForLegacyImplementations() {
    StubDao dao = realDefaults();
    CondaRegistryDao.PackageRecord first = record(1, "main", "linux-64", "a.conda",
        "alpha", "conda", EARLY);
    CondaRegistryDao.PackageRecord second = record(1, "main", "linux-64", "b.tar.bz2",
        "beta", "tar.bz2", LATE);
    dao.replaceResult = 7;

    assertEquals(7, dao.replaceProxyPackages(
        1, "main", "linux-64", "hash", "../pool/",
        visitor -> {
          visitor.accept(first);
          visitor.accept(second);
        }, EARLY));
    assertEquals(List.of(first, second), dao.replaced);

    dao.replaceProxyPackages(
        1, "main", "linux-64", "hash", "../pool/",
        (CondaRegistryDao.PackageRecordSource) null, EARLY);
    assertEquals(List.of(), dao.replaced);
  }

  @Test
  void visitsRecordsWithDeterministicFilteringPriorityAndOrdering() {
    StubDao dao = realDefaults();
    CondaRegistryDao.PackageRecord fallback = record(
        2, "main", "linux-64", "same.conda", "zeta", "conda", EARLY);
    CondaRegistryDao.PackageRecord preferred = record(
        1, "main", "linux-64", "same.conda", "alpha", "conda", LATE);
    CondaRegistryDao.PackageRecord legacy = record(
        1, "main", "noarch", "legacy.tar.bz2", "beta", "tar.bz2", null);
    dao.packageLists.put(StubDao.key(1, "main", "linux-64"), List.of(legacy, preferred));
    dao.packageLists.put(StubDao.key(2, "main", "linux-64"), List.of(fallback));
    dao.channelLists.put(StubDao.key(1, "main"), List.of(legacy, preferred));
    dao.channelLists.put(StubDao.key(2, "main"), List.of(fallback));

    ArrayList<CondaRegistryDao.PackageRecord> visited = new ArrayList<>();
    dao.visitPackages(1, "main", "linux-64", "conda", visited::add);
    assertEquals(List.of(preferred), visited);

    visited.clear();
    dao.visitPreferredPackages(
        List.of(1L, 2L), "main", "linux-64", "conda", visited::add);
    assertEquals(List.of(preferred), visited);
    visited.clear();
    dao.visitPreferredPackages(
        null, "main", "linux-64", "conda", visited::add);
    assertEquals(List.of(), visited);

    visited.clear();
    dao.visitPackagesByChannel(1, "main", visited::add);
    assertEquals(List.of(preferred, legacy), visited);
    visited.clear();
    dao.visitPreferredPackagesByChannel(List.of(1L, 2L), "main", visited::add);
    assertEquals(List.of(preferred, legacy), visited);
    visited.clear();
    dao.visitPreferredPackagesByChannel(null, "main", visited::add);
    assertEquals(List.of(), visited);
  }

  @Test
  void resolvesLatestUpdatesAndPreferredCoordinates() {
    StubDao dao = realDefaults();
    CondaRegistryDao.PackageRecord first = record(
        1, "main", "linux-64", "a.conda", "alpha", "conda", EARLY);
    CondaRegistryDao.PackageRecord latest = record(
        1, "main", "linux-64", "b.conda", "beta", "conda", LATE);
    dao.channelLists.put(StubDao.key(1, "main"), List.of(first, latest));
    assertEquals(LATE, dao.latestChannelUpdatedAt(1, "main"));
    dao.channelLists.put(StubDao.key(1, "empty"), List.of(
        record(1, "empty", "noarch", "empty.conda", "empty", "conda", null)));
    assertEquals(Instant.EPOCH, dao.latestChannelUpdatedAt(1, "empty"));

    dao.packages.put(StubDao.key(2, "main", "linux-64", "a.conda"), first);
    assertEquals(Optional.of(first), dao.findPreferredPackage(
        List.of(1L, 2L), "main", "linux-64", "a.conda"));
    assertEquals(Optional.empty(), dao.findPreferredPackage(
        null, "main", "linux-64", "a.conda"));
    assertEquals(Set.of("a.conda"), dao.findPreferredPackageFilenames(
        List.of(1L, 2L), "main", "linux-64", List.of("a.conda", "missing")));
    assertEquals(Set.of(), dao.findPreferredPackageFilenames(
        List.of(1L), "main", "linux-64", null));
  }

  @Test
  void packageRecordsDefensivelyCopyMetadataAndPreserveInitialIndexTime() {
    LinkedHashMap<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("depends", List.of("python"));
    CondaRegistryDao.PackageRecord record = new CondaRegistryDao.PackageRecord(
        null, 1, "main", "linux-64", "demo.conda", "demo", "1.0", "0", 0,
        "conda", mutable, "fingerprint", null, "a".repeat(64), 12, null, null,
        CondaRegistryDao.SOURCE_PROXY, 0, null, EARLY);
    mutable.put("summary", "mutated");
    assertEquals(Map.of("depends", List.of("python")), record.metadata());
    assertThrows(UnsupportedOperationException.class,
        () -> record.metadata().put("summary", "blocked"));

    CondaRegistryDao.PackageRecord firstRevision = record.withRevision(3, LATE);
    assertEquals(3, firstRevision.revision());
    assertSame(LATE, firstRevision.indexedAt());
    assertSame(LATE, firstRevision.updatedAt());
    CondaRegistryDao.PackageRecord secondRevision = firstRevision.withRevision(4, EARLY);
    assertSame(LATE, secondRevision.indexedAt());
    assertSame(EARLY, secondRevision.updatedAt());

    CondaRegistryDao.PackageRecord empty = new CondaRegistryDao.PackageRecord(
        null, 1, "main", "noarch", "empty.conda", "empty", "1", "0", 0,
        "conda", null, null, null, null, 0, null, null, CondaRegistryDao.SOURCE_PROXY,
        0, null, null);
    assertEquals(Map.of(), empty.metadata());
    assertEquals(0, realDefaults().deleteExpiredLeases(EARLY, 10));
  }

  private static StubDao realDefaults() {
    return new StubDao();
  }

  private static CondaRegistryDao.PackageRecord record(
      long repositoryId,
      String channel,
      String subdir,
      String filename,
      String name,
      String format,
      Instant updatedAt) {
    return new CondaRegistryDao.PackageRecord(
        null, repositoryId, channel, subdir, filename, name, "1.0", "0", 0, format,
        Map.of(), filename, null, "a".repeat(64), 12, null, null,
        CondaRegistryDao.SOURCE_PROXY, 0, updatedAt, updatedAt);
  }

  private static final class StubDao implements CondaRegistryDao {
    private final Map<Long, Long> revisions = new LinkedHashMap<>();
    private final List<Long> revisionLookups = new ArrayList<>();
    private final Map<String, List<PackageRecord>> packageLists = new LinkedHashMap<>();
    private final Map<String, List<PackageRecord>> channelLists = new LinkedHashMap<>();
    private final Map<String, PackageRecord> packages = new LinkedHashMap<>();
    private List<PackageRecord> replaced = List.of();
    private long replaceResult;

    @Override
    public long nextRepositoryRevision(long repositoryId) {
      return revisions.merge(repositoryId, 1L, Long::sum);
    }

    @Override
    public long currentRepositoryRevision(long repositoryId) {
      revisionLookups.add(repositoryId);
      return revisions.getOrDefault(repositoryId, 0L);
    }

    @Override
    public PackageRecord saveHostedPackage(PackageRecord record) {
      return record;
    }

    @Override
    public long replaceProxyPackages(
        long repositoryId,
        String channel,
        String subdir,
        String metadataSha256,
        String packageBaseUrl,
        List<PackageRecord> records,
        Instant indexedAt) {
      replaced = List.copyOf(records);
      return replaceResult;
    }

    @Override
    public Optional<PackageRecord> findPackage(
        long repositoryId, String channel, String subdir, String filename) {
      return Optional.ofNullable(packages.get(key(repositoryId, channel, subdir, filename)));
    }

    @Override
    public List<PackageRecord> listPackages(
        long repositoryId, String channel, String subdir) {
      return packageLists.getOrDefault(key(repositoryId, channel, subdir), List.of());
    }

    @Override
    public List<PackageRecord> listPackagesByChannel(long repositoryId, String channel) {
      return channelLists.getOrDefault(key(repositoryId, channel), List.of());
    }

    @Override
    public List<String> listChannels(long repositoryId) {
      return List.of();
    }

    @Override
    public Optional<ChannelState> findChannelState(
        long repositoryId, String channel, String subdir) {
      return Optional.empty();
    }

    @Override
    public void ensureChannelState(ChannelState state) {
    }

    @Override
    public Optional<PackageRecord> tombstoneAndDeletePackage(
        long repositoryId,
        String channel,
        String subdir,
        String filename,
        String reason,
        long revision,
        Instant deletedAt) {
      return Optional.empty();
    }

    @Override
    public List<Tombstone> listTombstones(
        long repositoryId, String channel, String subdir) {
      return List.of();
    }

    @Override
    public Optional<GroupSourceBinding> findGroupSourceBinding(
        long groupRepositoryId, String channel, String subdir, String filename) {
      return Optional.empty();
    }

    @Override
    public void upsertGroupSourceBinding(GroupSourceBinding binding) {
    }

    @Override
    public void deleteGroupSourceBindings(long groupRepositoryId) {
    }

    @Override
    public Optional<Lease> tryAcquireLease(String leaseKey, String owner, Instant expiresAt) {
      return Optional.empty();
    }

    @Override
    public boolean renewLease(
        String leaseKey, String owner, long fencingToken, Instant expiresAt) {
      return false;
    }

    @Override
    public void releaseLease(String leaseKey, String owner, long fencingToken) {
    }

    @Override
    public void deleteRepositoryState(long repositoryId) {
    }

    private static String key(Object... values) {
      return java.util.Arrays.toString(values);
    }
  }
}
