package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RRegistryDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
  private static final String NAMESPACE = "src/contrib";

  @Test
  void packageSnapshotsRelationsBindingsAndFencingArePortable() {
    long hostedId = insertRepository("r-hosted-pg", "hosted");
    long groupId = insertRepository("r-group-pg", "group");
    RRegistryDao dao = stores().rRegistry();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    RRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.savePackage(record(hostedId, "1.0.0", null, now)));
    RRegistryDao.PackageRecord newest = inTransaction(
        () -> dao.savePackage(record(hostedId, "1.10.0", null, now.plusMillis(1))));
    assertEquals(newest.id(),
        dao.findLatestPackage(hostedId, NAMESPACE, "demo").orElseThrow().id());
    assertEquals(stored.id(), dao.findPackage(
        hostedId, NAMESPACE, "source", "demo", "1.0.0", "source").orElseThrow().id());
    assertEquals(List.of(stored.id(), newest.id()), dao.listPackagePage(
        hostedId, NAMESPACE, "source", "source", "", 0, 10).stream()
        .map(RRegistryDao.PackageRecord::id).toList());

    dao.replacePackageRelations(hostedId, stored.id(), List.of(
        new RRegistryDao.PackageRelation(stored.id(), "IMPORTS", "dependency", "dependency (>= 1.0)")));
    assertEquals(List.of(stored.id()), dao.findPackagesByRelation(
        hostedId, "IMPORTS", "dependency", null, 10).stream()
        .map(RRegistryDao.PackageRecord::id).toList());

    String leaseKey = "r:publish:" + hostedId + ":" + NAMESPACE;
    RRegistryDao.Lease lease = dao.tryAcquireLease(
        leaseKey, "owner-1", now, now.plusSeconds(60)).orElseThrow();
    long revision = dao.findSuite(hostedId, NAMESPACE).orElseThrow().desiredRevision();
    RRegistryDao.Snapshot snapshot = new RRegistryDao.Snapshot(
        hostedId, NAMESPACE, revision, 1,
        Map.of(NAMESPACE + "/PACKAGES.gz", ".r/snapshots/1/PACKAGES.gz"),
        "d".repeat(64), now);
    assertFalse(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken() + 1));
    assertTrue(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken()));
    assertEquals(snapshot, dao.findPublishedSnapshot(hostedId, NAMESPACE).orElseThrow());

    dao.ensureSuite(groupId, NAMESPACE, now);
    long groupRevision = dao.markSuiteDirty(groupId, NAMESPACE, now);
    RRegistryDao.Lease groupLease = dao.tryAcquireLease(
        "r:publish:" + groupId + ":" + NAMESPACE,
        "group-owner", now, now.plusSeconds(60)).orElseThrow();
    RRegistryDao.Snapshot groupSnapshot = new RRegistryDao.Snapshot(
        groupId, NAMESPACE, groupRevision, 1,
        Map.of(NAMESPACE + "/PACKAGES.gz", ".r/group/PACKAGES.gz"),
        "e".repeat(64), now);
    dao.beginGroupSnapshot(groupId, NAMESPACE, groupRevision, groupLease.fencingToken());
    dao.appendGroupBindings(groupLease.fencingToken(), List.of(new RRegistryDao.GroupBinding(
        null, groupId, NAMESPACE, groupRevision, stored.path(), hostedId,
        snapshot.revision(), stored.path(), stored.identity(), stored.sha256(), stored.size(), now)));
    assertTrue(dao.publishGroupSnapshot(
        groupSnapshot, "group-owner", groupLease.fencingToken()));
    assertEquals(hostedId, dao.findGroupBinding(
        groupId, NAMESPACE, groupRevision, stored.path()).orElseThrow().memberRepositoryId());

    dao.observeProxyDistribution(
        hostedId,
        NAMESPACE,
        "release-1",
        Map.of(stored.path(), new RRegistryDao.ProxyIndex(stored.sha256(), stored.size())),
        true,
        now);
    assertTrue(dao.findProxyDistribution(hostedId, NAMESPACE)
        .orElseThrow().projectionVerified());

    long proxyId = insertRepository("r-proxy-pg", "proxy");
    RRegistryDao.PackageRecord projection = inTransaction(
        () -> dao.savePackage(proxyRecord(proxyId, now)));
    long projectedRevision = dao.findSuite(proxyId, NAMESPACE).orElseThrow().desiredRevision();
    RRegistryDao.PackageRecord materialized = dao.materializeProxyPackage(
        materialized(projection, now.plusSeconds(1)), projection.identity(), projection.revision())
        .orElseThrow();
    assertEquals("c".repeat(64), materialized.sha256());
    assertEquals(42L, materialized.size());
    assertEquals(projection.revision(), materialized.revision());
    assertEquals(projectedRevision,
        dao.findSuite(proxyId, NAMESPACE).orElseThrow().desiredRevision());
    assertTrue(dao.materializeProxyPackage(
        materialized(projection, now.plusSeconds(2)), "f".repeat(32), projection.revision())
        .isEmpty());

    assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() -> dao.savePackage(
        record(hostedId, "2.0.0", stored.path(), now.plusSeconds(2)))));
    assertTrue(inTransaction(() -> dao.deletePackage(
        hostedId, NAMESPACE, "source", "demo", "1.0.0", "source", "test",
        now.plusSeconds(3))).isPresent());
  }

  @Test
  void accessShapesHaveRepositoryLeadingIndexesAndIndexedPlans() {
    assertTrue(indexDefinition("uk_r_package_coordinate")
        .contains("(repository_id, coordinate_hash)"));
    assertTrue(indexDefinition("uk_r_package_path")
        .contains("(repository_id, asset_path_hash)"));
    assertTrue(indexDefinition("idx_r_package_index_page").contains(
        "(repository_id, distribution_name, component_name, architecture, package_name, id)"));
    assertTrue(indexDefinition("idx_r_package_name").contains(
        "(repository_id, distribution_name, package_name, version_order_key DESC, id DESC)"));
    assertTrue(indexDefinition("idx_r_relation_lookup")
        .contains("(repository_id, relation_kind, token_hash, package_id)"));
    assertTrue(indexDefinition("idx_r_group_page").contains(
        "(group_repository_id, distribution_name, snapshot_revision, binding_token, id)"));
    assertTrue(indexDefinition("idx_r_suite_worker").contains(
        "(publish_pending, desired_at, repository_id, distribution_name)"));
    assertTrue(indexDefinition("idx_r_snapshot_retention").contains(
        "(repository_id, distribution_name, revision DESC) WHERE (published_at IS NOT NULL)"));

    long repositoryId = insertRepository("r-plan-pg", "hosted");
    jdbc().update("""
        INSERT INTO r_package_record
          (repository_id, coordinate_hash, distribution_name, component_name, architecture,
           package_name, package_version, version_order_key, package_architecture, filename,
           asset_path, asset_path_hash, control_fields, package_identity, data_sha256, sha256,
           size_bytes, source_kind, revision, indexed_at, created_at, updated_at)
        SELECT ?, decode(md5('r-plan-' || n::text) || md5('coordinate-' || n::text), 'hex'),
               'src/contrib', 'source', 'source', 'plan' || n::text, '1.' || n::text,
               convert_to('r1|' || lpad(n::text, 8, '0'), 'UTF8'), 'source',
               'plan' || n::text || '_1.' || n::text || '.tar.gz',
               'src/contrib/plan' || n::text || '_1.' || n::text || '.tar.gz',
               decode(md5('path-' || n::text) || md5('r-path-' || n::text), 'hex'),
               jsonb_build_object('Package', 'plan' || n::text, 'Version', '1.' || n::text),
               left(md5('md5-' || n::text), 32), repeat('a', 64), repeat('b', 64),
               12, 'HOSTED', n, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        FROM generate_series(1, 2048) AS n
        """, repositoryId);
    jdbc().update("""
        INSERT INTO r_package_relation
          (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
        SELECT repository_id, id, 'IMPORTS',
               CASE WHEN package_name = 'plan1024' THEN 'fixture' ELSE 'dep' || id::text END,
               CASE WHEN package_name = 'plan1024'
                    THEN decode(md5('fixture') || md5('token'), 'hex')
                    ELSE decode(md5('dep' || id::text) || md5('token-' || id::text), 'hex')
               END,
               CASE WHEN package_name = 'plan1024' THEN 'fixture' ELSE 'dep' || id::text END
        FROM r_package_record WHERE repository_id = ?
        """, repositoryId);
    jdbc().execute("ANALYZE r_package_record");
    jdbc().execute("ANALYZE r_package_relation");

    String exactPlan = indexedPlan("""
        EXPLAIN SELECT * FROM r_package_record
        WHERE repository_id = ?
          AND coordinate_hash = decode(md5('r-plan-1024') || md5('coordinate-1024'), 'hex')
        """, repositoryId);
    assertTrue(exactPlan.contains("uk_r_package_coordinate"), exactPlan);
    String pagePlan = indexedPlan("""
        EXPLAIN SELECT * FROM r_package_record
        WHERE repository_id = ? AND distribution_name = 'src/contrib'
          AND component_name = 'source' AND architecture = 'source'
          AND (package_name, id) > ('', 0)
        ORDER BY package_name, id LIMIT 20
        """, repositoryId);
    assertTrue(pagePlan.contains("idx_r_package_index_page")
        || pagePlan.contains("idx_r_package_namespace_page"), pagePlan);
    assertFalse(pagePlan.contains("Seq Scan on r_package_record"), pagePlan);
    String latestPlan = indexedPlan("""
        EXPLAIN SELECT * FROM r_package_record
        WHERE repository_id = ? AND distribution_name = 'src/contrib'
          AND package_name = 'plan1024'
        ORDER BY version_order_key DESC, id DESC LIMIT 1
        """, repositoryId);
    assertTrue(latestPlan.contains("idx_r_package_name"), latestPlan);
    String relationPlan = indexedPlan("""
        EXPLAIN SELECT package_row.* FROM r_package_relation relation_row
        JOIN r_package_record package_row ON package_row.id = relation_row.package_id
        WHERE relation_row.repository_id = ? AND package_row.repository_id = ?
          AND relation_row.relation_kind = 'IMPORTS'
          AND relation_row.token_hash = decode(md5('fixture') || md5('token'), 'hex')
          AND relation_row.token_value = 'fixture' AND package_row.id > 0
        ORDER BY package_row.id LIMIT 20
        """, repositoryId, repositoryId);
    assertTrue(relationPlan.contains("idx_r_relation_lookup"), relationPlan);
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
        VALUES (?, 'r', ?, ?, ?, CAST('{}' AS jsonb))
        """, name, type, "r-" + type, blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

  private static RRegistryDao.PackageRecord record(
      long repositoryId, String version, String explicitPath, Instant now) {
    String filename = "demo_" + version + ".tar.gz";
    String path = explicitPath == null ? NAMESPACE + "/" + filename : explicitPath;
    return new RRegistryDao.PackageRecord(
        null, repositoryId, NAMESPACE, "source", "source", "demo", version,
        ("r1|" + version).getBytes(StandardCharsets.US_ASCII), "source", filename, path,
        Map.of("Package", "demo", "Version", version), "a".repeat(32), "b".repeat(64),
        "b".repeat(64), 12, null, null, RRegistryDao.SOURCE_HOSTED, 0, now, now, now);
  }

  private static RRegistryDao.PackageRecord proxyRecord(long repositoryId, Instant now) {
    RRegistryDao.PackageRecord row = record(repositoryId, "2.0.0", null, now);
    return new RRegistryDao.PackageRecord(
        null, row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.versionOrderKey(), row.packageArchitecture(),
        row.filename(), row.path(), row.controlFields(), row.identity(), "0".repeat(64),
        "0".repeat(64), 0, null, null, RRegistryDao.SOURCE_PROXY, 0, now, now, now);
  }

  private static RRegistryDao.PackageRecord materialized(
      RRegistryDao.PackageRecord row, Instant now) {
    return new RRegistryDao.PackageRecord(
        row.id(), row.repositoryId(), row.distribution(), row.component(), row.architecture(),
        row.packageName(), row.version(), row.versionOrderKey(), row.packageArchitecture(),
        row.filename(), row.path(), row.controlFields(), row.identity(), "c".repeat(64),
        "c".repeat(64), 42, null, null, row.sourceKind(), row.revision(), now,
        row.createdAt(), now);
  }
}
