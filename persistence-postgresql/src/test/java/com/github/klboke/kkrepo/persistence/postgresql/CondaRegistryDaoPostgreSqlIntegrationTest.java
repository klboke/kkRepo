package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcCondaRegistryDao;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CondaRegistryDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

  @Test
  void packageRevisionTombstoneBindingAndLeaseLifecycleIsDurable() {
    long repositoryId = insertRepository("conda-hosted", "hosted");
    long groupId = insertRepository("conda-group", "group");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());

    CondaRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.saveHostedPackage(record(repositoryId, CondaRegistryDao.SOURCE_HOSTED)));
    assertEquals(1, stored.revision());
    assertEquals(stored, dao.findPackage(
        repositoryId, "main", "linux-64", stored.filename()).orElseThrow());
    assertEquals(List.of("main"), dao.listChannels(repositoryId));

    inTransaction(() -> dao.tombstoneAndDeletePackage(
        repositoryId, "main", "linux-64", stored.filename(), "test", 0,
        NOW.plusSeconds(1))).orElseThrow();
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

  @Test
  void unchangedProxyInventoryKeepsRevisionStable() {
    long repositoryId = insertRepository("conda-proxy", "proxy");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());
    CondaRegistryDao.PackageRecord record = record(repositoryId, CondaRegistryDao.SOURCE_PROXY);
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

  @Test
  void preferredStreamingQueriesKeepRepositoryPriority() {
    long preferredId = insertRepository("conda-preferred", "hosted");
    long fallbackId = insertRepository("conda-fallback", "hosted");
    CondaRegistryDao dao = new JdbcCondaRegistryDao(jdbc(), jsonColumns(), dialect());
    inTransaction(() -> dao.saveHostedPackage(
        record(preferredId, CondaRegistryDao.SOURCE_HOSTED)));
    inTransaction(() -> dao.saveHostedPackage(
        record(fallbackId, CondaRegistryDao.SOURCE_HOSTED)));

    ArrayList<CondaRegistryDao.PackageRecord> repodata = new ArrayList<>();
    dao.visitPreferredPackages(
        List.of(preferredId, fallbackId), "main", "linux-64", "conda", repodata::add);
    assertEquals(List.of(preferredId),
        repodata.stream().map(CondaRegistryDao.PackageRecord::repositoryId).toList());

    ArrayList<CondaRegistryDao.PackageRecord> channeldata = new ArrayList<>();
    dao.visitPreferredPackagesByChannel(
        List.of(preferredId, fallbackId), "main", channeldata::add);
    assertEquals(List.of(preferredId),
        channeldata.stream().map(CondaRegistryDao.PackageRecord::repositoryId).toList());
  }

  private long insertRepository(String name, String type) {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES (?, 'S3', CAST('{}' AS jsonb))
        """, name + "-store");
    long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = ?", Long.class, name + "-store");
    jdbc().update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES (?, 'conda', ?, ?, ?, CAST('{}' AS jsonb))
        """, name, type, "conda-" + type, blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

  private static CondaRegistryDao.PackageRecord record(long repositoryId, String source) {
    return new CondaRegistryDao.PackageRecord(
        null, repositoryId, "main", "linux-64", "demo-1.0-0.conda",
        "demo", "1.0", "0", 0, "conda", Map.of("depends", List.of("python")),
        "c".repeat(64), "b".repeat(32), "a".repeat(64), 12, null, null, source, 0, NOW, NOW);
  }
}
