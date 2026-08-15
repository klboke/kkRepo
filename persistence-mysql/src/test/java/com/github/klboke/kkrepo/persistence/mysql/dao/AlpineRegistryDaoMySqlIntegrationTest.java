package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class AlpineRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {

  @Test
  void packageSnapshotRelationsBindingsKeysAndFencingAreDurable() {
    long hostedId = insertRepository("alpine-hosted-db", "alpine");
    long groupId = insertRepository("alpine-group-db", "alpine");
    jdbc().update("UPDATE repository SET type = 'group', recipe_name = 'alpine-group' WHERE id = ?",
        groupId);
    AlpineRegistryDao dao = stores().alpineRegistry();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String namespace = "v3.23/main/x86_64";

    AlpineRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.savePackage(record(hostedId, namespace, "1.0-r0", now)));
    assertEquals(1, stored.revision());
    assertEquals(stored, dao.findPackage(
        hostedId, namespace, "main", "demo", "1.0-r0", "x86_64").orElseThrow());
    assertEquals(List.of(stored), dao.listPackagePage(
        hostedId, namespace, "main", "x86_64", "", 0, 10));
    assertEquals(List.of(stored), dao.listPackagePage(hostedId, namespace, "", 0, 10));
    assertEquals(List.of(namespace), dao.listDistributions(hostedId));
    assertEquals(List.of("main"), dao.listComponents(hostedId, namespace));
    assertEquals(List.of("x86_64"), dao.listArchitectures(hostedId, namespace, "main"));
    ArrayList<AlpineRegistryDao.PackageRecord> visited = new ArrayList<>();
    dao.visitPackages(hostedId, namespace, "main", "x86_64", visited::add);
    assertEquals(List.of(stored), visited);
    assertEquals(1L, dao.findSuite(hostedId, namespace).orElseThrow().desiredRevision());
    assertEquals(1, dao.listSuites(hostedId).size());
    assertTrue(dao.listPendingSuites(
            now.plusSeconds(1), now.plusSeconds(1), now.plusSeconds(1), 10).stream()
        .anyMatch(suite -> suite.repositoryId() == hostedId));
    dao.recordBuildFailure(hostedId, namespace, 1L, "fixture failure", now);
    assertEquals("fixture failure", dao.findSuite(hostedId, namespace).orElseThrow().lastError());
    dao.replacePackageRelations(hostedId, stored.id(), List.of(
        new AlpineRegistryDao.PackageRelation(stored.id(), "DEPEND", "musl", "musl>=1.2")));
    assertEquals(List.of(stored), dao.findPackagesByRelation(
        hostedId, "DEPEND", "musl", null, 10));

    String leaseKey = "alpine:publish:" + hostedId + ":" + namespace;
    AlpineRegistryDao.Lease lease = dao.tryAcquireLease(
        leaseKey, "owner-1", now, now.plusSeconds(60)).orElseThrow();
    assertTrue(dao.tryAcquireLease(
        leaseKey, "owner-2", now, now.plusSeconds(60)).isEmpty());
    AlpineRegistryDao.Snapshot snapshot = new AlpineRegistryDao.Snapshot(
        hostedId, namespace, stored.revision(), 1,
        Map.of(namespace + "/APKINDEX.tar.gz", ".alpine/snapshots/1/APKINDEX.tar.gz"),
        "d".repeat(64), now);
    assertFalse(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken() + 1));
    assertTrue(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken()));
    assertEquals(snapshot, dao.findPublishedSnapshot(hostedId, namespace).orElseThrow());
    assertEquals(snapshot, dao.findSnapshot(hostedId, namespace, snapshot.revision()).orElseThrow());
    assertEquals(List.of(snapshot), dao.listSnapshots(hostedId, namespace, 10));
    assertTrue(dao.listSnapshotCleanupCandidates(now.plusSeconds(1), 3, 10).isEmpty());
    assertFalse(dao.deleteSnapshot(hostedId, namespace, snapshot.revision()));
    assertTrue(dao.renewLease(
        leaseKey, "owner-1", lease.fencingToken(), now, now.plusSeconds(120)));
    assertFalse(dao.renewLease(
        leaseKey, "wrong", lease.fencingToken(), now, now.plusSeconds(120)));
    dao.releaseLease(leaseKey, "owner-1", lease.fencingToken());
    AlpineRegistryDao.Lease reacquired = dao.tryAcquireLease(
        leaseKey, "owner-2", now.plusSeconds(1), now.plusSeconds(120)).orElseThrow();
    assertTrue(reacquired.fencingToken() > lease.fencingToken());

    dao.insertSigningKey(key(hostedId, 1, now));
    dao.insertSigningKey(key(hostedId, 2, now.plusSeconds(1)));
    assertEquals(2, dao.findActiveSigningKey(hostedId).orElseThrow().revision());
    assertEquals(1, dao.findSigningKey(hostedId, 1).orElseThrow().revision());
    assertEquals(2, dao.listSigningKeys(hostedId, 10).size());

    dao.ensureSuite(groupId, namespace, now);
    long groupRevision = dao.markSuiteDirty(groupId, namespace, now);
    AlpineRegistryDao.Lease groupLease = dao.tryAcquireLease(
        "alpine:publish:" + groupId + ":" + namespace,
        "group-owner", now, now.plusSeconds(60)).orElseThrow();
    AlpineRegistryDao.Snapshot groupSnapshot = new AlpineRegistryDao.Snapshot(
        groupId, namespace, groupRevision, 1,
        Map.of(namespace + "/APKINDEX.tar.gz", ".alpine/group/APKINDEX.tar.gz"),
        "e".repeat(64), now);
    AlpineRegistryDao.GroupBinding binding = new AlpineRegistryDao.GroupBinding(
        null, groupId, namespace, groupRevision, stored.path(), hostedId,
        snapshot.revision(), stored.path(), stored.identity(), stored.sha256(), stored.size(), now);
    assertFalse(dao.publishGroupSnapshot(
        groupSnapshot, "group-owner", groupLease.fencingToken()));
    dao.beginGroupSnapshot(groupId, namespace, groupRevision, groupLease.fencingToken());
    dao.appendGroupBindings(groupLease.fencingToken(), List.of(binding));
    assertTrue(dao.publishGroupSnapshot(
        groupSnapshot, "group-owner", groupLease.fencingToken()));
    assertEquals(hostedId, dao.findGroupBinding(
        groupId, namespace, groupRevision, stored.path()).orElseThrow().memberRepositoryId());
    assertEquals(1, dao.listGroupBindings(groupId, namespace, groupRevision, null, 10).size());
    assertTrue(dao.listGroupBindings(groupId, namespace, groupRevision, Long.MAX_VALUE, 10)
        .isEmpty());
    String groupLeaseKey = "alpine:publish:" + groupId + ":" + namespace;
    dao.releaseLease(groupLeaseKey, "group-owner", groupLease.fencingToken());
    Instant reacquireAt = Instant.now().plusSeconds(1);
    AlpineRegistryDao.Lease nextGroupLease = dao.tryAcquireLease(
        groupLeaseKey, "group-owner-2", reacquireAt, reacquireAt.plusSeconds(120)).orElseThrow();
    AlpineRegistryDao.GroupBinding duplicateRevision = new AlpineRegistryDao.GroupBinding(
        null, groupId, namespace, groupRevision, stored.path(), hostedId,
        snapshot.revision(), stored.path(), stored.identity(), stored.sha256(), stored.size(), now);
    dao.beginGroupSnapshot(
        groupId, namespace, groupRevision, nextGroupLease.fencingToken());
    dao.appendGroupBindings(nextGroupLease.fencingToken(), List.of(duplicateRevision));
    assertFalse(dao.publishGroupSnapshot(
        groupSnapshot, "group-owner-2", nextGroupLease.fencingToken()));
    assertEquals(0, jdbc().queryForObject(
        """
        SELECT COUNT(*) FROM alpine_group_binding
        WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
          AND binding_token = ?
        """,
        Integer.class,
        groupId,
        namespace,
        groupRevision,
        nextGroupLease.fencingToken()));
    assertEquals(groupLease.fencingToken(), jdbc().queryForObject(
        """
        SELECT group_binding_token FROM alpine_snapshot
        WHERE repository_id = ? AND distribution_name = ? AND revision = ?
        """,
        Long.class,
        groupId,
        namespace,
        groupRevision));
    assertThrows(IllegalStateException.class, () -> dao.beginGroupSnapshot(
        groupId, namespace, groupRevision + 1, groupLease.fencingToken()));
    dao.beginGroupSnapshot(
        groupId, namespace, groupRevision + 1, nextGroupLease.fencingToken());
    AlpineRegistryDao.GroupBinding staged = new AlpineRegistryDao.GroupBinding(
        null, groupId, namespace, groupRevision + 1, stored.path(), hostedId,
        snapshot.revision(), stored.path(), stored.identity(), stored.sha256(), stored.size(), now);
    assertThrows(IllegalStateException.class, () -> dao.appendGroupBindings(
        groupLease.fencingToken(), List.of(staged)));
    dao.appendGroupBindings(nextGroupLease.fencingToken(), List.of(staged));
    dao.discardGroupSnapshot(
        groupId, namespace, groupRevision + 1, groupLease.fencingToken());
    assertEquals(1, jdbc().queryForObject(
        """
        SELECT COUNT(*) FROM alpine_group_binding
        WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
          AND binding_token = ?
        """,
        Integer.class,
        groupId,
        namespace,
        groupRevision + 1,
        nextGroupLease.fencingToken()));
    assertEquals(1, dao.deleteOrphanGroupBindings(Instant.now().plusSeconds(1), 10));

    dao.observeProxyDistribution(
        hostedId,
        namespace,
        "release-1",
        Map.of(stored.path(), new AlpineRegistryDao.ProxyIndex(stored.sha256(), stored.size())),
        true,
        now);
    AlpineRegistryDao.ProxyDistribution observed =
        dao.findProxyDistribution(hostedId, namespace).orElseThrow();
    assertTrue(observed.signatureVerified());
    assertEquals(stored.sha256(), observed.indices().get(stored.path()).sha256());
    dao.observeProxyDistribution(hostedId, "edge/main/x86_64", "release-2", now);
    assertEquals(2, dao.listProxyDistributions(hostedId).size());

    assertThrows(DataIntegrityViolationException.class, () -> jdbc().update(
        "UPDATE alpine_signing_key SET active = true WHERE repository_id = ? AND revision = 1",
        hostedId));
    assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() -> dao.savePackage(
        recordAtPath(hostedId, namespace, "2.0-r0", stored.path(), now.plusSeconds(2)))));

    AlpineRegistryDao.PackageRecord deleted = inTransaction(() -> dao.deletePackage(
        hostedId, namespace, "main", "demo", "1.0-r0", "x86_64", "test", now.plusSeconds(3)))
        .orElseThrow();
    assertEquals(stored.id(), deleted.id());
    assertTrue(dao.findPackageByPath(hostedId, stored.path()).isEmpty());

    long tombstoneRepository = insertRepository("alpine-tombstone-db", "alpine");
    AlpineRegistryDao.PackageRecord tombstonePackage = inTransaction(
        () -> dao.savePackage(record(tombstoneRepository, namespace, "3.0-r0", now)));
    inTransaction(() -> dao.deletePackage(
        tombstoneRepository, namespace, "main", "demo", "3.0-r0", "x86_64", "cleanup", now));
    AlpineRegistryDao.PackageTombstone tombstone = dao.listPackageCleanupCandidates(
        now.plusSeconds(1), 10).stream()
        .filter(candidate -> candidate.repositoryId() == tombstoneRepository)
        .findFirst().orElseThrow();
    assertFalse(dao.deletePackageTombstone(null));
    assertTrue(dao.deletePackageTombstone(tombstone));
    assertTrue(dao.listPackageCleanupCandidates(now.plusSeconds(1), 10).stream()
        .noneMatch(candidate -> candidate.repositoryId() == tombstoneRepository));
    dao.deleteRepositoryState(tombstoneRepository);
    assertTrue(dao.listSuites(tombstoneRepository).isEmpty());
  }

  @Test
  void snapshotRetentionUsesBoundedCandidatesAndDeletesPublishedGroupBindings() {
    long memberId = insertRepository("alpine-retention-member", "alpine");
    long groupId = insertRepository("alpine-retention-group", "alpine");
    jdbc().update("UPDATE repository SET type = 'group', recipe_name = 'alpine-group' WHERE id = ?",
        groupId);
    AlpineRegistryDao dao = stores().alpineRegistry();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant createdAt = now.minusSeconds(30);
    String namespace = "v3.23/main/x86_64";
    AlpineRegistryDao.PackageRecord member = inTransaction(
        () -> dao.savePackage(record(memberId, namespace, "1.0-r0", now)));
    String leaseKey = "alpine:publish:" + groupId + ":" + namespace;
    AlpineRegistryDao.Lease lease = dao.tryAcquireLease(
        leaseKey, "retention-owner", now, now.plusSeconds(300)).orElseThrow();
    ArrayList<Long> revisions = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      long revision = dao.markSuiteDirty(groupId, namespace, now.plusMillis(index));
      revisions.add(revision);
      AlpineRegistryDao.Snapshot snapshot = new AlpineRegistryDao.Snapshot(
          groupId,
          namespace,
          revision,
          1,
          Map.of(
              namespace + "/APKINDEX.tar.gz",
              ".alpine/snapshots/retention/" + revision + "/APKINDEX.tar.gz"),
          "%064x".formatted(index + 1),
          createdAt.plusSeconds(index));
      dao.beginGroupSnapshot(groupId, namespace, revision, lease.fencingToken());
      dao.appendGroupBindings(lease.fencingToken(), List.of(new AlpineRegistryDao.GroupBinding(
          null,
          groupId,
          namespace,
          revision,
          member.path(),
          memberId,
          member.revision(),
          member.path(),
          member.identity(),
          member.sha256(),
          member.size(),
          createdAt.plusSeconds(index))));
      assertTrue(dao.publishGroupSnapshot(snapshot, "retention-owner", lease.fencingToken()));
    }

    List<Long> candidates = dao.listSnapshotCleanupCandidates(now, 3, 256).stream()
        .filter(candidate -> candidate.repositoryId() == groupId)
        .map(AlpineRegistryDao.Snapshot::revision)
        .toList();
    assertEquals(revisions.subList(0, 2), candidates);
    assertEquals(1, dao.listGroupBindings(
        groupId, namespace, revisions.getFirst(), null, 10).size());
    assertTrue(dao.deleteSnapshot(groupId, namespace, revisions.getFirst()));
    assertEquals(0, jdbc().queryForObject(
        """
        SELECT COUNT(*) FROM alpine_group_binding
        WHERE group_repository_id = ? AND distribution_name = ? AND snapshot_revision = ?
        """,
        Integer.class,
        groupId,
        namespace,
        revisions.getFirst()));
  }

  @Test
  void accessShapesHaveRepositoryLeadingIndexesAndIndexedPlans() {
    assertEquals(List.of("repository_id", "coordinate_hash"),
        indexColumns("alpine_package_record", "uk_alpine_package_coordinate"));
    assertEquals(List.of("repository_id", "asset_path_hash"),
        indexColumns("alpine_package_record", "uk_alpine_package_path"));
    assertEquals(List.of(
        "repository_id", "distribution_name", "component_name", "architecture", "package_name", "id"),
        indexColumns("alpine_package_record", "idx_alpine_package_index"));
    assertEquals(List.of("repository_id", "relation_kind", "token_hash", "package_id"),
        indexColumns("alpine_package_relation", "idx_alpine_relation_lookup"));
    assertEquals(List.of(
        "group_repository_id", "distribution_name", "snapshot_revision", "binding_token", "id"),
        indexColumns("alpine_group_binding", "idx_alpine_group_page"));
    assertEquals(List.of(
        "created_at", "group_repository_id", "distribution_name", "snapshot_revision",
        "binding_token"),
        indexColumns("alpine_group_snapshot_stage", "idx_alpine_group_stage_cleanup"));
    assertEquals(List.of(
        "publish_pending", "desired_at", "repository_id", "distribution_name"),
        indexColumns("alpine_suite_state", "idx_alpine_suite_worker"));
    assertEquals(List.of(
        "created_at", "repository_id", "distribution_name", "revision"),
        indexColumns("alpine_snapshot", "idx_alpine_snapshot_cleanup"));

    long repositoryId = insertRepository("alpine-plan", "alpine");
    inTransaction(() -> {
      jdbc().execute("SET SESSION cte_max_recursion_depth = 4096");
      return jdbc().update("""
          INSERT INTO alpine_package_record
            (repository_id, coordinate_hash, distribution_name, component_name, architecture,
             package_name, package_version, package_architecture, filename, asset_path,
             asset_path_hash, control_fields, package_identity, data_sha256, sha256, size_bytes, source_kind,
             revision, indexed_at, created_at, updated_at)
          WITH RECURSIVE sequence_value(n) AS (
            SELECT 1
            UNION ALL
            SELECT n + 1 FROM sequence_value WHERE n < 2048
          )
          SELECT ?, UNHEX(SHA2(CONCAT('alpine-plan-', n), 256)),
                 'v3.23/main/x86_64', 'main', 'x86_64', CONCAT('plan-', n), '1.0-r0',
                 'x86_64', CONCAT('plan-', n, '-1.0-r0.apk'),
                 CONCAT('v3.23/main/x86_64/plan-', n, '-1.0-r0.apk'),
                 UNHEX(SHA2(CONCAT('v3.23/main/x86_64/plan-', n, '-1.0-r0.apk'), 256)),
                 JSON_OBJECT('P', CONCAT('plan-', n), 'V', '1.0-r0'),
                 CONCAT('Q1', LPAD(n, 27, 'A'), '='), REPEAT('a', 64), REPEAT('b', 64),
                 12, 'HOSTED', n, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
          FROM sequence_value
          """, repositoryId);
    });
    jdbc().update("""
        INSERT INTO alpine_package_relation
          (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
        SELECT repository_id, id, 'PROVIDE', 'cmd:fixture',
               UNHEX(SHA2('cmd:fixture', 256)), 'cmd:fixture'
        FROM alpine_package_record WHERE repository_id = ?
        """, repositoryId);
    jdbc().execute("ANALYZE TABLE alpine_package_record, alpine_package_relation");

    String exactPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT * FROM alpine_package_record
        WHERE repository_id = ? AND coordinate_hash = UNHEX(SHA2('alpine-plan-1024', 256))
        """, String.class, repositoryId);
    assertTrue(exactPlan.contains("uk_alpine_package_coordinate"), exactPlan);
    String indexPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT * FROM alpine_package_record
        WHERE repository_id = ? AND distribution_name = 'v3.23/main/x86_64'
          AND component_name = 'main' AND architecture = 'x86_64'
          AND (package_name > '' OR (package_name = '' AND id > 0))
        ORDER BY package_name, id
        LIMIT 20
        """, String.class, repositoryId);
    assertTrue(indexPlan.contains("idx_alpine_package_index"), indexPlan);
    String relationPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT package_row.* FROM alpine_package_relation relation_row
        JOIN alpine_package_record package_row ON package_row.id = relation_row.package_id
        WHERE relation_row.repository_id = ? AND package_row.repository_id = ?
          AND relation_row.relation_kind = 'PROVIDE'
          AND relation_row.token_hash = UNHEX(SHA2('cmd:fixture', 256))
          AND relation_row.token_value = 'cmd:fixture' AND package_row.id > 0
        ORDER BY package_row.id LIMIT 20
        """, String.class, repositoryId, repositoryId);
    assertTrue(relationPlan.contains("idx_alpine_relation_lookup"), relationPlan);
  }

  private List<String> indexColumns(String table, String index) {
    return jdbc().queryForList("""
        SELECT column_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
        ORDER BY seq_in_index
        """, String.class, table, index);
  }

  private static AlpineRegistryDao.SigningKey key(
      long repositoryId, int revision, Instant now) {
    return new AlpineRegistryDao.SigningKey(
        repositoryId, revision, "fixture.rsa.pub", "f".repeat(64),
        "encrypted", "public", "RSA256", true, now);
  }

  private static AlpineRegistryDao.PackageRecord record(
      long repositoryId, String namespace, String version, Instant now) {
    return recordAtPath(repositoryId, namespace, version,
        namespace + "/demo-" + version + ".apk", now);
  }

  private static AlpineRegistryDao.PackageRecord recordAtPath(
      long repositoryId, String namespace, String version, String path, Instant now) {
    return new AlpineRegistryDao.PackageRecord(
        null, repositoryId, namespace, "main", "x86_64", "demo", version, "x86_64",
        "demo-" + version + ".apk", path,
        Map.of("C", "Q1" + "A".repeat(27) + "=", "P", "demo", "V", version,
            "A", "x86_64", "S", "12", "I", "12"),
        "Q1" + "A".repeat(27) + "=", "a".repeat(64), "b".repeat(64), 12,
        null, null, AlpineRegistryDao.SOURCE_HOSTED, 0, now, now, now);
  }
}
