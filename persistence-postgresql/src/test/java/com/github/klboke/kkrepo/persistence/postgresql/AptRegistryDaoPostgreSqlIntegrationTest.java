package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AptRegistryDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void packageSnapshotAndFencedLeaseLifecycleIsDurable() {
    long repositoryId = insertRepository("apt-hosted");
    AptRegistryDao dao = stores().aptRegistry();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    AptRegistryDao.PackageRecord stored = inTransaction(() -> dao.savePackage(
        packageRecord(repositoryId, "amd64", "1.0", now)));
    assertEquals(1, stored.revision());
    assertEquals(List.of("stable"), dao.listDistributions(repositoryId));
    String leaseKey = "apt:publish:" + repositoryId + ":stable";
    AptRegistryDao.Lease first = dao.tryAcquireLease(
        leaseKey, "first", now, now.plusSeconds(60)).orElseThrow();
    assertTrue(dao.tryAcquireLease(
        leaseKey, "second", now, now.plusSeconds(60)).isEmpty());
    AptRegistryDao.Snapshot snapshot = new AptRegistryDao.Snapshot(
        repositoryId, "stable", 1, 1,
        Map.of("dists/stable/Release", ".apt/snapshots/stable/1/Release"),
        "a".repeat(64), now);
    assertFalse(dao.publishSnapshot(snapshot, "first", first.fencingToken() + 1));
    assertTrue(dao.publishSnapshot(snapshot, "first", first.fencingToken()));
    assertEquals(snapshot, dao.findPublishedSnapshot(repositoryId, "stable").orElseThrow());
    assertEquals(List.of(snapshot), dao.listSnapshots(repositoryId, "stable", 2));

    long staleRevision = dao.markSuiteDirty(repositoryId, "racing", now);
    String racingLeaseKey = "apt:publish:" + repositoryId + ":racing";
    AptRegistryDao.Lease racingLease = dao.tryAcquireLease(
        racingLeaseKey, "racing-owner", now, now.plusSeconds(60)).orElseThrow();
    dao.markSuiteDirty(repositoryId, "racing", now.plusMillis(1));
    AptRegistryDao.Snapshot staleSnapshot = new AptRegistryDao.Snapshot(
        repositoryId, "racing", staleRevision, 1,
        Map.of("dists/racing/Release", ".apt/snapshots/racing/1/Release"),
        "e".repeat(64), now);
    assertFalse(dao.publishSnapshot(
        staleSnapshot, "racing-owner", racingLease.fencingToken()));
    assertTrue(dao.listSnapshots(repositoryId, "racing", 2).isEmpty());

    dao.releaseLease(leaseKey, "first", first.fencingToken());
    AptRegistryDao.Lease second = dao.tryAcquireLease(
        leaseKey, "second", now.plusSeconds(1), now.plusSeconds(61)).orElseThrow();
    assertTrue(second.fencingToken() > first.fencingToken());
    assertFalse(dao.renewLease(
        leaseKey, "first", first.fencingToken(), now.plusSeconds(1), now.plusSeconds(62)));

    long expiredRevision = dao.markSuiteDirty(repositoryId, "expired", now.minusSeconds(120));
    String expiredLeaseKey = "apt:publish:" + repositoryId + ":expired";
    AptRegistryDao.Lease expired = dao.tryAcquireLease(
        expiredLeaseKey, "expired-owner", now.minusSeconds(120), now.minusSeconds(60))
        .orElseThrow();
    AptRegistryDao.Snapshot expiredSnapshot = new AptRegistryDao.Snapshot(
        repositoryId, "expired", expiredRevision, 1,
        Map.of("dists/expired/Release", ".apt/snapshots/expired/1/Release"),
        "d".repeat(64), now.minusSeconds(120));
    assertFalse(dao.publishSnapshot(expiredSnapshot, "expired-owner", expired.fencingToken()));

    AptRegistryDao.PackageRecord all = inTransaction(() -> dao.savePackage(
        packageRecord(repositoryId, "all", "2.0", now.plusSeconds(2))));
    assertEquals(1, dao.listPackages(repositoryId, "stable", "main", "amd64").size());
    assertEquals(List.of(all), dao.listPackages(repositoryId, "stable", "main", "all"));
    dao.insertSigningKey(new AptRegistryDao.SigningKey(
        repositoryId, 1, "ABCD", "F".repeat(40), "encrypted", "public", true, now));
    assertEquals(1, dao.listSigningKeys(repositoryId, 2).size());
    inTransaction(() -> dao.deletePackage(
        repositoryId, "stable", "main", "demo", "1.0", "amd64", "test", now.plusSeconds(3)));
    assertTrue(dao.findPackageByPath(repositoryId, stored.path()).isEmpty());
    assertEquals(all.revision() + 1,
        dao.findSuite(repositoryId, "stable").orElseThrow().desiredRevision());
  }

  private long insertRepository(String name) {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES (?, 'S3', CAST('{}' AS jsonb))
        """, name + "-store");
    long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = ?", Long.class, name + "-store");
    jdbc().update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES (?, 'apt', 'hosted', 'apt-hosted', ?, CAST('{}' AS jsonb))
        """, name, blobStoreId);
    return jdbc().queryForObject("SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

  private static AptRegistryDao.PackageRecord packageRecord(
      long repositoryId, String architecture, String version, Instant now) {
    return new AptRegistryDao.PackageRecord(
        null, repositoryId, "stable", "main", architecture, "demo", version, "demo",
        "demo_" + version + "_" + architecture + ".deb",
        "pool/d/demo/demo_" + version + "_" + architecture + ".deb",
        Map.of(
            "Package", "demo", "Version", version, "Architecture", architecture,
            "Maintainer", "Test <test@example.invalid>", "Description", "demo"),
        "a".repeat(32), "b".repeat(40), "c".repeat(64), 12,
        null, null, AptRegistryDao.SOURCE_HOSTED, 0, now, now, now);
  }
}
