package com.github.klboke.kkrepo.persistence.mysql.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao.Release;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpmReleaseIndexDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  private static final Instant FIRST_PUBLISHED = Instant.parse("2026-07-19T10:00:00Z");
  private static final Instant SECOND_PUBLISHED = Instant.parse("2026-07-19T11:00:00Z");
  private static final Instant INDEXED_AT = Instant.parse("2026-07-19T12:00:00Z");

  @Test
  void indexIsRevisionFencedAndSupportsTargetedMaturityQueries() {
    long repositoryId = insertRepository("npm-proxy", "npm");
    long firstBlobId = insertBlob(repositoryId, "npm/demo/packument-v1.json");
    long secondBlobId = insertBlob(repositoryId, "npm/demo/packument-v2.json");
    long assetId = insertPackageRootAsset(repositoryId, firstBlobId);
    NpmReleaseIndexDao dao = stores().npmReleaseIndexes();
    List<Release> firstRevision = List.of(
        new Release(0, "1.0.0", FIRST_PUBLISHED, null, "demo-1.0.0.tgz"),
        new Release(1, "2.0.0", SECOND_PUBLISHED, null, "demo-2.0.0.tgz"),
        new Release(2, "broken", null, "missing-publish-time", "demo-2.0.0.tgz"));

    assertTrue(dao.findSnapshot(assetId, firstBlobId).isEmpty());
    assertTrue(dao.findTarballPolicy(
        assetId, firstBlobId, "missing.tgz", null, null).isEmpty());
    assertFalse(dao.hasMaturityBoundary(assetId, firstBlobId, null, SECOND_PUBLISHED));
    assertFalse(dao.hasMaturityBoundary(
        assetId, firstBlobId, SECOND_PUBLISHED, SECOND_PUBLISHED));
    assertTrue(dao.findNextPublishedAfter(assetId, firstBlobId, null).isEmpty());
    assertFalse(inTransaction(() -> dao.replaceIfCurrent(
        Long.MAX_VALUE, firstBlobId, true, List.of(), null)));

    assertTrue(inTransaction(() -> dao.replaceIfCurrent(
        assetId, firstBlobId, false, firstRevision, INDEXED_AT)));
    var snapshot = dao.findSnapshot(assetId, firstBlobId).orElseThrow();
    assertFalse(snapshot.status().completePublishTimes());
    assertEquals(firstRevision, snapshot.releases());
    assertEquals(
        List.of(firstRevision.get(1), firstRevision.get(2)),
        dao.findByTarball(assetId, firstBlobId, "demo-2.0.0.tgz").orElseThrow());
    var tarballPolicy = dao.findTarballPolicy(
        assetId, firstBlobId, "demo-2.0.0.tgz", FIRST_PUBLISHED, SECOND_PUBLISHED)
        .orElseThrow();
    assertEquals(3, tarballPolicy.status().releaseCount());
    assertTrue(tarballPolicy.maturityBoundaryCrossed());
    assertEquals(List.of(firstRevision.get(1), firstRevision.get(2)), tarballPolicy.releases());
    assertTrue(dao.findByTarball(assetId, firstBlobId, "missing.tgz").orElseThrow().isEmpty());
    assertTrue(dao.hasMaturityBoundary(
        assetId, firstBlobId, FIRST_PUBLISHED, SECOND_PUBLISHED));
    assertFalse(dao.hasMaturityBoundary(
        assetId, firstBlobId, SECOND_PUBLISHED, SECOND_PUBLISHED.plusSeconds(1)));
    assertEquals(
        SECOND_PUBLISHED,
        dao.findNextPublishedAfter(assetId, firstBlobId, FIRST_PUBLISHED).orElseThrow());

    jdbc().update(
        "UPDATE npm_release_index_revision SET release_count = 99 WHERE package_root_asset_id = ?",
        assetId);
    assertTrue(dao.findSnapshot(assetId, firstBlobId).isEmpty());
    jdbc().update(
        "UPDATE npm_release_index_revision SET release_count = ? WHERE package_root_asset_id = ?",
        firstRevision.size(), assetId);

    assertFalse(inTransaction(() -> dao.replaceIfCurrent(
        assetId, secondBlobId, true, List.of(), INDEXED_AT)));
    jdbc().update("UPDATE asset SET asset_blob_id = ? WHERE id = ?", secondBlobId, assetId);
    assertFalse(inTransaction(() -> dao.replaceIfCurrent(
        assetId, firstBlobId, true, List.of(), INDEXED_AT)));

    List<Release> secondRevision = List.of(
        new Release(0, "3.0.0", SECOND_PUBLISHED, null, null));
    assertTrue(inTransaction(() -> dao.replaceIfCurrent(
        assetId, secondBlobId, true, secondRevision, INDEXED_AT.plusSeconds(1))));
    assertTrue(dao.findStatus(assetId, firstBlobId).isEmpty());
    assertTrue(dao.findByTarball(assetId, firstBlobId, "demo-2.0.0.tgz").isEmpty());
    assertEquals(secondRevision, dao.findSnapshot(assetId, secondBlobId).orElseThrow().releases());

    jdbc().update("UPDATE asset SET asset_blob_id = NULL WHERE id = ?", assetId);
    assertFalse(inTransaction(() -> dao.replaceIfCurrent(
        assetId, secondBlobId, true, List.of(), null)));
    jdbc().update("UPDATE asset SET asset_blob_id = ? WHERE id = ?", secondBlobId, assetId);
    assertTrue(inTransaction(() -> dao.replaceIfCurrent(
        assetId, secondBlobId, true, null, null)));
    assertTrue(dao.findSnapshot(assetId, secondBlobId).orElseThrow().releases().isEmpty());
  }

  private long insertBlob(long repositoryId, String objectKey) {
    long blobStoreId = jdbc().queryForObject(
        "SELECT blob_store_id FROM repository WHERE id = ?", Long.class, repositoryId);
    String blobRef = "default@" + objectKey;
    jdbc().update("""
        INSERT INTO asset_blob
          (blob_store_id, blob_ref, blob_ref_hash, object_key, object_key_hash,
           size, content_type, attributes_json)
        VALUES (?, ?, ?, ?, ?, 1, 'application/json', JSON_OBJECT())
        """, blobStoreId, blobRef, HashColumns.blobRefHash(blobRef), objectKey,
        HashColumns.objectKeyHash(objectKey));
    return jdbc().queryForObject(
        "SELECT id FROM asset_blob WHERE blob_store_id = ? AND blob_ref_hash = ?",
        Long.class, blobStoreId, HashColumns.blobRefHash(blobRef));
  }

  private long insertPackageRootAsset(long repositoryId, long blobId) {
    String path = "demo";
    jdbc().update("""
        INSERT INTO asset
          (repository_id, asset_blob_id, format, path, path_hash, name, kind,
           content_type, size, attributes_json)
        VALUES (?, ?, 'npm', ?, ?, ?, 'PACKAGE_ROOT', 'application/json', 1, JSON_OBJECT())
        """, repositoryId, blobId, path, HashColumns.pathHash(path), path);
    return jdbc().queryForObject(
        "SELECT id FROM asset WHERE repository_id = ? AND path_hash = ?",
        Long.class, repositoryId, HashColumns.pathHash(path));
  }
}
