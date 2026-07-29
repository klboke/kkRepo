package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetPublicIdentifierRecord.IdentifierType;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssetDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void blobLifecycleDeduplicatesSoftDeletesAndRecovers() {
    long blobStoreId = insertBlobStore("asset-store");
    AssetDao dao = new JdbcAssetDao(jdbc(), jsonColumns());
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
    AssetDao dao = new JdbcAssetDao(jdbc(), jsonColumns());

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
  void managementAssetPageUsesStableIdCursorAndJoinsBlobMetadata() {
    long repositoryId = insertRepository("windows-artifacts", "raw");
    long blobStoreId = jdbc().queryForObject(
        "SELECT blob_store_id FROM repository WHERE id = ?", Long.class, repositoryId);
    AssetDao dao = new JdbcAssetDao(jdbc(), jsonColumns());
    long firstBlobId = dao.insertBlob(blob(
        blobStoreId, "windows/first.zip", "a".repeat(64), 11));
    long secondBlobId = dao.insertBlob(blob(
        blobStoreId, "windows/second.zip", "b".repeat(64), 12));
    long firstId = dao.insertAsset(assetWithBlob(
        repositoryId, firstBlobId, RepositoryFormat.RAW, "windows/first.zip"));
    long secondId = dao.insertAsset(assetWithBlob(
        repositoryId, secondBlobId, RepositoryFormat.RAW, "windows/second.zip"));
    long thirdId = dao.insertAsset(assetWithBlob(
        repositoryId, null, RepositoryFormat.RAW, "windows/no-blob.zip"));

    List<AssetDao.AssetWithBlob> firstPage =
        dao.listAssetWithBlobPage(repositoryId, 0, 2);
    List<AssetDao.AssetWithBlob> secondPage =
        dao.listAssetWithBlobPage(repositoryId, firstId, 2);

    assertEquals(List.of(firstId, secondId),
        firstPage.stream().map(row -> row.asset().id()).toList());
    assertEquals("a".repeat(64), firstPage.getFirst().blob().sha256());
    assertEquals(List.of(secondId, thirdId),
        secondPage.stream().map(row -> row.asset().id()).toList());
    assertEquals(null, secondPage.get(1).blob());
  }

  @Test
  void publicIdentifiersEnforceCollisionRulesAndRemainAsDeletionTombstones() {
    long repositoryId = insertRepository("windows-components", "raw");
    AssetDao dao = new JdbcAssetDao(jdbc(), jsonColumns());
    long assetId = dao.insertAsset(assetWithBlob(
        repositoryId, null, RepositoryFormat.RAW, "tools/setup.exe"));
    String nativeOpaque = String.format("%032x", assetId);
    String nexusOpaque = "fedcba98765432100123456789abcdef";

    assertTrue(dao.tryInsertPublicIdentifier(publicIdentifier(
        repositoryId, nativeOpaque, assetId, assetId, IdentifierType.KKREPO_NATIVE)));
    assertTrue(dao.tryInsertPublicIdentifier(publicIdentifier(
        repositoryId, nexusOpaque, assetId, null, IdentifierType.NEXUS_ALIAS)));
    assertFalse(dao.tryInsertPublicIdentifier(publicIdentifier(
        repositoryId, nexusOpaque, assetId, null, IdentifierType.NEXUS_ALIAS)));
    assertEquals(assetId, dao.findNativePublicIdentifier(assetId).orElseThrow().assetId());
    assertEquals(assetId, dao.lockPublicIdentifier(repositoryId, nexusOpaque).orElseThrow().assetId());
    assertEquals(assetId, dao.lockNativePublicIdentifier(assetId).orElseThrow().assetId());

    assertEquals(1, dao.deleteAssetById(assetId));

    assertTrue(dao.findNativePublicIdentifier(assetId).isEmpty());
    assertEquals(null, dao.findPublicIdentifier(repositoryId, nativeOpaque).orElseThrow().assetId());
    assertEquals(null, dao.findPublicIdentifier(repositoryId, nexusOpaque).orElseThrow().assetId());
    assertFalse(dao.tryInsertPublicIdentifier(publicIdentifier(
        repositoryId, nexusOpaque, null, null, IdentifierType.NEXUS_ALIAS)));
  }

  @Test
  void reconciliationAndGcClaimOnlyUnreferencedDeletedBlobs() {
    long repositoryId = insertRepository("raw-hosted", "raw");
    long blobStoreId = jdbc().queryForObject(
        "SELECT blob_store_id FROM repository WHERE id = ?", Long.class, repositoryId);
    AssetDao dao = new JdbcAssetDao(jdbc(), jsonColumns());
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

  private static AssetPublicIdentifierRecord publicIdentifier(
      long repositoryId,
      String opaqueId,
      Long assetId,
      Long nativeAssetId,
      IdentifierType type) {
    return new AssetPublicIdentifierRecord(
        null, repositoryId, opaqueId, assetId, nativeAssetId, type,
        "http://nexus.example/", 99L, Instant.EPOCH);
  }

  private static AssetRecord assetWithBlob(
      long repositoryId, Long blobId, RepositoryFormat format, String path) {
    return new AssetRecord(
        null,
        repositoryId,
        null,
        blobId,
        format,
        path,
        HashColumns.pathHash(path),
        path.substring(path.lastIndexOf('/') + 1),
        "ARTIFACT",
        "application/zip",
        10L,
        null,
        Instant.parse("2026-01-01T00:00:00Z"),
        Map.of("tested", true));
  }
}
