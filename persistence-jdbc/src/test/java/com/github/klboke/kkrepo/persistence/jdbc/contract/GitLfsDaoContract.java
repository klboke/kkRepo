package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs against both real backends, including independent connections competing for one OID. */
public final class GitLfsDaoContract {
  private GitLfsDaoContract() {}
  public static void verify(JdbcTemplate jdbc, PersistenceStores stores) throws Exception {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    long store = stores.blobStores().insert(new BlobStoreRecord(null, "lfs-contract", "s3", null, null, "bucket", "", Map.of()));
    long repository = stores.repositories().insert(new RepositoryRecord(null, "lfs-contract", RepositoryFormat.GITLFS,
        RepositoryType.HOSTED, "gitlfs-hosted", true, store, null, null, null, null, "ALLOW", false, Map.of()));
    GitLfsDao dao = stores.gitLfs();
    String oid = "b".repeat(64);
    String subject = "a".repeat(64);
    assertThrows(IllegalStateException.class,
        () -> tx.execute(s -> dao.create(repository, oid, 5, subject, store + 100)));
    var first = tx.execute(s -> dao.create(repository, oid, 5, subject, store));
    var second = tx.execute(s -> dao.create(repository, oid, 5, subject, store));
    assertTrue(tx.execute(s -> dao.claim(first.id(), "wrong-owner", "blob://bucket/one", "one")).isEmpty());
    var claimed = tx.execute(s -> dao.claim(first.id(), subject, "blob://bucket/one", "one")).orElseThrow();
    assertTrue(tx.execute(s -> dao.claim(second.id(), subject, "blob://bucket/two", "two")).isEmpty());
    assertFalse(tx.<Boolean>execute(s -> dao.renew(first.id(), claimed.fence() + 1)));
    assertTrue(tx.<Boolean>execute(s -> dao.renew(first.id(), claimed.fence())));
    dao.multipartStarted(first.id(), claimed.fence(), "provider-upload-id");
    assertEquals("provider-upload-id", dao.find(first.id()).orElseThrow().multipartId());
    Timestamp past = Timestamp.from(Instant.parse("2000-01-01T00:00:00Z"));
    jdbc.update("UPDATE gitlfs_object SET lease_until = ? WHERE repository_id = ?", past, repository);
    var takeover = tx.execute(s -> dao.claim(second.id(), subject, "blob://bucket/two", "two")).orElseThrow();
    assertTrue(takeover.fence() > claimed.fence());
    assertFalse(tx.<Boolean>execute(s -> dao.lockForPublication(first.id(), claimed.fence())));
    long asset = tx.execute(s -> {
      assertTrue(dao.lockForPublication(second.id(), takeover.fence()));
      long id = stores.assets().insertAsset(new AssetRecord(null, repository, null, null, RepositoryFormat.GITLFS,
          oid, PersistenceHashes.pathHash(oid), oid, "gitlfs", "application/octet-stream", 5L, null, Instant.now(), Map.of()));
      dao.published(second.id(), takeover.fence(), id);
      return id;
    });
    assertEquals("PUBLISHED", dao.find(second.id()).orElseThrow().state());
    jdbc.update("UPDATE gitlfs_upload SET expires_at = ? WHERE upload_id = ?", past, second.id());
    assertEquals(1, tx.<Integer>execute(s -> dao.prunePublished(100)));
    assertTrue(dao.find(second.id()).isEmpty());
    assertTrue(stores.assets().findAssetById(asset).isPresent(), "Expired replay context must not delete a published object");
    var beforeDelete = tx.execute(s -> dao.create(repository, oid, 5, subject, store));
    stores.assets().deleteAssetById(asset);
    assertTrue(tx.execute(s -> dao.claim(beforeDelete.id(), subject, "blob://bucket/stale", "stale")).isEmpty());
    var afterDelete = tx.execute(s -> dao.create(repository, oid, 5, subject, store));
    assertTrue(afterDelete.generation() > beforeDelete.generation());
    assertTrue(tx.execute(s -> dao.claim(beforeDelete.id(), subject, "blob://bucket/stale", "stale")).isEmpty());
    var newClaim = tx.execute(s -> dao.claim(afterDelete.id(), subject, "blob://bucket/new", "new")).orElseThrow();
    jdbc.update("UPDATE repository SET updated_at = ? WHERE id = ?", Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")), repository);
    assertFalse(tx.<Boolean>execute(s -> dao.lockForPublication(afterDelete.id(), newClaim.fence())));
    tx.executeWithoutResult(s -> dao.abandon(afterDelete.id(), newClaim.fence()));
    jdbc.update("UPDATE gitlfs_upload SET expires_at = ? WHERE upload_id = ?", past, afterDelete.id());
    var cleanup = tx.execute(s -> dao.claimExpired(100));
    var garbage = cleanup.stream().filter(u -> u.id().equals(afterDelete.id())).findFirst().orElseThrow();
    assertFalse(tx.<Boolean>execute(s -> dao.lockForPublication(garbage.id(), garbage.fence())));
    dao.reaped(garbage.id(), "wrong-token");
    assertEquals("REAPING", dao.find(garbage.id()).orElseThrow().state());
    dao.reaped(garbage.id(), garbage.cleanupToken());
    assertEquals("GARBAGE", dao.find(garbage.id()).orElseThrow().state(), "Keep key for late remote completion recovery");
    jdbc.update("UPDATE gitlfs_upload SET expires_at = ?, cleanup_after = ? WHERE upload_id = ?", past, past, garbage.id());
    var finalCleanup = tx.execute(s -> dao.claimExpired(100)).stream().filter(u -> u.id().equals(garbage.id())).findFirst().orElseThrow();
    dao.reaped(garbage.id(), finalCleanup.cleanupToken());
    assertTrue(dao.find(garbage.id()).isEmpty());

    String raceOid = "c".repeat(64);
    try (var threads = Executors.newFixedThreadPool(4)) {
      List<Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<Boolean>) () -> {
        var upload = tx.execute(s -> dao.create(repository, raceOid, 1, subject, store));
        return tx.execute(s -> dao.claim(upload.id(), subject, "blob://bucket/race-" + i, "race-" + i)).isPresent();
      }).toList();
      int winners = 0;
      for (var result : threads.invokeAll(calls)) if (result.get()) winners++;
      assertEquals(1, winners);
    }
    jdbc.update("UPDATE repository SET write_policy = 'DENY' WHERE id = ?", repository);
    assertThrows(IllegalStateException.class, () -> tx.execute(s -> dao.create(repository, "d".repeat(64), 1, subject, store)));
    // Repository deletion retains physical cleanup context while removing logical object state.
    stores.repositories().deleteById(repository);
    assertTrue(dao.find(first.id()).isPresent());
    assertFalse(tx.<Boolean>execute(s -> dao.lockForPublication(first.id(), claimed.fence())));
  }
}
