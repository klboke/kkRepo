package com.github.klboke.kkrepo.server.statistics;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.DatabaseConnectionSettings;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStoreFactories;
import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in benchmark against the isolated, fully migrated million-row fixture in scripts/perf. */
@EnabledIfSystemProperty(named = "kkrepo.usagePerf.url", matches = ".+")
class StorageUsagePerformanceTest {
  @Test
  void millionRowInventory() throws Exception {
    String url = System.getProperty("kkrepo.usagePerf.url");
    String backend = url.contains(":mysql:") ? "mysql" : "postgresql";
    StringBuilder report = new StringBuilder("backend=" + backend + "\n");
    try (var stores = PersistenceStoreFactories.connect(new DatabaseConnectionSettings(url,
        System.getProperty("kkrepo.usagePerf.username", "root"),
        System.getProperty("kkrepo.usagePerf.password", "usage-perf-local-only")))) {
      StorageStatisticsDao dao = stores.storageStatistics();
      var blobs = timed(report, "first_blob_aggregate", dao::blobStoreUsage);
      var repos = timed(report, "first_repository_aggregate", dao::repositoryUsage);
      assertEquals(1_000_000, blobs.values().stream().mapToLong(usage -> usage.blobCount().longValueExact()).sum());
      assertEquals(100_000, blobs.values().stream().mapToLong(usage -> usage.pendingDeletionCount().longValueExact()).sum());
      assertEquals(1_000_000, repos.values().stream().mapToLong(usage -> usage.assetCount().longValueExact()).sum());
      assertEquals(10, blobs.size());
      assertEquals(100, repos.size());
      assertEquals(LongStream.rangeClosed(1, 1_000_000).map(n -> 1024 + n % 4096).sum(),
          blobs.values().stream().mapToLong(usage -> usage.totalBytes().longValueExact()).sum());
      assertEquals(LongStream.rangeClosed(900_001, 1_000_000).map(n -> 1024 + n % 4096).sum(),
          blobs.values().stream().mapToLong(usage -> usage.pendingDeletionBytes().longValueExact()).sum());
      assertEquals(LongStream.rangeClosed(1, 1_000_000).map(n -> 1024 + (n > 800_000 ? n - 800_000 : n) % 4096).sum(),
          repos.values().stream().mapToLong(usage -> usage.totalBytes().longValueExact()).sum());
      report.append("assets=1000000 blobs=1000000 repositories=100 stores=10 pending_blobs=100000\n");
      repeated(report, "uncached_blob_aggregate", 20, dao::blobStoreUsage);
      repeated(report, "uncached_repository_aggregate", 20, dao::repositoryUsage);

      AtomicInteger queries = new AtomicInteger();
      StorageStatisticsDao counted = new StorageStatisticsDao() {
        public Map<Long, BlobStoreUsage> blobStoreUsage() { queries.incrementAndGet(); return dao.blobStoreUsage(); }
        public Map<Long, RepositoryUsage> repositoryUsage() { queries.incrementAndGet(); return dao.repositoryUsage(); }
      };
      var service = new StorageStatisticsService(counted);
      List<Long> storeIds = LongStream.rangeClosed(1, 10).boxed().toList();
      List<Long> repoIds = LongStream.rangeClosed(1, 100).boxed().toList();
      CountDownLatch ready = new CountDownLatch(16);
      CountDownLatch start = new CountDownLatch(1);
      List<Double> cold = new ArrayList<>();
      try (var workers = Executors.newFixedThreadPool(16)) {
        var futures = java.util.stream.IntStream.range(0, 16).mapToObj(i -> workers.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, TimeUnit.SECONDS));
          long began = System.nanoTime();
          assertEquals(blobs, service.blobs(storeIds).usage());
          assertEquals(repos, service.assets(repoIds).usage());
          return elapsed(began);
        })).toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (var future : futures) cold.add(future.get(60, TimeUnit.SECONDS));
      }
      distribution(report, "16_concurrent_cold_service_pairs", cold);
      assertEquals(2, queries.get(), "concurrent requests must coalesce to one query per inventory");
      report.append("cold_concurrent_aggregate_queries=2\n");
      repeated(report, "cached_service_pair", 1000, () -> List.of(service.blobs(storeIds), service.assets(repoIds)));
      assertEquals(2, queries.get(), "hot-cache calls must perform no aggregate queries");
      report.append("warm_additional_aggregate_queries=0\n");

      // A freshly vacuumed PostgreSQL fixture alone is too optimistic. Touch 2% of rows
      // spread across the entire heap and repeat without VACUUM, like active repositories.
      // These timestamps do not change the expected inventory or damage the reusable fixture.
      try (var connection = java.sql.DriverManager.getConnection(url,
          System.getProperty("kkrepo.usagePerf.username", "root"),
          System.getProperty("kkrepo.usagePerf.password", "usage-perf-local-only"));
          var statement = connection.createStatement()) {
        assertEquals(20_000, statement.executeUpdate(
            "UPDATE asset SET last_downloaded_at = CURRENT_TIMESTAMP WHERE MOD(id, 50) = 0"));
        assertEquals(20_000, statement.executeUpdate(
            "UPDATE asset_blob SET blob_updated_at = CURRENT_TIMESTAMP WHERE MOD(id, 50) = 0"));
        report.append("dirty_rows_per_table=20000; no vacuum after updates\n");
        for (String sql : List.of(
            "SELECT repository_id, COUNT(*), COALESCE(SUM(size), 0), COUNT(*) - COUNT(size) FROM asset GROUP BY repository_id",
            "SELECT blob_store_id, COUNT(*), COALESCE(SUM(size), 0), SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END), SUM(CASE WHEN deleted_at IS NOT NULL THEN size ELSE 0 END) FROM asset_blob GROUP BY blob_store_id")) {
          try (var plan = statement.executeQuery((backend.equals("mysql")
              ? "EXPLAIN ANALYZE " : "EXPLAIN (ANALYZE, BUFFERS) ") + sql)) {
            while (plan.next()) report.append(plan.getString(1)).append('\n');
          }
        }
      }
      repeated(report, "after_updates_blob_aggregate", 20, dao::blobStoreUsage);
      repeated(report, "after_updates_repository_aggregate", 20, dao::repositoryUsage);
    }
    Path directory = Path.of("../target/admin-storage-performance");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(backend + "-jvm.txt"), report);
    System.out.println(report);
  }

  private static <T> T timed(StringBuilder report, String label, Supplier<T> action) {
    long start = System.nanoTime();
    T result = action.get();
    report.append(label).append("_ms=").append(String.format(java.util.Locale.ROOT, "%.3f", elapsed(start))).append('\n');
    return result;
  }

  private static void repeated(StringBuilder report, String label, int iterations, Supplier<?> action) {
    List<Double> values = new ArrayList<>();
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      assertNotNull(action.get());
      values.add(elapsed(start));
    }
    distribution(report, label, values);
  }

  private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

  private static void distribution(StringBuilder report, String label, List<Double> values) {
    Collections.sort(values);
    report.append(String.format(java.util.Locale.ROOT, "%s n=%d p50_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
        label, values.size(), values.get(values.size() / 2),
        values.get((int) Math.ceil(values.size() * .95) - 1), values.getLast()));
  }
}
