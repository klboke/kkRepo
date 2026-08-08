package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CondaRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

  @Test
  void exercisesTheCompleteCondaRegistryPersistenceContract() {
    packageRevisionTombstoneBindingAndLeaseLifecycleIsDurable();
    unchangedProxyInventoryKeepsRevisionStable();
    preferredStreamingQueriesKeepRepositoryPriority();
    hostedUpdatesInvalidateNestedGroupsAndExposeAllReadModels();
    proxyInventoryReconcilesInsertsUpdatesDeletesAndBackfillsFingerprints();
    channelBindingLeaseAndRepositoryCleanupBranchesAreDurable();
  }

  private void packageRevisionTombstoneBindingAndLeaseLifecycleIsDurable() {
    long repositoryId = insertRepository("conda-hosted", "conda");
    long groupId = insertRepository("conda-group", "conda");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());

    CondaRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.saveHostedPackage(hosted(repositoryId)));
    assertEquals(1, stored.revision());
    assertEquals(stored, dao.findPackage(
        repositoryId, "main", "linux-64", stored.filename()).orElseThrow());
    assertEquals(List.of("main"), dao.listChannels(repositoryId));

    CondaRegistryDao.PackageRecord deleted = inTransaction(() -> dao.tombstoneAndDeletePackage(
        repositoryId, "main", "linux-64", stored.filename(), "test", 0,
        NOW.plusSeconds(1))).orElseThrow();
    assertEquals(stored.id(), deleted.id());
    assertTrue(dao.findPackage(repositoryId, "main", "linux-64", stored.filename()).isEmpty());
    assertEquals(stored.filename(), dao.listTombstones(
        repositoryId, "main", "linux-64").getFirst().filename());
    assertEquals(2, dao.currentRepositoryRevision(repositoryId));

    String md5Identity = "md5:" + "b".repeat(32);
    inTransaction(() -> dao.upsertGroupSourceBinding(new CondaRegistryDao.GroupSourceBinding(
        groupId, "main", "linux-64", stored.filename(), repositoryId, 2,
        md5Identity, 4, NOW, NOW)));
    assertEquals(md5Identity, dao.findGroupSourceBinding(
        groupId, "main", "linux-64", stored.filename()).orElseThrow().sha256());

    CondaRegistryDao.Lease first = dao.tryAcquireLease(
        "conda:test:lease", "first", Instant.now().plusSeconds(60)).orElseThrow();
    assertTrue(dao.tryAcquireLease(
        "conda:test:lease", "second", Instant.now().plusSeconds(60)).isEmpty());
    dao.releaseLease(first.leaseKey(), first.owner(), first.fencingToken());
    CondaRegistryDao.Lease second = dao.tryAcquireLease(
        "conda:test:lease", "second", Instant.now().plusSeconds(60)).orElseThrow();
    assertTrue(second.fencingToken() > first.fencingToken());
    assertFalse(dao.renewLease(
        first.leaseKey(), first.owner(), first.fencingToken(), Instant.now().plusSeconds(60)));
  }

  private void unchangedProxyInventoryKeepsRevisionStable() {
    long repositoryId = insertRepository("conda-proxy", "conda");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());
    CondaRegistryDao.PackageRecord record = proxy(repositoryId);
    String packageBaseUrl = "../package-pool/";

    long first = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "a".repeat(64), packageBaseUrl, List.of(record), NOW));
    long second = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "a".repeat(64), packageBaseUrl, List.of(record),
        NOW.plusSeconds(30)));

    assertEquals(first, second);
    assertEquals(first, dao.currentRepositoryRevision(repositoryId));
    assertEquals(1, dao.listPackages(repositoryId, "main", "linux-64").size());
    CondaRegistryDao.ChannelState state = dao.findChannelState(
        repositoryId, "main", "linux-64").orElseThrow();
    assertEquals(NOW, state.indexedAt());
    assertEquals(packageBaseUrl, state.packageBaseUrl());

    long changed = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "d".repeat(64), "../new-pool/",
        List.of(record), NOW.plusSeconds(60)));
    assertEquals(second, changed);
    CondaRegistryDao.ChannelState refreshed = dao.findChannelState(
        repositoryId, "main", "linux-64").orElseThrow();
    assertEquals("d".repeat(64), refreshed.metadataSha256());
    assertEquals("../new-pool/", refreshed.packageBaseUrl());
    assertEquals(NOW.plusSeconds(60), refreshed.indexedAt());
  }

  private void preferredStreamingQueriesKeepRepositoryPriority() {
    long preferredId = insertRepository("conda-preferred", "conda");
    long fallbackId = insertRepository("conda-fallback", "conda");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());
    inTransaction(() -> dao.saveHostedPackage(hosted(preferredId)));
    inTransaction(() -> dao.saveHostedPackage(hosted(fallbackId)));

    ArrayList<CondaRegistryDao.PackageRecord> repodata = new ArrayList<>();
    dao.visitPackages(preferredId, "main", "linux-64", "conda", repodata::add);
    assertEquals(List.of(preferredId),
        repodata.stream().map(CondaRegistryDao.PackageRecord::repositoryId).toList());
    repodata.clear();
    dao.visitPreferredPackages(
        List.of(preferredId, fallbackId), "main", "linux-64", "conda", repodata::add);
    assertEquals(List.of(preferredId),
        repodata.stream().map(CondaRegistryDao.PackageRecord::repositoryId).toList());

    ArrayList<CondaRegistryDao.PackageRecord> channeldata = new ArrayList<>();
    dao.visitPackagesByChannel(preferredId, "main", channeldata::add);
    assertEquals(List.of(preferredId),
        channeldata.stream().map(CondaRegistryDao.PackageRecord::repositoryId).toList());
    channeldata.clear();
    dao.visitPreferredPackagesByChannel(
        List.of(preferredId, fallbackId), "main", channeldata::add);
    assertEquals(List.of(preferredId),
        channeldata.stream().map(CondaRegistryDao.PackageRecord::repositoryId).toList());
    dao.visitPackages(preferredId, "main", "linux-64", "conda", null);
    dao.visitPackagesByChannel(preferredId, "main", null);
  }

  private void hostedUpdatesInvalidateNestedGroupsAndExposeAllReadModels() {
    long repositoryId = insertRepository("conda-hosted-update", "conda");
    long groupId = insertRepository("conda-group-update", "conda");
    long outerGroupId = insertRepository("conda-outer-group-update", "conda");
    jdbc().update("UPDATE repository SET type = 'group', recipe_name = 'conda-group' WHERE id = ?",
        groupId);
    jdbc().update("UPDATE repository SET type = 'group', recipe_name = 'conda-group' WHERE id = ?",
        outerGroupId);
    jdbc().update(
        "INSERT INTO repository_member (repository_id, member_repository_id, sort_order) VALUES (?, ?, 0)",
        groupId, repositoryId);
    jdbc().update(
        "INSERT INTO repository_member (repository_id, member_repository_id, sort_order) VALUES (?, ?, 0)",
        outerGroupId, groupId);
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());

    CondaRegistryDao.PackageRecord first = inTransaction(
        () -> dao.saveHostedPackage(hosted(repositoryId)));
    LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("nested", Map.of("z", 1, "a", List.of("value")));
    CondaRegistryDao.PackageRecord update = new CondaRegistryDao.PackageRecord(
        null, repositoryId, "main", "linux-64", first.filename(), "demo", "2.0", "h1", 1,
        "conda", metadata, null, null, "d".repeat(64), 24, null, null,
        CondaRegistryDao.SOURCE_HOSTED, 0, null, null);
    CondaRegistryDao.PackageRecord second = inTransaction(() -> dao.saveHostedPackage(update));

    assertEquals(first.id(), second.id());
    assertEquals(2, second.revision());
    assertEquals("2.0", second.version());
    assertNull(second.assetId());
    assertTrue(second.recordSha256() != null && second.recordSha256().length() == 64);
    assertEquals(2, dao.currentRepositoryRevision(groupId));
    assertEquals(2, dao.currentRepositoryRevision(outerGroupId));
    assertEquals(Map.of(repositoryId, 2L, groupId, 2L, 999999L, 0L),
        dao.currentRepositoryRevisions(List.of(repositoryId, groupId, repositoryId, 999999L)));
    assertEquals(Map.of(), dao.currentRepositoryRevisions(null));
    assertEquals(List.of(second), dao.listPackages(repositoryId, "main", "linux-64"));
    assertEquals(List.of(second), dao.listPackagesByChannel(repositoryId, "main"));
    assertEquals(second.updatedAt(), dao.latestChannelUpdatedAt(repositoryId, "main"));
    assertEquals(Set.of(first.filename()), dao.findPreferredPackageFilenames(
        java.util.Arrays.asList(null, repositoryId, repositoryId), "main", "linux-64",
        java.util.Arrays.asList(first.filename(), first.filename(), null, "missing.conda")));
    assertTrue(dao.findPreferredPackage(
        List.of(999999L, repositoryId), "main", "linux-64", first.filename()).isPresent());
    assertTrue(dao.findPreferredPackage(
        List.of(), "main", "linux-64", first.filename()).isEmpty());

    assertThrows(IllegalArgumentException.class,
        () -> dao.saveHostedPackage(proxy(repositoryId)));
  }

  private void proxyInventoryReconcilesInsertsUpdatesDeletesAndBackfillsFingerprints() {
    long repositoryId = insertRepository("conda-proxy-reconcile", "conda");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());
    CondaRegistryDao.PackageRecord first = proxy(repositoryId, "alpha-1.0-0.conda", "alpha", "a");
    CondaRegistryDao.PackageRecord second = proxy(repositoryId, "beta-1.0-0.conda", "beta", "b");

    long initial = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "1".repeat(64), null,
        visitor -> {
          visitor.accept(first);
          visitor.accept(second);
        }, null));
    assertEquals(1, initial);
    jdbc().update(
        "UPDATE conda_package_record SET record_sha256 = NULL WHERE repository_id = ? AND filename = ?",
        repositoryId, first.filename());

    long metadataOnly = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "2".repeat(64), "../pool/",
        List.of(first, second), NOW.plusSeconds(1)));
    assertEquals(initial, metadataOnly);
    assertTrue(jdbc().queryForObject(
        "SELECT record_sha256 IS NOT NULL FROM conda_package_record WHERE repository_id = ? AND filename = ?",
        Boolean.class, repositoryId, first.filename()));

    CondaRegistryDao.PackageRecord changed = proxy(
        repositoryId, "alpha-1.0-0.conda", "alpha", "changed");
    CondaRegistryDao.PackageRecord added = proxy(
        repositoryId, "gamma-1.0-0.tar.bz2", "gamma", "c");
    long changedRevision = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "3".repeat(64), "../pool/",
        List.of(changed, added), NOW.plusSeconds(2)));
    assertEquals(2, changedRevision);
    assertTrue(dao.findPackage(
        repositoryId, "main", "linux-64", second.filename()).isEmpty());
    assertEquals(List.of(changed.filename(), added.filename()), dao.listPackages(
        repositoryId, "main", "linux-64").stream()
        .map(CondaRegistryDao.PackageRecord::filename).toList());

    ArrayList<String> requested = new ArrayList<>();
    for (int index = 0; index < 501; index++) requested.add("missing-" + index + ".conda");
    requested.add(changed.filename());
    assertEquals(Set.of(changed.filename()), dao.findPreferredPackageFilenames(
        List.of(repositoryId), "main", "linux-64", requested));
    assertEquals(Set.of(), dao.findPreferredPackageFilenames(
        List.of(), "main", "linux-64", requested));
    assertEquals(Set.of(), dao.findPreferredPackageFilenames(
        List.of(repositoryId), "main", "linux-64", List.of()));

    CondaRegistryDao.PackageRecord wrongCoordinate = proxy(
        repositoryId + 1, "wrong.conda", "wrong", "x");
    assertThrows(IllegalArgumentException.class, () -> inTransaction(
        () -> dao.replaceProxyPackages(repositoryId, "main", "linux-64", "4".repeat(64),
            null, List.of(wrongCoordinate), NOW)));
    assertThrows(IllegalArgumentException.class, () -> inTransaction(
        () -> dao.replaceProxyPackages(repositoryId, "main", "linux-64", "4".repeat(64),
            null, List.of(hosted(repositoryId)), NOW)));
    assertThrows(IllegalArgumentException.class, () -> inTransaction(
        () -> dao.replaceProxyPackages(repositoryId, "main", "linux-64", "4".repeat(64),
            null, List.of(changed, changed), NOW)));

    long emptied = inTransaction(() -> dao.replaceProxyPackages(
        repositoryId, "main", "linux-64", "5".repeat(64), null,
        (List<CondaRegistryDao.PackageRecord>) null, NOW.plusSeconds(3)));
    assertEquals(3, emptied);
    assertTrue(dao.listPackages(repositoryId, "main", "linux-64").isEmpty());
  }

  private void channelBindingLeaseAndRepositoryCleanupBranchesAreDurable() {
    long repositoryId = insertRepository("conda-state-cleanup", "conda");
    long memberId = insertRepository("conda-state-member", "conda");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());

    dao.ensureChannelState(new CondaRegistryDao.ChannelState(
        repositoryId, "main", "noarch", null, null, 0, null, null));
    dao.ensureChannelState(new CondaRegistryDao.ChannelState(
        repositoryId, "main", "noarch", "ignored", null, 99, NOW, NOW));
    dao.ensureChannelState(new CondaRegistryDao.ChannelState(
        repositoryId, "secondary", "noarch", null, null, 9, NOW, NOW));
    CondaRegistryDao.ChannelState state = dao.findChannelState(
        repositoryId, "main", "noarch").orElseThrow();
    assertEquals(0, state.revision());
    assertEquals(9, dao.findChannelState(
        repositoryId, "secondary", "noarch").orElseThrow().revision());
    assertTrue(state.indexedAt() != null);
    assertTrue(dao.tombstoneAndDeletePackage(
        repositoryId, "main", "noarch", "missing.conda", null, 7, null).isEmpty());

    CondaRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.saveHostedPackage(hosted(repositoryId)));
    assertTrue(inTransaction(() -> dao.tombstoneAndDeletePackage(
        repositoryId, "main", "linux-64", stored.filename(), null, 77, null)).isPresent());
    assertEquals(77, dao.listTombstones(
        repositoryId, "main", "linux-64").getFirst().revision());

    dao.upsertGroupSourceBinding(new CondaRegistryDao.GroupSourceBinding(
        repositoryId, "main", "linux-64", stored.filename(), memberId, 1,
        "a".repeat(64), 2, null, null));
    dao.upsertGroupSourceBinding(new CondaRegistryDao.GroupSourceBinding(
        repositoryId, "main", "linux-64", stored.filename(), memberId, 3,
        "b".repeat(64), 4, NOW, NOW));
    assertEquals(3, dao.findGroupSourceBinding(
        repositoryId, "main", "linux-64", stored.filename()).orElseThrow().memberRevision());
    dao.deleteGroupSourceBindings(repositoryId);
    assertTrue(dao.findGroupSourceBinding(
        repositoryId, "main", "linux-64", stored.filename()).isEmpty());

    assertThrows(IllegalArgumentException.class,
        () -> dao.tryAcquireLease("conda:state:invalid", "owner", Instant.EPOCH));
    CondaRegistryDao.Lease lease = dao.tryAcquireLease(
        "conda:" + repositoryId + ":state", "owner", Instant.now().plusSeconds(60))
        .orElseThrow();
    assertFalse(dao.renewLease(
        lease.leaseKey(), lease.owner(), lease.fencingToken(), Instant.EPOCH));
    assertTrue(dao.renewLease(
        lease.leaseKey(), lease.owner(), lease.fencingToken(), Instant.now().plusSeconds(120)));
    assertEquals(0, dao.deleteExpiredLeases(Instant.EPOCH, 10));
    dao.releaseLease(lease.leaseKey(), lease.owner(), lease.fencingToken());
    assertEquals(1, dao.deleteExpiredLeases(null, 0));

    dao.deleteRepositoryState(repositoryId);
    assertTrue(dao.listChannels(repositoryId).isEmpty());
    assertTrue(dao.listTombstones(repositoryId, "main", "linux-64").isEmpty());
    assertEquals(0, dao.currentRepositoryRevision(repositoryId));
  }

  private static CondaRegistryDao.PackageRecord hosted(long repositoryId) {
    return record(repositoryId, CondaRegistryDao.SOURCE_HOSTED);
  }

  private static CondaRegistryDao.PackageRecord proxy(long repositoryId) {
    return record(repositoryId, CondaRegistryDao.SOURCE_PROXY);
  }

  private static CondaRegistryDao.PackageRecord proxy(
      long repositoryId, String filename, String name, String fingerprint) {
    return new CondaRegistryDao.PackageRecord(
        null, repositoryId, "main", "linux-64", filename, name, "1.0", "0", 0,
        filename.endsWith(".conda") ? "conda" : "tar.bz2",
        Map.of("depends", List.of("python"), "fingerprint", fingerprint),
        null, "b".repeat(32), "a".repeat(64), 12, null, null,
        CondaRegistryDao.SOURCE_PROXY, 0, NOW, NOW);
  }

  private static CondaRegistryDao.PackageRecord record(long repositoryId, String source) {
    return new CondaRegistryDao.PackageRecord(
        null, repositoryId, "main", "linux-64", "demo-1.0-0.conda",
        "demo", "1.0", "0", 0, "conda", Map.of("depends", List.of("python")),
        "c".repeat(64), "b".repeat(32), "a".repeat(64), 12, null, null, source, 0, NOW, NOW);
  }
}
