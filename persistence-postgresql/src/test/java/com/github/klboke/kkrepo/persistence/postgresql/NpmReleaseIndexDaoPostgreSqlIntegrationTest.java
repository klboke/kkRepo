package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao.Release;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NpmReleaseIndexDaoPostgreSqlIntegrationTest extends PostgreSqlIntegrationTestSupport {
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
        new Release(2, "broken", null, "missing-publish-time", "demo-broken.tgz"));

    assertTrue(inTransaction(() -> dao.replaceIfCurrent(
        assetId, firstBlobId, false, firstRevision, INDEXED_AT)));
    var snapshot = dao.findSnapshot(assetId, firstBlobId).orElseThrow();
    assertFalse(snapshot.status().completePublishTimes());
    assertEquals(firstRevision, snapshot.releases());
    assertEquals(
        List.of(firstRevision.get(1)),
        dao.findByTarball(assetId, firstBlobId, "demo-2.0.0.tgz").orElseThrow());
    var tarballPolicy = dao.findTarballPolicy(
        assetId, firstBlobId, "demo-2.0.0.tgz", FIRST_PUBLISHED, SECOND_PUBLISHED)
        .orElseThrow();
    assertEquals(3, tarballPolicy.status().releaseCount());
    assertTrue(tarballPolicy.maturityBoundaryCrossed());
    assertEquals(List.of(firstRevision.get(1)), tarballPolicy.releases());
    assertTrue(dao.findByTarball(assetId, firstBlobId, "missing.tgz").orElseThrow().isEmpty());
    assertTrue(dao.hasMaturityBoundary(
        assetId, firstBlobId, FIRST_PUBLISHED, SECOND_PUBLISHED));
    assertFalse(dao.hasMaturityBoundary(
        assetId, firstBlobId, SECOND_PUBLISHED, SECOND_PUBLISHED.plusSeconds(1)));
    assertEquals(
        SECOND_PUBLISHED,
        dao.findNextPublishedAfter(assetId, firstBlobId, FIRST_PUBLISHED).orElseThrow());

    assertFalse(inTransaction(() -> dao.replaceIfCurrent(
        assetId, secondBlobId, true, List.of(), INDEXED_AT)));
    jdbc().update("UPDATE asset SET asset_blob_id = ? WHERE id = ?", secondBlobId, assetId);
    assertFalse(inTransaction(() -> dao.replaceIfCurrent(
        assetId, firstBlobId, true, List.of(), INDEXED_AT)));

    List<Release> secondRevision = List.of(
        new Release(0, "3.0.0", SECOND_PUBLISHED, null, "demo-3.0.0.tgz"));
    assertTrue(inTransaction(() -> dao.replaceIfCurrent(
        assetId, secondBlobId, true, secondRevision, INDEXED_AT.plusSeconds(1))));
    assertTrue(dao.findStatus(assetId, firstBlobId).isEmpty());
    assertTrue(dao.findByTarball(assetId, firstBlobId, "demo-2.0.0.tgz").isEmpty());
    assertEquals(secondRevision, dao.findSnapshot(assetId, secondBlobId).orElseThrow().releases());
  }

  @Test
  void oldAndNewReplicasKeepTheirTarballIdentityIndexesIsolated() {
    long repositoryId = insertRepository("npm-rolling", "npm");
    long blobId = insertBlob(repositoryId, "npm/demo/packument.json");
    long assetId = insertPackageRootAsset(repositoryId, blobId);
    NpmReleaseIndexDao dao = stores().npmReleaseIndexes();
    jdbc().update("""
        INSERT INTO npm_release_index_revision
          (package_root_asset_id, source_blob_id, complete_publish_times, release_count, indexed_at)
        VALUES (?, ?, TRUE, 1, ?)
        """, assetId, blobId, java.sql.Timestamp.from(INDEXED_AT));
    jdbc().update("""
        INSERT INTO npm_release_index_entry
          (package_root_asset_id, source_blob_id, ordinal, version, version_hash,
           published_at, tarball_name, tarball_name_hash)
        VALUES (?, ?, 0, '1.0.0', ?, ?, 'demo.tgz', ?)
        """, assetId, blobId,
        com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes.sha256("1.0.0"),
        java.sql.Timestamp.from(FIRST_PUBLISHED),
        com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes.sha256("demo.tgz"));
    assertTrue(dao.findStatus(assetId, blobId).isEmpty());
    assertTrue(dao.findSnapshot(assetId, blobId).isEmpty());
    assertTrue(dao.findTarballPolicy(assetId, blobId, "demo.tgz", null, null).isEmpty());
    assertFalse(dao.hasMaturityBoundary(assetId, blobId, FIRST_PUBLISHED, SECOND_PUBLISHED));
    assertTrue(dao.findNextPublishedAfter(assetId, blobId, Instant.EPOCH).isEmpty());

    var fullPath = List.of(new Release(0, "1.0.0", FIRST_PUBLISHED, null, "signed/demo.tgz"));
    assertTrue(inTransaction(() -> dao.replaceIfCurrent(assetId, blobId, true, fullPath, INDEXED_AT)));
    // An old binary still reads its original basename and never sees full-path entries.
    assertEquals("demo.tgz", jdbc().queryForObject(
        "SELECT tarball_name FROM npm_release_index_entry WHERE package_root_asset_id = ?",
        String.class, assetId));
    assertEquals(fullPath, dao.findByTarball(assetId, blobId, "signed/demo.tgz").orElseThrow());
    // Old writers delete/rebuild their revision, without deleting a new replica's snapshot.
    jdbc().update("DELETE FROM npm_release_index_revision WHERE package_root_asset_id = ?", assetId);
    assertEquals(fullPath, dao.findSnapshot(assetId, blobId).orElseThrow().releases());
    assertTrue(inTransaction(() -> dao.replaceIfCurrent(assetId, blobId, true, fullPath, INDEXED_AT)));
    assertEquals(0, jdbc().queryForObject(
        "SELECT COUNT(*) FROM npm_release_index_revision WHERE package_root_asset_id = ?", Integer.class, assetId));
    jdbc().update("DELETE FROM asset WHERE id = ?", assetId);
    assertTrue(dao.findSnapshot(assetId, blobId).isEmpty());
    assertEquals(0, jdbc().queryForObject(
        "SELECT COUNT(*) FROM npm_release_index_v2_entry WHERE package_root_asset_id = ?", Integer.class, assetId));
  }

  private long insertRepository(String name, String format) {
    jdbc().update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES (?, 'S3', CAST('{}' AS jsonb))
        """, name + "-store");
    long blobStoreId = jdbc().queryForObject(
        "SELECT id FROM blob_store WHERE name = ?", Long.class, name + "-store");
    jdbc().update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES (?, ?, 'proxy', ?, ?, CAST('{}' AS jsonb))
        """, name, format, format + "-proxy", blobStoreId);
    return jdbc().queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

  private long insertBlob(long repositoryId, String objectKey) {
    long blobStoreId = jdbc().queryForObject(
        "SELECT blob_store_id FROM repository WHERE id = ?", Long.class, repositoryId);
    String blobRef = "default@" + objectKey;
    jdbc().update("""
        INSERT INTO asset_blob
          (blob_store_id, blob_ref, blob_ref_hash, object_key, object_key_hash,
           size, content_type, attributes_json)
        VALUES (?, ?, ?, ?, ?, 1, 'application/json', CAST('{}' AS jsonb))
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
        VALUES (?, ?, 'npm', ?, ?, ?, 'PACKAGE_ROOT', 'application/json', 1,
                CAST('{}' AS jsonb))
        """, repositoryId, blobId, path, HashColumns.pathHash(path), path);
    return jdbc().queryForObject(
        "SELECT id FROM asset WHERE repository_id = ? AND path_hash = ?",
        Long.class, repositoryId, HashColumns.pathHash(path));
  }
}
