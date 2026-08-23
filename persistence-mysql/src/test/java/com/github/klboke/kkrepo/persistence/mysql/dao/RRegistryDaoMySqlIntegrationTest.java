package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RRegistryDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  private static final String NAMESPACE = "src/contrib";

  @Test
  void packageSnapshotsRelationsBindingsAndFencingAreDurable() {
    long hostedId = insertRepository("r-hosted-db", "r");
    long groupId = insertRepository("r-group-db", "r");
    jdbc().update(
        "UPDATE repository SET type = 'group', recipe_name = 'r-group' WHERE id = ?",
        groupId);
    RRegistryDao dao = stores().rRegistry();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    RRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.savePackage(record(hostedId, "1.0.0", null, now)));
    RRegistryDao.PackageRecord newest = inTransaction(
        () -> dao.savePackage(record(hostedId, "1.10.0", null, now.plusMillis(1))));
    assertEquals(1L, stored.revision());
    assertEquals(newest.id(),
        dao.findLatestPackage(hostedId, NAMESPACE, "demo").orElseThrow().id());
    assertEquals(stored.id(), dao.findPackage(
        hostedId, NAMESPACE, "source", "demo", "1.0.0", "source").orElseThrow().id());
    assertEquals(stored.id(), dao.findPackageByPath(hostedId, stored.path()).orElseThrow().id());
    assertEquals(List.of(stored.id(), newest.id()), dao.listPackagePage(
        hostedId, NAMESPACE, "source", "source", "", 0, 10).stream()
        .map(RRegistryDao.PackageRecord::id).toList());
    ArrayList<RRegistryDao.PackageRecord> visited = new ArrayList<>();
    dao.visitPackages(hostedId, NAMESPACE, visited::add);
    assertEquals(List.of(stored.id(), newest.id()),
        visited.stream().map(RRegistryDao.PackageRecord::id).toList());
    assertEquals(List.of(NAMESPACE), dao.listDistributions(hostedId));
    assertTrue(dao.listPendingSuites(
            now.plusSeconds(1), now.plusSeconds(1), now.plusSeconds(1), 10).stream()
        .anyMatch(suite -> suite.repositoryId() == hostedId));

    dao.replacePackageRelations(hostedId, stored.id(), List.of(
        new RRegistryDao.PackageRelation(stored.id(), "IMPORTS", "dependency", "dependency (>= 1.0)")));
    assertEquals(List.of(stored.id()), dao.findPackagesByRelation(
        hostedId, "IMPORTS", "dependency", null, 10).stream()
        .map(RRegistryDao.PackageRecord::id).toList());

    String leaseKey = "r:publish:" + hostedId + ":" + NAMESPACE;
    RRegistryDao.Lease lease = dao.tryAcquireLease(
        leaseKey, "owner-1", now, now.plusSeconds(60)).orElseThrow();
    assertTrue(dao.tryAcquireLease(
        leaseKey, "owner-2", now, now.plusSeconds(60)).isEmpty());
    long revision = dao.findSuite(hostedId, NAMESPACE).orElseThrow().desiredRevision();
    RRegistryDao.Snapshot snapshot = new RRegistryDao.Snapshot(
        hostedId, NAMESPACE, revision, 1,
        Map.of(NAMESPACE + "/PACKAGES.gz", ".r/snapshots/1/PACKAGES.gz"),
        "d".repeat(64), now);
    assertFalse(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken() + 1));
    assertTrue(dao.publishSnapshot(snapshot, "owner-1", lease.fencingToken()));
    assertEquals(snapshot, dao.findPublishedSnapshot(hostedId, NAMESPACE).orElseThrow());
    assertTrue(dao.renewLease(
        leaseKey, "owner-1", lease.fencingToken(), now, now.plusSeconds(120)));
    dao.releaseLease(leaseKey, "owner-1", lease.fencingToken());
    assertTrue(dao.tryAcquireLease(
            leaseKey, "owner-2", now.plusSeconds(1), now.plusSeconds(120))
        .orElseThrow().fencingToken() > lease.fencingToken());

    dao.ensureSuite(groupId, NAMESPACE, now);
    long groupRevision = dao.markSuiteDirty(groupId, NAMESPACE, now);
    String groupLeaseKey = "r:publish:" + groupId + ":" + NAMESPACE;
    RRegistryDao.Lease groupLease = dao.tryAcquireLease(
        groupLeaseKey, "group-owner", now, now.plusSeconds(60)).orElseThrow();
    RRegistryDao.Snapshot groupSnapshot = new RRegistryDao.Snapshot(
        groupId, NAMESPACE, groupRevision, 1,
        Map.of(NAMESPACE + "/PACKAGES.gz", ".r/group/PACKAGES.gz"),
        "e".repeat(64), now);
    RRegistryDao.GroupBinding binding = new RRegistryDao.GroupBinding(
        null, groupId, NAMESPACE, groupRevision, stored.path(), hostedId,
        snapshot.revision(), stored.path(), stored.identity(), stored.sha256(), stored.size(), now);
    assertFalse(dao.publishGroupSnapshot(
        groupSnapshot, "group-owner", groupLease.fencingToken()));
    dao.beginGroupSnapshot(groupId, NAMESPACE, groupRevision, groupLease.fencingToken());
    dao.appendGroupBindings(groupLease.fencingToken(), List.of(binding));
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

    long proxyId = insertRepository("r-proxy-db", "r");
    jdbc().update(
        "UPDATE repository SET type = 'proxy', recipe_name = 'r-proxy' WHERE id = ?", proxyId);
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
    assertTrue(dao.findPackageByPath(hostedId, stored.path()).isEmpty());
  }

  @Test
  void maintenanceQueriesValidationAndRepositoryCleanupCoverEveryDurableState() {
    long hostedId = insertRepository("r-maintenance", "r");
    long groupId = insertRepository("r-maintenance-group", "r");
    jdbc().update(
        "UPDATE repository SET type = 'group', recipe_name = 'r-group' WHERE id = ?",
        groupId);
    RRegistryDao dao = stores().rRegistry();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    assertThrows(IllegalArgumentException.class, () -> dao.savePackage(null));
    assertThrows(IllegalArgumentException.class,
        () -> dao.ensureSuite(hostedId, " ", now));
    assertThrows(IllegalArgumentException.class,
        () -> dao.tryAcquireLease("", "owner", now, now.plusSeconds(1)));
    assertThrows(IllegalArgumentException.class,
        () -> dao.tryAcquireLease("lease", "owner", now, now));

    RRegistryDao.PackageRecord stored = inTransaction(
        () -> dao.savePackage(record(hostedId, "1.0.0", null, now)));
    RRegistryDao.PackageRecord updatedInput = new RRegistryDao.PackageRecord(
        stored.id(), stored.repositoryId(), stored.distribution(), stored.component(),
        stored.architecture(), stored.packageName(), stored.version(), stored.versionOrderKey(),
        stored.packageArchitecture(), stored.filename(), stored.path(),
        Map.of("Package", "demo", "Version", "1.0.0", "Title", "updated"),
        stored.identity(), "c".repeat(64), "c".repeat(64), 13L, null, null,
        stored.sourceKind(), stored.revision(), now, stored.createdAt(), now.plusMillis(1));
    RRegistryDao.PackageRecord updated = inTransaction(() -> dao.savePackage(updatedInput));
    assertEquals(stored.id(), updated.id());
    assertEquals("updated", updated.controlFields().get("Title"));
    assertEquals(List.of("source"), dao.listComponents(hostedId, NAMESPACE));
    assertEquals(List.of("source"), dao.listArchitectures(hostedId, NAMESPACE, "source"));
    assertEquals(List.of(updated.id()), dao.listPackagePage(
        hostedId, NAMESPACE, null, -1, 0).stream()
        .map(RRegistryDao.PackageRecord::id).toList());
    ArrayList<RRegistryDao.PackageRecord> visited = new ArrayList<>();
    dao.visitPackages(hostedId, NAMESPACE, visited::add);
    assertEquals(List.of(updated.id()),
        visited.stream().map(RRegistryDao.PackageRecord::id).toList());
    assertEquals(1, dao.listSuites(hostedId).size());

    assertThrows(IllegalArgumentException.class, () -> dao.materializeProxyPackage(
        updated, updated.identity(), updated.revision()));
    RRegistryDao.PackageRecord invalidSource = new RRegistryDao.PackageRecord(
        null, hostedId, NAMESPACE, "source", "source", "invalid", "1.0.0",
        "r1|invalid".getBytes(StandardCharsets.US_ASCII), "source",
        "invalid_1.0.0.tar.gz", NAMESPACE + "/invalid_1.0.0.tar.gz", Map.of(),
        "a".repeat(32), "b".repeat(64), "b".repeat(64), 1L, null, null,
        "INVALID", 0L, now, now, now);
    assertThrows(IllegalArgumentException.class, () -> dao.savePackage(invalidSource));

    String leaseKey = "r:publish:" + hostedId + ":" + NAMESPACE;
    RRegistryDao.Lease lease = dao.tryAcquireLease(
        leaseKey, "maintenance-owner", now, now.plusSeconds(3600)).orElseThrow();
    assertTrue(dao.tryAcquireLease(
        leaseKey, "maintenance-owner", now.plusMillis(1), now.plusSeconds(3600))
        .orElseThrow().fencingToken() > lease.fencingToken());
    lease = dao.tryAcquireLease(
        leaseKey, "maintenance-owner", now.plusMillis(2), now.plusSeconds(3600)).orElseThrow();
    assertFalse(dao.renewLease(
        leaseKey, "other", lease.fencingToken(), now, now.plusSeconds(3600)));

    ArrayList<RRegistryDao.Snapshot> published = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      long revision = index == 0
          ? dao.findSuite(hostedId, NAMESPACE).orElseThrow().desiredRevision()
          : dao.markSuiteDirty(hostedId, NAMESPACE, now.plusMillis(10L + index));
      RRegistryDao.Snapshot snapshot = snapshot(
          hostedId, revision, ".r/maintenance/" + revision, now.minusSeconds(100 - index));
      assertTrue(dao.publishSnapshot(snapshot, lease.owner(), lease.fencingToken()));
      published.add(snapshot);
    }
    RRegistryDao.Snapshot current = published.getLast();
    assertEquals(current, dao.findSnapshot(hostedId, NAMESPACE, current.revision()).orElseThrow());
    assertEquals(5, dao.listSnapshots(hostedId, NAMESPACE, 500).size());
    assertFalse(dao.listSnapshotCleanupCandidates(now.plusSeconds(1), 3, 100).isEmpty());
    assertFalse(dao.deleteSnapshot(hostedId, NAMESPACE, current.revision()));
    for (RRegistryDao.Snapshot candidate : published.subList(0, published.size() - 1)) {
      assertTrue(dao.deleteSnapshot(hostedId, NAMESPACE, candidate.revision()));
    }

    long currentRevision = dao.findSuite(hostedId, NAMESPACE).orElseThrow().desiredRevision();
    dao.recordBuildFailure(hostedId, NAMESPACE, currentRevision, "x".repeat(4096), null);
    assertEquals(2048, dao.findSuite(hostedId, NAMESPACE).orElseThrow().lastError().length());
    dao.recordBuildFailure(hostedId, NAMESPACE, currentRevision, null, now);
    assertEquals(null, dao.findSuite(hostedId, NAMESPACE).orElseThrow().lastError());

    assertTrue(inTransaction(() -> dao.deletePackage(
        hostedId, NAMESPACE, "source", "demo", "1.0.0", "source", "cleanup", null))
        .isPresent());
    long deletionRevision = dao.findSuite(hostedId, NAMESPACE).orElseThrow().desiredRevision();
    assertTrue(dao.publishSnapshot(
        snapshot(hostedId, deletionRevision, ".r/maintenance/deleted", now),
        lease.owner(), lease.fencingToken()));
    assertTrue(dao.deleteSnapshot(hostedId, NAMESPACE, current.revision()));
    List<RRegistryDao.PackageTombstone> tombstones = dao.listPackageCleanupCandidates(
        now.plusSeconds(60), 500);
    assertEquals(1, tombstones.size());
    assertFalse(dao.deletePackageTombstone(null));
    assertTrue(dao.deletePackageTombstone(tombstones.getFirst()));
    assertFalse(dao.deletePackageTombstone(tombstones.getFirst()));
    assertTrue(dao.deletePackage(
        hostedId, NAMESPACE, "source", "missing", "1", "source", "missing", now).isEmpty());

    dao.replacePackageRelations(hostedId, 999L, null);
    dao.replacePackageRelations(hostedId, 999L, List.of());
    assertThrows(IllegalArgumentException.class, () -> dao.replacePackageRelations(
        hostedId, 999L, List.of(new RRegistryDao.PackageRelation(
            998L, "IMPORTS", "dependency", "dependency"))));

    dao.ensureSuite(groupId, NAMESPACE, now);
    long groupRevision = dao.markSuiteDirty(groupId, NAMESPACE, now);
    String groupLeaseKey = "r:publish:" + groupId + ":" + NAMESPACE;
    RRegistryDao.Lease groupLease = dao.tryAcquireLease(
        groupLeaseKey, "group-owner", now, now.plusSeconds(3600)).orElseThrow();
    assertThrows(IllegalStateException.class,
        () -> dao.beginGroupSnapshot(groupId, NAMESPACE, groupRevision,
            groupLease.fencingToken() + 1));
    dao.appendGroupBindings(groupLease.fencingToken(), null);
    dao.appendGroupBindings(groupLease.fencingToken(), List.of());
    dao.beginGroupSnapshot(groupId, NAMESPACE, groupRevision, groupLease.fencingToken());
    RRegistryDao.GroupBinding binding = new RRegistryDao.GroupBinding(
        null, groupId, NAMESPACE, groupRevision, "src/contrib/demo_1.0.0.tar.gz",
        hostedId, deletionRevision, "src/contrib/demo_1.0.0.tar.gz", "a".repeat(32),
        "b".repeat(64), 12L, null);
    assertThrows(IllegalStateException.class,
        () -> dao.appendGroupBindings(groupLease.fencingToken() + 1, List.of(binding)));
    RRegistryDao.GroupBinding mismatched = new RRegistryDao.GroupBinding(
        null, groupId, "other", groupRevision, binding.path(), hostedId, deletionRevision,
        binding.memberPath(), binding.identity(), binding.sha256(), binding.size(), now);
    assertThrows(IllegalArgumentException.class,
        () -> dao.appendGroupBindings(groupLease.fencingToken(), List.of(binding, mismatched)));
    dao.appendGroupBindings(groupLease.fencingToken(), List.of(binding));
    assertTrue(dao.listGroupBindings(groupId, NAMESPACE, groupRevision, null, 1).isEmpty());
    RRegistryDao.Snapshot groupSnapshot = snapshot(
        groupId, groupRevision, ".r/group/maintenance", now);
    assertTrue(dao.publishGroupSnapshot(
        groupSnapshot, groupLease.owner(), groupLease.fencingToken()));
    assertEquals(1, dao.listGroupBindings(
        groupId, NAMESPACE, groupRevision, -1L, 5000).size());
    assertThrows(IllegalArgumentException.class,
        () -> dao.publishGroupSnapshot(null, groupLease.owner(), groupLease.fencingToken()));

    long orphanRevision = dao.markSuiteDirty(groupId, NAMESPACE, now.plusSeconds(1));
    dao.beginGroupSnapshot(groupId, NAMESPACE, orphanRevision, groupLease.fencingToken());
    jdbc().update(
        "UPDATE r_group_snapshot_stage SET created_at = ? WHERE group_repository_id = ? "
            + "AND snapshot_revision = ?",
        java.sql.Timestamp.from(now.minusSeconds(60)), groupId, orphanRevision);
    assertEquals(1, dao.deleteOrphanGroupBindings(now, 0));
    assertEquals(0, dao.deleteOrphanGroupBindings(now, 1));

    dao.observeProxyDistribution(hostedId, NAMESPACE, "empty-release", now);
    assertEquals(1, dao.listProxyDistributions(hostedId).size());
    assertFalse(dao.findProxyDistribution(hostedId, NAMESPACE)
        .orElseThrow().projectionVerified());

    dao.deleteRepositoryState(groupId);
    dao.deleteRepositoryState(hostedId);
    assertTrue(dao.listSuites(groupId).isEmpty());
    assertTrue(dao.listSuites(hostedId).isEmpty());
    assertTrue(dao.listProxyDistributions(hostedId).isEmpty());
  }

  @Test
  void accessShapesHaveRepositoryLeadingIndexesAndIndexedPlans() {
    assertEquals(List.of("repository_id", "coordinate_hash"),
        indexColumns("r_package_record", "uk_r_package_coordinate"));
    assertEquals(List.of("repository_id", "asset_path_hash"),
        indexColumns("r_package_record", "uk_r_package_path"));
    assertEquals(List.of(
        "repository_id", "distribution_name", "component_name", "architecture",
        "package_name", "id"),
        indexColumns("r_package_record", "idx_r_package_index_page"));
    assertEquals(List.of(
        "repository_id", "distribution_name", "package_name", "version_order_key", "id"),
        indexColumns("r_package_record", "idx_r_package_name"));
    assertEquals(List.of("repository_id", "relation_kind", "token_hash", "package_id"),
        indexColumns("r_package_relation", "idx_r_relation_lookup"));
    assertEquals(List.of(
        "publish_pending", "desired_at", "repository_id", "distribution_name"),
        indexColumns("r_suite_state", "idx_r_suite_worker"));
    assertEquals(List.of(
        "repository_id", "distribution_name", "publish_complete", "revision"),
        indexColumns("r_snapshot", "idx_r_snapshot_retention"));

    long repositoryId = insertRepository("r-plan", "r");
    inTransaction(() -> {
      jdbc().execute("SET SESSION cte_max_recursion_depth = 4096");
      return jdbc().update("""
          INSERT INTO r_package_record
            (repository_id, coordinate_hash, distribution_name, component_name, architecture,
             package_name, package_version, version_order_key, package_architecture, filename,
             asset_path, asset_path_hash, control_fields, package_identity, data_sha256, sha256,
             size_bytes, source_kind, revision, indexed_at, created_at, updated_at)
          WITH RECURSIVE sequence_value(n) AS (
            SELECT 1
            UNION ALL
            SELECT n + 1 FROM sequence_value WHERE n < 2048
          )
          SELECT ?, UNHEX(SHA2(CONCAT('r-plan-', n), 256)), 'src/contrib', 'source',
                 'source', CONCAT('plan', n), CONCAT('1.', n),
                 CONVERT(CONCAT('r1|0001:1|0004:', LPAD(n, 4, '0'), '|') USING BINARY),
                 'source', CONCAT('plan', n, '_1.', n, '.tar.gz'),
                 CONCAT('src/contrib/plan', n, '_1.', n, '.tar.gz'),
                 UNHEX(SHA2(CONCAT('src/contrib/plan', n, '_1.', n, '.tar.gz'), 256)),
                 JSON_OBJECT('Package', CONCAT('plan', n), 'Version', CONCAT('1.', n)),
                 LEFT(SHA2(CONCAT('md5-', n), 256), 32), REPEAT('a', 64), REPEAT('b', 64),
                 12, 'HOSTED', n, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
          FROM sequence_value
          """, repositoryId);
    });
    jdbc().update("""
        INSERT INTO r_package_relation
          (repository_id, package_id, relation_kind, token_value, token_hash, expression_value)
        SELECT repository_id, id, 'IMPORTS', 'fixture', UNHEX(SHA2('fixture', 256)), 'fixture'
        FROM r_package_record WHERE repository_id = ?
        """, repositoryId);
    jdbc().execute("ANALYZE TABLE r_package_record, r_package_relation");

    String exactPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON SELECT * FROM r_package_record
        WHERE repository_id = ? AND coordinate_hash = UNHEX(SHA2('r-plan-1024', 256))
        """, String.class, repositoryId);
    assertTrue(exactPlan.contains("uk_r_package_coordinate"), exactPlan);
    String pagePlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON SELECT * FROM r_package_record
        WHERE repository_id = ? AND distribution_name = 'src/contrib'
          AND component_name = 'source' AND architecture = 'source'
          AND (package_name > '' OR (package_name = '' AND id > 0))
        ORDER BY package_name, id LIMIT 20
        """, String.class, repositoryId);
    assertTrue(pagePlan.contains("idx_r_package_index_page"), pagePlan);
    String latestPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON SELECT * FROM r_package_record FORCE INDEX (idx_r_package_name)
        WHERE repository_id = ? AND distribution_name = 'src/contrib'
          AND package_name = 'plan1024'
        ORDER BY version_order_key DESC, id DESC LIMIT 1
        """, String.class, repositoryId);
    assertTrue(latestPlan.contains("idx_r_package_name"), latestPlan);
    String relationPlan = jdbc().queryForObject("""
        EXPLAIN FORMAT=JSON
        SELECT package_row.* FROM r_package_relation relation_row
        JOIN r_package_record package_row ON package_row.id = relation_row.package_id
        WHERE relation_row.repository_id = ? AND package_row.repository_id = ?
          AND relation_row.relation_kind = 'IMPORTS'
          AND relation_row.token_hash = UNHEX(SHA2('fixture', 256))
          AND relation_row.token_value = 'fixture' AND package_row.id > 0
        ORDER BY package_row.id LIMIT 20
        """, String.class, repositoryId, repositoryId);
    assertTrue(relationPlan.contains("idx_r_relation_lookup"), relationPlan);
  }

  private List<String> indexColumns(String table, String index) {
    return jdbc().queryForList("""
        SELECT column_name FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
        ORDER BY seq_in_index
        """, String.class, table, index);
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

  private static RRegistryDao.Snapshot snapshot(
      long repositoryId, long revision, String hiddenPath, Instant createdAt) {
    return new RRegistryDao.Snapshot(
        repositoryId, NAMESPACE, revision, 1,
        Map.of(NAMESPACE + "/PACKAGES.gz", hiddenPath),
        String.format("%064x", revision), createdAt);
  }
}
