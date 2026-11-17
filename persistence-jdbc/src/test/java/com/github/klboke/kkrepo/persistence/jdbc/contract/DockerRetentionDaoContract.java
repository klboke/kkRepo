package com.github.klboke.kkrepo.persistence.jdbc.contract;

import static org.junit.jupiter.api.Assertions.*;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The same retention and cross-connection locking contract runs on MySQL and PostgreSQL. */
public final class DockerRetentionDaoContract {
  private DockerRetentionDaoContract() { }

  public static void verify(JdbcTemplate jdbc, PersistenceStores stores) throws Exception {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    var dao = stores.dockerRegistry();
    long store = stores.blobStores().insert(new BlobStoreRecord(null, "docker-retention", "s3",
        null, null, "bucket", "", Map.of()));
    long repo = stores.repositories().insert(new RepositoryRecord(null, "docker-retention", RepositoryFormat.DOCKER,
        RepositoryType.HOSTED, "docker-hosted", true, store, null, null, null, null, "ALLOW", false, Map.of()));
    var old = publish(stores, tx, repo, "team/api", "old", "z-old", "alias");
    Thread.sleep(5);
    var middle = publish(stores, tx, repo, "team/api", "middle", "m-middle");
    Thread.sleep(5);
    var newest = publish(stores, tx, repo, "team/api", "new", "a-new");
    var untagged = publish(stores, tx, repo, "team/api", "child");
    var other = publish(stores, tx, repo, "team/web", "other", "latest");
    assertEquals(middle.id(), dao.findCleanupRetentionBoundary(repo, "team/api", 2).orElseThrow().manifestId());
    assertEquals(newest.id(), dao.findCleanupRetentionBoundary(repo, "team/api", 1).orElseThrow().manifestId());
    assertEquals(other.id(), dao.findCleanupRetentionBoundary(repo, "team/web", 1).orElseThrow().manifestId());
    assertTrue(dao.findCleanupRetentionBoundary(repo, "team/api", 4).isEmpty(), "Aliases and untagged children are not versions");
    assertTrue(dao.findCleanupRetentionBoundary(repo + 999, "team/api", 1).isEmpty());
    assertTrue(dao.findCleanupRetentionBoundary(repo, "missing", 1).isEmpty());
    assertTrue(dao.findCleanupRetentionBoundary(repo, "team/api", 0).isEmpty());
    // The boundary is independent of keyset pagination and lexical tag ordering.
    assertEquals(old.assetId(), dao.listManifestCleanupCandidatesPage(repo, 0, 1).getFirst().assetId());
    Thread.sleep(5);
    var republished = tx.execute(s -> {
      dao.lockRepositoryForMutation(repo);
      return dao.upsertManifest(old);
    });
    assertEquals(old.id(), republished.id());
    assertTrue(republished.updatedAt().isAfter(newest.updatedAt()), "An identical re-push must refresh the update time on both databases");
    assertEquals(old.id(), dao.findCleanupRetentionBoundary(repo, "team/api", 1).orElseThrow().manifestId());
    tx.executeWithoutResult(s -> {
      dao.lockRepositoryForMutation(repo);
      dao.replaceManifestReferences(newest.id(), List.of(new DockerManifestReferenceRecord(null,
          newest.id(), repo, newest.imageName(), untagged.digest(), PersistenceHashes.sha256(untagged.digest()),
          "MANIFEST", untagged.mediaType(), 10L, Map.of(), Map.of())));
    });
    assertTrue(dao.hasLiveManifestReference(repo, untagged.digest()));
    assertFalse(dao.hasLiveManifestReference(repo + 999, untagged.digest()));
    assertFalse(dao.hasLiveManifestReference(repo, old.digest()));
    tx.executeWithoutResult(s -> {
      dao.lockRepositoryForMutation(repo);
      dao.deleteManifest(repo, newest.imageName(), newest.digest());
    });
    assertFalse(dao.hasLiveManifestReference(repo, untagged.digest()), "Deleted parents cannot pin children forever");
    assertTrue(dao.findCleanupRetentionBoundary(repo, "team/api", 3).isEmpty(), "Deleted versions do not fill retention slots");
    assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> dao.lockRepositoryForCleanup(repo + 999)));
    verifyLocking(tx, dao, repo);
  }

  private static void verifyLocking(TransactionTemplate tx, DockerRegistryDao dao, long repo) throws Exception {
    CountDownLatch publisherLocked = new CountDownLatch(1);
    CountDownLatch releasePublisher = new CountDownLatch(1);
    CountDownLatch cleanupAttempted = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(3)) {
      Future<?> publisher = workers.submit(() -> tx.executeWithoutResult(s -> {
        dao.lockRepositoryForMutation(repo);
        publisherLocked.countDown();
        await(releasePublisher);
      }));
      try {
        assertTrue(publisherLocked.await(10, TimeUnit.SECONDS));
        workers.submit(() -> tx.executeWithoutResult(s -> dao.lockRepositoryForMutation(repo))).get(10, TimeUnit.SECONDS);
        Future<?> cleanup = workers.submit(() -> tx.executeWithoutResult(s -> {
          cleanupAttempted.countDown();
          dao.lockRepositoryForCleanup(repo);
        }));
        assertTrue(cleanupAttempted.await(10, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> cleanup.get(200, TimeUnit.MILLISECONDS),
            "Cleanup must wait for another replica's in-flight publication");
        releasePublisher.countDown();
        publisher.get(10, TimeUnit.SECONDS);
        cleanup.get(10, TimeUnit.SECONDS);
      } finally {
        releasePublisher.countDown();
      }
    }
    CountDownLatch cleanupLocked = new CountDownLatch(1);
    CountDownLatch releaseCleanup = new CountDownLatch(1);
    CountDownLatch mutationAttempted = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      Future<?> cleanup = workers.submit(() -> tx.executeWithoutResult(s -> {
        dao.lockRepositoryForCleanup(repo);
        cleanupLocked.countDown();
        await(releaseCleanup);
      }));
      try {
        assertTrue(cleanupLocked.await(10, TimeUnit.SECONDS));
        Future<?> mutation = workers.submit(() -> tx.executeWithoutResult(s -> {
          mutationAttempted.countDown();
          dao.lockRepositoryForMutation(repo);
        }));
        assertTrue(mutationAttempted.await(10, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS),
            "Another replica cannot retag between retention revalidation and deletion");
        releaseCleanup.countDown();
        cleanup.get(10, TimeUnit.SECONDS);
        mutation.get(10, TimeUnit.SECONDS);
      } finally {
        releaseCleanup.countDown();
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock test timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static DockerManifestRecord publish(PersistenceStores stores, TransactionTemplate tx,
      long repo, String image, String payload, String... tags) {
    return tx.execute(s -> {
      var dao = stores.dockerRegistry();
      dao.lockRepositoryForMutation(repo);
      String digest = "sha256:" + HexFormat.of().formatHex(PersistenceHashes.sha256(payload));
      String path = image + "/" + digest;
      long asset = stores.assets().insertAsset(new AssetRecord(null, repo, null, null,
          RepositoryFormat.DOCKER, path, PersistenceHashes.pathHash(path), digest, "MANIFEST",
          "application/vnd.oci.image.manifest.v1+json", 10L, null, null, Map.of()));
      var manifest = dao.upsertManifest(new DockerManifestRecord(null, repo, image,
          PersistenceHashes.sha256(image), "sha256", digest, PersistenceHashes.sha256(digest),
          "application/vnd.oci.image.manifest.v1+json", null, null, null, asset, 10,
          "test", null, null, Map.of(), null, null));
      for (String tag : tags) dao.upsertTag(new DockerTagRecord(null, repo, image,
          PersistenceHashes.sha256(image), tag, PersistenceHashes.sha256(tag), manifest.id(),
          digest, "test", null, null, null));
      return manifest;
    });
  }
}
