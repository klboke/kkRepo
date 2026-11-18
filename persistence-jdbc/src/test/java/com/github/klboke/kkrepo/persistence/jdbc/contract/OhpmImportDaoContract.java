package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.ItemState;
import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmImportDao.JobState;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class OhpmImportDaoContract {
  private OhpmImportDaoContract() {}

  public static void verify(JdbcTemplate jdbc, PersistenceStores stores) throws Exception {
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    long store = stores.blobStores().insert(new BlobStoreRecord(
        null, "ohpm-import", "s3", null, null, "bucket", "", Map.of()));
    long repository = stores.repositories().insert(new RepositoryRecord(null, "ohpm-import",
        RepositoryFormat.OHPM, RepositoryType.HOSTED, "ohpm-hosted", true, store,
        null, null, null, null, "ALLOW", false, Map.of()));
    OhpmImportDao dao = stores.ohpmImports();
    OhpmRepositoryDaoContract.verify(stores, repository);
    var job = dao.create(repository, OhpmImportDao.Source.DIRECTORY, null, "admin");
    assertEquals(JobState.PLANNING, job.state());
    assertEquals(job, dao.listJobs(repository, 10).getFirst());
    assertThrows(IllegalStateException.class, () -> dao.lockJob(job.id()));
    assertThrows(IllegalArgumentException.class,
        () -> dao.create(repository, OhpmImportDao.Source.REGISTRY, null, "admin"));
    assertFalse(transactions.<Boolean>execute(status -> dao.transition(job.id(), JobState.PLANNING, JobState.READY)));
    String key = "a".repeat(64);
    Map<String, Object> plan = Map.of("integrity", "sha512-pinned", "harSize", 42L, "tags", Map.of("latest", "1.0.0"));
    var first = transactions.execute(status -> dao.addPlan(
        job.id(), key, "@scope/library", "1.0.0", plan, ItemState.PLANNED, "Checksums verified"));
    var replay = transactions.execute(status -> dao.addPlan(
        job.id(), key, "@scope/library", "1.0.0", plan, ItemState.PLANNED, "Checksums verified"));
    assertEquals(first, replay);
    assertEquals(1, dao.items(job.id()).size());
    assertThrows(IllegalArgumentException.class, () -> transactions.execute(status ->
        dao.addPlan(job.id(), "d".repeat(64), "oversized", "1.0.0",
            Map.of("description", "x".repeat(2 * 1024 * 1024)), ItemState.PLANNED, "Oversized metadata")));
    assertTrue(dao.findItem(job.id(), "d".repeat(64)).isEmpty());
    assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
        dao.addPlan(job.id(), key, "@scope/library", "1.0.0", Map.of("integrity", "changed"),
            ItemState.PLANNED, "Changed source")));
    assertTrue(transactions.<Boolean>execute(status -> dao.transition(job.id(), JobState.PLANNING, JobState.READY)));
    assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
        dao.addPlan(job.id(), "b".repeat(64), "other", "1.0.0", plan, ItemState.PLANNED, "New item")));
    assertTrue(transactions.<Boolean>execute(status -> dao.transition(job.id(), JobState.READY, JobState.APPLYING)));
    assertFalse(transactions.<Boolean>execute(status -> dao.transition(job.id(), JobState.APPLYING, JobState.COMPLETED)));
    transactions.executeWithoutResult(status -> {
      assertTrue(dao.result(job.id(), key, ItemState.IMPORTED, "Imported"));
      status.setRollbackOnly();
    });
    assertEquals(ItemState.PLANNED, dao.findItem(job.id(), key).orElseThrow().state());
    assertTrue(transactions.<Boolean>execute(status -> dao.result(job.id(), key, ItemState.FAILED, "Retryable source failure")));
    assertTrue(transactions.<Boolean>execute(status -> dao.result(job.id(), key, ItemState.MANUAL, "Source changed")));
    assertFalse(transactions.<Boolean>execute(status -> dao.transition(job.id(), JobState.APPLYING, JobState.COMPLETED)));

    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Callable<Boolean>> imports = java.util.stream.IntStream.range(0, 8)
          .mapToObj(index -> (Callable<Boolean>) () ->
              transactions.execute(status -> dao.result(job.id(), key, ItemState.IMPORTED, "Imported"))).toList();
      int winners = 0;
      for (var completed : executor.invokeAll(imports)) if (completed.get()) winners++;
      assertEquals(1, winners);
    }
    assertFalse(transactions.<Boolean>execute(status -> dao.result(job.id(), key, ItemState.FAILED, "Late failed request")));
    assertEquals(ItemState.IMPORTED, dao.findItem(job.id(), key).orElseThrow().state());
    assertTrue(transactions.<Boolean>execute(status -> dao.transition(job.id(), JobState.APPLYING, JobState.COMPLETED)));
    assertThrows(IllegalStateException.class,
        () -> transactions.execute(status -> dao.result(job.id(), key, ItemState.SKIPPED, "Replay")));
    assertThrows(IllegalArgumentException.class,
        () -> transactions.execute(status -> dao.transition(job.id(), JobState.COMPLETED, JobState.APPLYING)));

    var concurrent = dao.create(repository, OhpmImportDao.Source.REGISTRY, repository + 100, "admin");
    CountDownLatch snapshot = new CountDownLatch(1);
    CountDownLatch committed = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reader = executor.submit(() -> transactions.execute(status -> {
        assertTrue(dao.items(concurrent.id()).isEmpty());
        snapshot.countDown();
        try {
          assertTrue(committed.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
          throw new IllegalStateException(error);
        }
        return dao.addPlan(concurrent.id(), key, "@scope/library", "1.0.0", plan,
            ItemState.PLANNED, "Checksums verified");
      }));
      assertTrue(snapshot.await(10, TimeUnit.SECONDS));
      var current = transactions.execute(status -> dao.addPlan(concurrent.id(), key,
          "@scope/library", "1.0.0", plan, ItemState.PLANNED, "Checksums verified"));
      committed.countDown();
      assertEquals(current, reader.get(10, TimeUnit.SECONDS));
    }
    assertEquals(1, dao.items(concurrent.id()).size());
    assertTrue(transactions.<Boolean>execute(status -> dao.transition(concurrent.id(), JobState.PLANNING, JobState.CANCELLED)));
    assertThrows(IllegalStateException.class,
        () -> transactions.execute(status -> dao.addPlan(concurrent.id(), "c".repeat(64),
            "other", "1.0.0", plan, ItemState.PLANNED, "New item")));
    var bounded = dao.create(repository, OhpmImportDao.Source.DIRECTORY, null, "admin");
    var largePlan = Map.<String, Object>of("description", "x".repeat(2 * 1024 * 1024 - 100));
    for (int index = 0; index < 8; index++) {
      String itemKey = "d".repeat(63) + index;
      transactions.executeWithoutResult(status -> dao.addPlan(bounded.id(), itemKey,
          "large", "1.0.0", largePlan, ItemState.PLANNED, "Bounded metadata"));
    }
    assertThrows(IllegalStateException.class, () -> transactions.execute(status ->
        dao.addPlan(bounded.id(), "e".repeat(64), "overflow", "1.0.0",
            Map.of("description", "x".repeat(1024)), ItemState.PLANNED, "Job metadata limit")));
    assertTrue(dao.findItem(bounded.id(), "e".repeat(64)).isEmpty());
    stores.repositories().deleteById(repository);
    assertTrue(dao.findJob(job.id()).isEmpty());
    assertTrue(dao.findJob(concurrent.id()).isEmpty());
    assertTrue(dao.items(job.id()).isEmpty());
  }
}
