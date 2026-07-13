package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssetDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void blobLifecycleDeduplicatesSoftDeletesAndRecovers() {
    long blobStoreId = insertBlobStore("asset-store");
    AssetDao dao = new AssetDao(jdbc(), jsonColumns());
    AssetBlobRecord candidate = blob(blobStoreId, "content/a.jar", "a".repeat(64), 12);

    AssetBlobRecord inserted = dao.insertBlobOrFindExisting(candidate);
    AssetBlobRecord duplicate = dao.insertBlobOrFindExisting(candidate);

    assertEquals(inserted.id(), duplicate.id());
    assertEquals(Map.of("origin", "test"), duplicate.attributes());
    assertTrue(dao.findReusableBlobBySha256(blobStoreId, candidate.sha256(), candidate.size()).isPresent());

    assertEquals(1, dao.markBlobDeletedIfUnreferenced(inserted.id(), "test cleanup"));
    assertFalse(dao.findReusableBlobBySha256(blobStoreId, candidate.sha256(), candidate.size()).isPresent());
    assertEquals(1, dao.countDeletedBlobsAwaitingGc());

    AssetBlobRecord recovered = dao.recoverDeletedBlobBySha256(
        blobStoreId, candidate.sha256(), candidate.size()).orElseThrow();
    assertEquals(inserted.id(), recovered.id());
    assertEquals(0, dao.countDeletedBlobsAwaitingGc());
    assertTrue(dao.hasLiveBlobForObjectKeyHash(blobStoreId, candidate.objectKeyHash()));
  }

  @Test
  void assetUniquenessLookupsAndPrefixQueriesUseRealIndexes() {
    long repositoryId = insertRepository("maven-one", "maven2");
    long secondRepositoryId = insertRepository("maven-two", "maven2");
    AssetDao dao = new AssetDao(jdbc(), jsonColumns());

    long firstId = dao.insertAsset(asset(repositoryId, "com/acme/a/1.0/a-1.0.jar"));
    long duplicateId = dao.insertAsset(asset(repositoryId, "com/acme/a/1.0/a-1.0.jar"));
    long secondId = dao.insertAsset(asset(repositoryId, "com/acme/b/1.0/b-1.0.jar"));
    long otherRepositoryId = dao.insertAsset(asset(secondRepositoryId, "com/acme/a/1.0/a-1.0.jar"));

    assertEquals(firstId, duplicateId);
    assertNotEquals(firstId, secondId);
    assertNotEquals(firstId, otherRepositoryId);
    assertEquals(firstId, dao.findAssetByPath(
        repositoryId, "com/acme/a/1.0/a-1.0.jar").orElseThrow().id());
    assertEquals(
        Map.of(repositoryId, firstId, secondRepositoryId, otherRepositoryId),
        dao.findAssetsByPathHash(
                List.of(repositoryId, secondRepositoryId),
                HashColumns.pathHash("com/acme/a/1.0/a-1.0.jar"))
            .entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().id())));
    assertEquals(
        List.of("com/acme/a/1.0/a-1.0.jar", "com/acme/b/1.0/b-1.0.jar"),
        dao.listAssetsByPrefix(repositoryId, "com/acme/").stream().map(AssetRecord::path).toList());
    assertTrue(dao.findAssetsByPathHash(List.of(), HashColumns.pathHash("unused")).isEmpty());
  }

  @Test
  void reconciliationAndGcClaimOnlyUnreferencedDeletedBlobs() {
    long repositoryId = insertRepository("raw-hosted", "raw");
    long blobStoreId = jdbc().queryForObject(
        "SELECT blob_store_id FROM repository WHERE id = ?", Long.class, repositoryId);
    AssetDao dao = new AssetDao(jdbc(), jsonColumns());
    long referencedBlobId = dao.insertBlob(blob(blobStoreId, "referenced.bin", "b".repeat(64), 5));
    long orphanBlobId = dao.insertBlob(blob(blobStoreId, "orphan.bin", "c".repeat(64), 7));
    AssetRecord referencedAsset = asset(repositoryId, "files/referenced.bin");
    dao.insertAsset(new AssetRecord(
        referencedAsset.id(),
        referencedAsset.repositoryId(),
        referencedAsset.componentId(),
        referencedBlobId,
        referencedAsset.format(),
        referencedAsset.path(),
        referencedAsset.pathHash(),
        referencedAsset.name(),
        referencedAsset.kind(),
        referencedAsset.contentType(),
        referencedAsset.size(),
        referencedAsset.lastDownloadedAt(),
        referencedAsset.lastUpdatedAt(),
        referencedAsset.attributes()));

    AssetDao.BlobReconcileWindow window = inTransaction(
        () -> dao.markUnreferencedBlobsDeletedAfter(0, 10, 10, "reconcile"));

    assertEquals(1, window.marked());
    assertEquals(2, window.scanned());
    assertTrue(dao.lockLiveBlobById(referencedBlobId).isPresent());
    assertTrue(dao.lockDeletedBlobById(orphanBlobId).isPresent());

    List<AssetBlobRecord> claimed = inTransaction(() -> dao.claimDeletedBlobsForGc(
        10, Instant.now().plusSeconds(1), Instant.now().minusSeconds(60)));
    assertEquals(List.of(orphanBlobId), claimed.stream().map(AssetBlobRecord::id).toList());
    assertEquals(1, dao.releaseBlobGcClaim(orphanBlobId));
    assertEquals(1, dao.hardDeleteBlobByIdIfDeleted(orphanBlobId));
    assertTrue(dao.findBlobById(orphanBlobId).isEmpty());
  }

  private static AssetBlobRecord blob(long blobStoreId, String objectKey, String sha256, long size) {
    String blobRef = "default@" + objectKey;
    return new AssetBlobRecord(
        null,
        blobStoreId,
        blobRef,
        HashColumns.blobRefHash(blobRef),
        objectKey,
        HashColumns.objectKeyHash(objectKey),
        "1".repeat(40),
        sha256,
        "2".repeat(32),
        size,
        "application/octet-stream",
        "tester",
        "127.0.0.1",
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("origin", "test"));
  }

  private static AssetRecord asset(long repositoryId, String path) {
    return new AssetRecord(
        null,
        repositoryId,
        null,
        null,
        RepositoryFormat.MAVEN2,
        path,
        HashColumns.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1),
        "ARTIFACT",
        "application/octet-stream",
        10L,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("tested", true));
  }
}
