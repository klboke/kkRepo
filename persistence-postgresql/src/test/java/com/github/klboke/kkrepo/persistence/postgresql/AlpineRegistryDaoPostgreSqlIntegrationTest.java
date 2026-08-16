package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class AlpineRegistryDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {

  @Test
  void packageSnapshotRelationsKeysAndFencingAreDurable() {
    long repositoryId = insertRepository("alpine-hosted-db");
    AlpineRegistryDao dao = stores().alpineRegistry();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    String namespace = "v3.23/main/x86_64";

    AlpineRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.savePackage(record(repositoryId, namespace, "1.0-r0", null, now)));
    assertEquals(stored, dao.findPackageByPath(repositoryId, stored.path()).orElseThrow());
    assertEquals(List.of(stored), dao.listPackagePage(
        repositoryId, namespace, "main", "x86_64", "", 0, 10));
    assertEquals(List.of(stored), dao.listPackagePage(repositoryId, namespace, "", 0, 10));
    assertTrue(dao.listPendingSuites(
            now.plusSeconds(1), now.plusSeconds(1), now.plusSeconds(1), 10).stream()
        .anyMatch(suite -> suite.repositoryId() == repositoryId));
    dao.replacePackageRelations(repositoryId, stored.id(), List.of(
        new AlpineRegistryDao.PackageRelation(stored.id(), "PROVIDE", "cmd:demo", "cmd:demo=1")));
    assertEquals(List.of(stored), dao.findPackagesByRelation(
        repositoryId, "PROVIDE", "cmd:demo", null, 10));

    String leaseKey = "alpine:publish:" + repositoryId + ":" + namespace;
    AlpineRegistryDao.Lease lease = dao.tryAcquireLease(
        leaseKey, "owner-1", now, now.plusSeconds(60)).orElseThrow();
    AlpineRegistryDao.Snapshot snapshot = new AlpineRegistryDao.Snapshot(
        repositoryId, namespace, stored.revision(), 1,
        Map.of(namespace + "/APKINDEX.tar.gz", ".alpine/snapshots/1/APKINDEX.tar.gz"),
        "d".repeat(64), now);
    assertFalse(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken() + 1));
    assertTrue(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken()));
    assertEquals(snapshot, dao.findPublishedSnapshot(repositoryId, namespace).orElseThrow());

    dao.insertSigningKey(key(repositoryId, 1, now));
    dao.insertSigningKey(key(repositoryId, 2, now.plusSeconds(1)));
    assertEquals(2, dao.findActiveSigningKey(repositoryId).orElseThrow().revision());
    assertThrows(DataIntegrityViolationException.class, () -> jdbc().update(
        "UPDATE alpine_signing_key SET active = true WHERE repository_id = ? AND revision = 1",
        repositoryId));
    assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() -> dao.savePackage(
        record(repositoryId, namespace, "2.0-r0", stored.path(), now.plusSeconds(2)))));

    inTransaction(() -> dao.deletePackage(
        repositoryId, namespace, "main", "demo", "1.0-r0", "x86_64", "test", now));
    assertTrue(dao.findPackageByPath(repositoryId, stored.path()).isEmpty());
  }

  @Test
  void snapshotRetentionAndStagedGroupBindingsArePortable() {
    long memberId = insertRepository("alpine-retention-member-pg");
    long groupId = insertRepository("alpine-retention-group-pg");
    jdbc().update("UPDATE repository SET type = 'group', recipe_name = 'alpine-group' WHERE id = ?",
        groupId);
    AlpineRegistryDao dao = stores().alpineRegistry();
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant createdAt = now.minusSeconds(30);
    String namespace = "v3.23/main/x86_64";
    AlpineRegistryDao.PackageRecord member = inTransaction(
        () -> dao.savePackage(record(memberId, namespace, "1.0-r0", null, now)));
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
    long orphanRevision = revisions.getLast() + 1;
    dao.beginGroupSnapshot(groupId, namespace, orphanRevision, lease.fencingToken());
    dao.appendGroupBindings(lease.fencingToken(), List.of(new AlpineRegistryDao.GroupBinding(
        null,
        groupId,
        namespace,
        orphanRevision,
        member.path(),
        memberId,
        member.revision(),
        member.path(),
        member.identity(),
        member.sha256(),
        member.size(),
        createdAt)));
    assertEquals(1, dao.deleteOrphanGroupBindings(Instant.now().plusSeconds(1), 10));

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
    assertTrue(indexDefinition("uk_alpine_package_coordinate")
        .contains("(repository_id, coordinate_hash)"));
    assertTrue(indexDefinition("uk_alpine_package_path")
        .contains("(repository_id, asset_path_hash)"));
    assertTrue(indexDefinition("idx_alpine_package_index").contains(
        "(repository_id, distribution_name, component_name, architecture, package_name, id)"));
    assertTrue(indexDefinition("idx_alpine_relation_lookup")
        .contains("(repository_id, relation_kind, token_hash, package_id)"));
    assertTrue(indexDefinition("idx_alpine_group_page").contains(
        "(group_repository_id, distribution_name, snapshot_revision, binding_token, id)"));
    assertTrue(indexDefinition("idx_alpine_group_stage_cleanup").contains(
        "(created_at, group_repository_id, distribution_name, snapshot_revision, binding_token)"));
    assertTrue(indexDefinition("idx_alpine_suite_worker").contains(
        "(publish_pending, desired_at, repository_id, distribution_name)"));
    assertTrue(indexDefinition("idx_alpine_snapshot_cleanup").contains(
        "(created_at, repository_id, distribution_name, revision)"));

    long repositoryId = insertRepository("alpine-plan");
    jdbc().update("""
        INSERT INTO alpine_package_record
          (repository_id, coordinate_hash, distribution_name, component_name, architecture,
           package_name, package_version, package_architecture, filename, asset_path,
           asset_path_hash, control_fields, package_identity, data_sha256, sha256, size_bytes, source_kind,
           revision, indexed_at, created_at, updated_at)
        SELECT ?, decode(md5('alpine-plan-' || n::text) || md5('coordinate-' || n::text), 'hex'),
               'v3.23/main/x86_64',
               CASE WHEN n % 2 = 0 THEN 'main' ELSE 'community' END,
               CASE WHEN n % 3 = 0 THEN 'aarch64' ELSE 'x86_64' END,
               'plan-' || n::text, '1.0-r0',
               'x86_64', 'plan-' || n::text || '-1.0-r0.apk',
               'v3.23/main/x86_64/plan-' || n::text || '-1.0-r0.apk',
               decode(md5('v3.23/main/x86_64/plan-' || n::text || '-1.0-r0.apk')
                 || md5('path-' || n::text), 'hex'),
               jsonb_build_object('P', 'plan-' || n::text, 'V', '1.0-r0'),
               'Q1' || lpad(n::text, 27, 'A') || '=', repeat('a', 64), repeat('b', 64),
               12, 'HOSTED', n, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM generate_series(1, 2048) AS n
        """, repositoryId);
    jdbc().update("""
        INSERT INTO alpine_package_relation
          (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
        SELECT repository_id, id, 'PROVIDE',
               CASE WHEN id % 16 = 0 THEN 'cmd:fixture' ELSE 'cmd:other' END,
               decode(
                 md5(CASE WHEN id % 16 = 0 THEN 'cmd:fixture' ELSE 'cmd:other' END)
                   || md5('token'),
                 'hex'),
               CASE WHEN id % 16 = 0 THEN 'cmd:fixture' ELSE 'cmd:other' END
        FROM alpine_package_record WHERE repository_id = ?
        """, repositoryId);
    jdbc().execute("ANALYZE alpine_package_record");
    jdbc().execute("ANALYZE alpine_package_relation");

    String exactPlan = indexedPlan("""
        EXPLAIN SELECT * FROM alpine_package_record
        WHERE repository_id = ?
          AND coordinate_hash = decode(md5('alpine-plan-1024') || md5('coordinate-1024'), 'hex')
        """, repositoryId);
    assertTrue(exactPlan.contains("uk_alpine_package_coordinate"), exactPlan);
    String indexPlan = indexedPlan("""
        EXPLAIN SELECT * FROM alpine_package_record
        WHERE repository_id = ? AND distribution_name = 'v3.23/main/x86_64'
          AND component_name = 'main' AND architecture = 'x86_64'
          AND (package_name, id) > ('', 0)
        ORDER BY package_name, id
        LIMIT 20
        """, repositoryId);
    assertTrue(indexPlan.contains("idx_alpine_package_index"), indexPlan);
    String relationPlan = indexedPlan("""
        EXPLAIN SELECT package_row.* FROM alpine_package_relation relation_row
        JOIN alpine_package_record package_row ON package_row.id = relation_row.package_id
        WHERE relation_row.repository_id = ? AND package_row.repository_id = ?
          AND relation_row.relation_kind = 'PROVIDE'
          AND relation_row.token_hash = decode(md5('cmd:fixture') || md5('token'), 'hex')
          AND relation_row.token_value = 'cmd:fixture' AND package_row.id > 0
        ORDER BY package_row.id LIMIT 20
        """, repositoryId, repositoryId);
    assertTrue(relationPlan.contains("idx_alpine_relation_lookup"), relationPlan);
  }

  private String indexedPlan(String sql, Object... args) {
    return inTransaction(() -> {
      jdbc().execute("SET LOCAL enable_seqscan = off");
      return String.join("\n", jdbc().queryForList(sql, String.class, args));
    });
  }

  private String indexDefinition(String index) {
    return jdbc().queryForObject("""
        SELECT indexdef FROM pg_indexes
        WHERE schemaname = current_schema() AND indexname = ?
        """, String.class, index);
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
        VALUES (?, 'alpine', 'hosted', 'alpine-hosted', ?, CAST('{}' AS jsonb))
        """, name, blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

  private static AlpineRegistryDao.SigningKey key(
      long repositoryId, int revision, Instant now) {
    return new AlpineRegistryDao.SigningKey(
        repositoryId, revision, "fixture.rsa.pub", "f".repeat(64),
        "encrypted", "public", "RSA256", true, now);
  }

  private static AlpineRegistryDao.PackageRecord record(
      long repositoryId, String namespace, String version, String explicitPath, Instant now) {
    String path = explicitPath == null
        ? namespace + "/demo-" + version + ".apk" : explicitPath;
    return new AlpineRegistryDao.PackageRecord(
        null, repositoryId, namespace, "main", "x86_64", "demo", version, "x86_64",
        "demo-" + version + ".apk", path,
        Map.of("C", "Q1" + "A".repeat(27) + "=", "P", "demo", "V", version,
            "A", "x86_64", "S", "12", "I", "12"),
        "Q1" + "A".repeat(27) + "=", "a".repeat(64), "b".repeat(64), 12,
        null, null, AlpineRegistryDao.SOURCE_HOSTED, 0, now, now, now);
  }
}
