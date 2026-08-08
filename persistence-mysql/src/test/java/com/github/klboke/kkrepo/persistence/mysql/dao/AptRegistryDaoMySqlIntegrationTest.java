package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AptRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void packageSnapshotKeyProxyAndFencedLeaseLifecycleIsDurable() {
    long repositoryId = insertRepository("apt-hosted", "apt");
    AptRegistryDao dao = stores().aptRegistry();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

    AptRegistryDao.PackageRecord stored = inTransaction(() -> dao.savePackage(packageRecord(
        repositoryId, "amd64", "1.0", AptRegistryDao.SOURCE_HOSTED, now)));
    assertEquals(1, stored.revision());
    assertEquals(stored, dao.findPackage(
        repositoryId, "stable", "main", "demo", "1.0", "amd64").orElseThrow());
    assertEquals(List.of(stored), dao.listPackages(repositoryId, "stable"));
    assertEquals(List.of("stable"), dao.listDistributions(repositoryId));
    assertEquals(List.of("main"), dao.listComponents(repositoryId, "stable"));
    assertEquals(List.of("amd64"), dao.listArchitectures(repositoryId, "stable", "main"));
    assertEquals(1, dao.listSuites(repositoryId).size());

    AptRegistryDao.SuiteState pending = dao.findSuite(repositoryId, "stable").orElseThrow();
    assertEquals(1, pending.desiredRevision());
    assertEquals(0, pending.publishedRevision());
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
    assertTrue(dao.publishSnapshot(snapshot, "first", first.fencingToken()));
    assertThrows(IllegalStateException.class, () -> dao.publishSnapshot(
        new AptRegistryDao.Snapshot(
            repositoryId, "stable", 1, 1,
            Map.of("dists/stable/Release", ".apt/different"), "f".repeat(64), now),
        "first", first.fencingToken()));
    assertThrows(IllegalArgumentException.class,
        () -> dao.publishSnapshot(null, "first", first.fencingToken()));
    assertThrows(IllegalArgumentException.class, () -> dao.publishSnapshot(
        new AptRegistryDao.Snapshot(repositoryId, "stable", 2, 1, Map.of(), "a", now),
        "first", first.fencingToken()));
    assertEquals(snapshot, dao.findPublishedSnapshot(repositoryId, "stable").orElseThrow());
    assertEquals(List.of(snapshot), dao.listSnapshots(repositoryId, "stable", 2));
    assertEquals(snapshot, dao.findSnapshot(repositoryId, "stable", 1).orElseThrow());
    assertEquals(List.of(snapshot), dao.listSnapshots(repositoryId, "stable", 0));
    assertEquals(List.of(snapshot), dao.listSnapshots(repositoryId, "stable", 1000));
    assertTrue(dao.renewLease(
        leaseKey, "first", first.fencingToken(), now, now.plusSeconds(120)));

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
    assertThrows(IllegalArgumentException.class,
        () -> dao.tryAcquireLease("invalid", "owner", now, now));
    assertThrows(IllegalArgumentException.class,
        () -> dao.tryAcquireLease("", "owner", now, now.plusSeconds(1)));
    assertThrows(IllegalArgumentException.class,
        () -> dao.tryAcquireLease("valid", "", now, now.plusSeconds(1)));

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

    AptRegistryDao.PackageRecord all = inTransaction(() -> dao.savePackage(packageRecord(
        repositoryId, "all", "2.0", AptRegistryDao.SOURCE_HOSTED, now.plusSeconds(2))));
    assertEquals(expiredRevision + 1, all.revision());
    assertEquals(1, dao.listPackages(repositoryId, "stable", "main", "amd64").size());
    assertEquals(List.of(all), dao.listPackages(repositoryId, "stable", "main", "all"));
    assertEquals(1, dao.findSuite(repositoryId, "stable").orElseThrow().publishedRevision());

    dao.insertSigningKey(new AptRegistryDao.SigningKey(
        repositoryId, 1, "ABCD", "F".repeat(40), "encrypted", "public", true, now));
    assertEquals("ABCD", dao.findActiveSigningKey(repositoryId).orElseThrow().keyId());
    assertEquals("ABCD", dao.findSigningKey(repositoryId, 1).orElseThrow().keyId());
    dao.insertSigningKey(new AptRegistryDao.SigningKey(
        repositoryId, 2, "EFGH", "E".repeat(40), "encrypted-2", "public-2", true,
        now.plusSeconds(1)));
    assertEquals("EFGH", dao.findActiveSigningKey(repositoryId).orElseThrow().keyId());
    assertEquals(2, dao.listSigningKeys(repositoryId, 100).size());
    Map<String, AptRegistryDao.ProxyIndex> indices = Map.of(
        "dists/testing/main/binary-amd64/Packages.gz",
        new AptRegistryDao.ProxyIndex("9".repeat(64), 123L));
    dao.observeProxyDistribution(
        repositoryId, "testing", "release-1", indices, true, now);
    AptRegistryDao.ProxyDistribution proxy =
        dao.findProxyDistribution(repositoryId, "testing").orElseThrow();
    assertEquals(indices, proxy.indices());
    assertTrue(proxy.signatureVerified());
    dao.observeProxyDistribution(
        repositoryId, "testing", "release-2", null, false, null);
    assertEquals("release-2",
        dao.listProxyDistributions(repositoryId).getFirst().releaseIdentity());

    long failedRevision = dao.markSuiteDirty(repositoryId, "broken", null);
    dao.recordBuildFailure(repositoryId, "broken", failedRevision, "x".repeat(3000), null);
    assertEquals(2048, dao.findSuite(repositoryId, "broken").orElseThrow().lastError().length());
    dao.recordBuildFailure(repositoryId, "broken", failedRevision, null, now);
    assertEquals(null, dao.findSuite(repositoryId, "broken").orElseThrow().lastError());

    AptRegistryDao.PackageRecord removed = inTransaction(() -> dao.deletePackage(
        repositoryId, "stable", "main", "demo", "1.0", "amd64", "test", now.plusSeconds(3)))
        .orElseThrow();
    assertEquals(stored.id(), removed.id());
    assertTrue(dao.findPackageByPath(repositoryId, stored.path()).isEmpty());
    assertEquals(failedRevision + 1,
        dao.findSuite(repositoryId, "stable").orElseThrow().desiredRevision());
    assertTrue(inTransaction(() -> dao.deletePackage(
        repositoryId, "stable", "main", "missing", "1.0", "amd64", "test", now))
        .isEmpty());

    assertThrows(IllegalArgumentException.class, () -> dao.savePackage(null));
    AptRegistryDao.PackageRecord invalidChecksum = packageRecord(
        repositoryId, "amd64", "bad", AptRegistryDao.SOURCE_HOSTED, now);
    invalidChecksum = new AptRegistryDao.PackageRecord(
        invalidChecksum.id(), invalidChecksum.repositoryId(), invalidChecksum.distribution(),
        invalidChecksum.component(), invalidChecksum.architecture(), invalidChecksum.packageName(),
        invalidChecksum.version(), invalidChecksum.sourcePackage(), invalidChecksum.filename(),
        invalidChecksum.path(), invalidChecksum.controlFields(), invalidChecksum.md5(),
        invalidChecksum.sha1(), "short", invalidChecksum.size(), invalidChecksum.assetId(),
        invalidChecksum.componentId(), invalidChecksum.sourceKind(), invalidChecksum.revision(),
        invalidChecksum.indexedAt(), invalidChecksum.createdAt(), invalidChecksum.updatedAt());
    AptRegistryDao.PackageRecord badChecksum = invalidChecksum;
    assertThrows(IllegalArgumentException.class, () -> dao.savePackage(badChecksum));
    AptRegistryDao.PackageRecord invalidSource = packageRecord(
        repositoryId, "amd64", "bad-source", "UNKNOWN", now);
    assertThrows(IllegalArgumentException.class, () -> dao.savePackage(invalidSource));

    dao.deleteRepositoryState(repositoryId);
    assertTrue(dao.listSuites(repositoryId).isEmpty());
    assertTrue(dao.listSigningKeys(repositoryId, 2).isEmpty());
    assertTrue(dao.listProxyDistributions(repositoryId).isEmpty());
  }

  private static AptRegistryDao.PackageRecord packageRecord(
      long repositoryId, String architecture, String version, String source, Instant now) {
    return new AptRegistryDao.PackageRecord(
        null, repositoryId, "stable", "main", architecture, "demo", version, "demo",
        "demo_" + version + "_" + architecture + ".deb",
        "pool/d/demo/demo_" + version + "_" + architecture + ".deb",
        Map.of(
            "Package", "demo", "Version", version, "Architecture", architecture,
            "Maintainer", "Test <test@example.invalid>", "Description", "demo"),
        "a".repeat(32), "b".repeat(40), "c".repeat(64), 12,
        null, null, source, 0, now, now, now);
  }
}
