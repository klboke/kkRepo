package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.*;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcMavenSnapshotDao;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Identical inventory, retention, rollback and cross-connection locking checks on both databases. */
public final class MavenSnapshotDaoContract {
  private MavenSnapshotDaoContract() {}
  public static void verify(JdbcTemplate jdbc, PersistenceStores stores, com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect dialect) throws Exception {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    // Same isolation as the server's connection pool, on both supported database engines.
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dao = new JdbcMavenSnapshotDao(jdbc, dialect);
    long store = stores.blobStores().insert(new BlobStoreRecord(null, "snapshot-contract", "s3", null, null, "bucket", "", Map.of()));
    long repo = stores.repositories().insert(new RepositoryRecord(null, "snapshot-contract", RepositoryFormat.MAVEN2,
        RepositoryType.HOSTED, "maven2-hosted", true, store, null, null, null, null, "ALLOW", false, Map.of()));
    String path = "com/example/demo/1.0-SNAPSHOT";
    long family = tx.execute(s -> dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT"));
    assertEquals(path, dao.findFamily(family).orElseThrow().path());
    assertTrue(dao.findFamily(Long.MAX_VALUE).isEmpty());
    Instant old = Instant.parse("2026-01-01T00:00:00Z");
    List<Long> assetIds = new ArrayList<>();
    for (int number : new int[] {1, 9, 10, 11}) {
      for (String suffix : new String[] {".jar", ".pom", "-sources.jar", ".jar.asc", ".jar.sha1"}) {
        String version = "1.0-20260101.120000-" + number;
        String filename = "demo-" + version + suffix;
        String assetPath = path + "/" + filename;
        long id = tx.execute(s -> {
          dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
          long asset = stores.assets().insertAsset(new AssetRecord(null, repo, null, null, RepositoryFormat.MAVEN2,
              assetPath, PersistenceHashes.pathHash(assetPath), filename, "artifact", "application/octet-stream", 5L, null, old, Map.of()));
          boolean primary = !suffix.contains("asc") && !suffix.contains("sha");
          String variant = suffix.contains("sources") ? "sources:jar" : suffix.equals(".pom") ? ":pom" : ":jar";
          dao.store(family, repo, asset, version, 1000, number, variant, primary, old, true);
          return asset;
        });
        assetIds.add(id);
      }
    }
    var builds = dao.page(repo, 0, 10);
    assertEquals(4, builds.size());
    assertEquals(List.of(1, 9, 10, 11), builds.stream().map(MavenSnapshotDao.Build::buildNumber).toList());
    assertEquals(10, dao.retentionBoundary(family, 2).orElseThrow().buildNumber(), "Numeric build order, not lexicographic");
    assertTrue(dao.retentionBoundary(family, 5).isEmpty());
    for (var build : builds) {
      assertEquals(5, build.assetCount());
      assertEquals(3, build.primaryCount());
      assertEquals(old, build.mutatedAt());
      assertEquals(build.buildNumber() == 11, dao.hasLatestVariant(build.id()));
    }
    assertEquals(builds.subList(2, 4), dao.page(repo, builds.get(1).id(), 2));
    assertTrue(dao.page(repo + 999, 0, 10).isEmpty());
    var oldest = builds.getFirst();
    assertEquals(assetIds.subList(0, 2), dao.assetIds(oldest.id(), 2));
    assertEquals(assetIds.subList(2, 5), dao.backfillPage(assetIds.get(1), 3));
    tx.executeWithoutResult(s -> {
      dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
      dao.store(family, repo, assetIds.getFirst(), oldest.version(), 1000, 1, ":jar", true, old, true);
    });
    assertEquals(oldest, dao.findBuild(oldest.id()).orElseThrow(), "Backfill is idempotent");
    Instant legacyOverwrite = old.plusSeconds(60);
    tx.executeWithoutResult(s -> {
      dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
      dao.store(family, repo, assetIds.getFirst(), oldest.version(), 1000, 1, ":jar", true, legacyOverwrite, true);
      dao.store(family, repo, assetIds.get(1), oldest.version(), 1000, 1, ":jar", false, old, true);
    });
    var reconciled = dao.findBuild(oldest.id()).orElseThrow();
    assertEquals(legacyOverwrite, reconciled.mutatedAt(), "Backfill includes writes by retiring replicas without moving time backwards");
    assertEquals(oldest.revision(), reconciled.revision());
    assertEquals(oldest.assetCount(), reconciled.assetCount());
    tx.executeWithoutResult(s -> {
      dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
      dao.store(family, repo, assetIds.getFirst(), oldest.version(), 1000, 1, ":jar", true, old, false);
    });
    var updated = dao.findBuild(oldest.id()).orElseThrow();
    assertEquals(5, updated.assetCount());
    assertEquals(oldest.revision() + 1, updated.revision());
    assertTrue(updated.mutatedAt().isAfter(old));
    tx.executeWithoutResult(s -> {
      dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
      var locked = stores.assets().findAssetsByIdsForUpdate(assetIds.subList(0, 5));
      assertEquals(5, locked.size());
      for (long id : locked.keySet()) { dao.remove(id); stores.assets().deleteAssetById(id); }
      assertTrue(dao.findBuild(oldest.id()).isEmpty());
      s.setRollbackOnly();
    });
    assertEquals(updated, dao.findBuild(oldest.id()).orElseThrow());
    assertEquals(5, dao.assetIds(oldest.id(), 10).size());
    tx.executeWithoutResult(s -> {
      dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
      for (long id : assetIds.subList(0, 5)) { dao.remove(id); stores.assets().deleteAssetById(id); }
      dao.remove(-1);
    });
    assertTrue(dao.findBuild(oldest.id()).isEmpty());
    assertTrue(dao.assetIds(oldest.id(), 10).isEmpty());
    assertFalse(tx.<Boolean>execute(s -> dao.tryLockBlob(Long.MAX_VALUE)));
    assertFalse(tx.<Boolean>execute(s -> dao.tryLockComponent(Long.MAX_VALUE)));

    // Competing replicas must converge on one newly created family and serialize its mutations.
    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Callable<Long>> work = new ArrayList<>();
      for (int i = 0; i < 8; i++) work.add(() -> tx.execute(s ->
          dao.lockFamily(repo, "com/example/race/1-SNAPSHOT", "com.example", "race", "1-SNAPSHOT")));
      var ids = new HashSet<Long>();
      for (var result : executor.invokeAll(work)) ids.add(result.get(10, TimeUnit.SECONDS));
      assertEquals(1, ids.size());
      CountDownLatch locked = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      var holder = executor.submit(() -> tx.execute(s -> {
        dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
        locked.countDown();
        try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new IllegalStateException(e); }
        return true;
      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));
      var contender = executor.submit(() -> tx.execute(s -> dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT")));
      try { assertThrows(TimeoutException.class, () -> contender.get(150, TimeUnit.MILLISECONDS)); }
      finally { release.countDown(); }
      assertTrue(holder.get(10, TimeUnit.SECONDS));
      assertEquals(family, contender.get(10, TimeUnit.SECONDS));
    }
    // A classifier that disappeared from newer builds pins its last available build.
    long retained = builds.get(1).id();
    tx.executeWithoutResult(s -> {
      dao.lockFamily(repo, path, "com.example", "demo", "1.0-SNAPSHOT");
      for (long id : new long[] {assetIds.get(12), assetIds.get(17)}) {
        dao.remove(id); stores.assets().deleteAssetById(id);
      }
    });
    assertTrue(dao.hasLatestVariant(retained));
    verifyRetiringReplicaDeletion(dao, tx, stores, repo, old);
    verifyLargeFamily(jdbc, dao, tx, repo, dialect);
  }

  private static void verifyRetiringReplicaDeletion(MavenSnapshotDao dao, TransactionTemplate tx,
      PersistenceStores stores, long repo, Instant old) {
    String path = "com/example/retiring/1-SNAPSHOT";
    long family = tx.execute(s -> dao.lockFamily(repo, path, "com.example", "retiring", "1-SNAPSHOT"));
    List<Long> assets = new ArrayList<>();
    for (int number = 1; number <= 4; number++) {
      int buildNumber = number;
      for (boolean primary : List.of(true, false)) {
        String version = "1-20260101.120000-" + number;
        String filename = "retiring-" + version + (primary ? ".jar" : ".jar.sha1");
        String assetPath = path + "/" + filename;
        assets.add(tx.execute(s -> {
          dao.lockFamily(repo, path, "com.example", "retiring", "1-SNAPSHOT");
          long id = stores.assets().insertAsset(new AssetRecord(null, repo, null, null, RepositoryFormat.MAVEN2,
              assetPath, PersistenceHashes.pathHash(assetPath), filename, "artifact", "application/octet-stream", 5L, null, old, Map.of()));
          dao.store(family, repo, id, version, 1000, buildNumber, ":jar", primary, old, true);
          return id;
        }));
      }
    }
    var builds = dao.page(repo, 0, 100).stream().filter(b -> b.familyId() == family).toList();
    // The retiring implementation deletes assets without observer callbacks. FK cascades
    // remove links, but leave one empty build and one checksum-only build with stale counts.
    tx.executeWithoutResult(s -> {
      for (long id : List.of(assets.get(4), assets.get(6), assets.get(7))) stores.assets().deleteAssetById(id);
    });
    assertEquals(3, dao.retentionBoundary(family, 2).orElseThrow().buildNumber(), "Stale aggregates reproduce the lost retention slot");
    assertTrue(dao.assetIds(builds.get(3).id(), 10).isEmpty());
    assertEquals(List.of(builds.get(2).id(), builds.get(3).id()), dao.reconciliationPage(builds.get(1).id(), 2));
    tx.executeWithoutResult(s -> {
      builds.forEach(b -> dao.reconcile(b.id()));
      assertEquals(1, dao.retentionBoundary(family, 2).orElseThrow().buildNumber());
      s.setRollbackOnly();
    });
    assertTrue(dao.findBuild(builds.get(3).id()).isPresent(), "Reconciliation rolls back with its cursor transaction");
    tx.executeWithoutResult(s -> builds.forEach(b -> dao.reconcile(b.id())));
    assertTrue(dao.findBuild(builds.get(3).id()).isEmpty());
    var partial = dao.findBuild(builds.get(2).id()).orElseThrow();
    assertEquals(1, partial.assetCount());
    assertEquals(0, partial.primaryCount());
    assertEquals(builds.get(2).revision() + 1, partial.revision());
    assertTrue(partial.mutatedAt().isAfter(old));
    assertEquals(1, dao.retentionBoundary(family, 2).orElseThrow().buildNumber(), "Both surviving primary builds retain their slots");
    assertEquals(builds.getFirst(), dao.findBuild(builds.getFirst().id()).orElseThrow(), "Unchanged builds preserve publication time and revision");
    tx.executeWithoutResult(s -> builds.forEach(b -> dao.reconcile(b.id())));
    assertEquals(partial, dao.findBuild(partial.id()).orElseThrow(), "Reconciliation is idempotent");
  }

  private static void verifyLargeFamily(JdbcTemplate jdbc, MavenSnapshotDao dao, TransactionTemplate tx,
      long repo, com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect dialect) {
    String backend = dialect.type().id();
    int count = Integer.getInteger("kkrepo.test.snapshot.build-count", 2000);
    assertTrue(count >= 1000 && count <= 1000000);
    long family = tx.execute(s -> dao.lockFamily(repo, "com/example/large/1-SNAPSHOT", "com.example", "large", "1-SNAPSHOT"));
    for (int offset = 0; offset < count; offset += 500) {
      List<Object[]> assets = new ArrayList<>(), builds = new ArrayList<>(), links = new ArrayList<>();
      for (int i = offset + 1; i <= Math.min(offset + 500, count); i++) {
        long id = 1_000_000L + i;
        boolean primary = i <= count / 2;
        String path = "com/example/large/1-SNAPSHOT/large-1-20260101.120000-" + i + (primary ? ".jar" : ".jar.sha1");
        assets.add(new Object[] {id, repo, path, PersistenceHashes.pathHash(path)});
        builds.add(new Object[] {id, family, repo, "1-20260101.120000-" + i, i, primary ? 1 : 0});
        links.add(new Object[] {id, id, family, PersistenceHashes.sha256(":jar"), primary, i});
      }
      tx.executeWithoutResult(status -> {
        jdbc.batchUpdate("""
            INSERT INTO asset (id, repository_id, format, path, path_hash, name, kind, last_updated_at, attributes_json)
            VALUES (?, ?, 'maven2', ?, ?, 'large.jar', 'artifact', CURRENT_TIMESTAMP, '{}')
            """, assets);
        jdbc.batchUpdate("""
            INSERT INTO maven_snapshot_build (id, family_id, repository_id, version, snapshot_timestamp,
              build_number, asset_count, primary_count, mutated_at)
            VALUES (?, ?, ?, ?, 1000, ?, 1, ?, CURRENT_TIMESTAMP)
            """, builds);
        jdbc.batchUpdate("""
            INSERT INTO maven_snapshot_asset (asset_id, build_id, family_id, variant_hash,
              primary_asset, snapshot_timestamp, build_number) VALUES (?, ?, ?, ?, ?, 1000, ?)
            """, links);
      });
    }
    jdbc.execute((backend.equals("mysql") ? "ANALYZE TABLE " : "ANALYZE ") + "maven_snapshot_build");
    jdbc.execute((backend.equals("mysql") ? "ANALYZE TABLE " : "ANALYZE ") + "maven_snapshot_asset");
    long start = System.nanoTime();
    var page = dao.page(repo, 1_000_000L + count - 1000, 101);
    assertEquals(101, page.size());
    assertEquals(count / 2 - 4, dao.retentionBoundary(family, 5).orElseThrow().buildNumber());
    assertFalse(dao.hasLatestVariant(1_000_001));
    assertTrue(dao.hasLatestVariant(1_000_000L + count / 2));
    assertFalse(dao.hasLatestVariant(1_000_000L + count));
    assertEquals(List.of(1_000_001L), dao.assetIds(1_000_001, 4097));
    double ms = (System.nanoTime() - start) / 1_000_000.0;
    System.out.printf("Snapshot inventory %s: %,d builds, deep page + retention + variant probes %.2f ms%n", backend, count, ms);
    String planSql = "SELECT * FROM " + dialect.tableReferenceWithPreferredIndex("maven_snapshot_build", "idx_maven_snapshot_page")
        + " WHERE repository_id = ? AND id > ? ORDER BY id LIMIT 101";
    var plan = jdbc.queryForList("EXPLAIN " + planSql, repo, 1_000_000L + count - 1000);
    String text = plan.toString();
    assertFalse(text.contains("Seq Scan"), text);
    assertFalse(text.contains("type=ALL"), text);
    System.out.println("Snapshot keyset plan " + backend + ": " + text);
  }

}
