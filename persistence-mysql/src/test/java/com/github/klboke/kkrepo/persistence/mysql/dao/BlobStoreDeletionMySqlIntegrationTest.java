package com.github.klboke.kkrepo.persistence.mysql.dao;

import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.BLOBS_REMAIN;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.DELETED;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.NOT_FOUND;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.REPOSITORY_IN_USE;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.UPLOADS_IN_PROGRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlobStoreDeletionMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void deletesOnlyUnreferencedConfigurationIncludingSoftDeletedBlobAndOrphanUploadGuards() {
    long empty = insertBlobStore("empty-delete");
    assertEquals(DELETED, inTransaction(() -> stores().blobStores().deleteEmptyById(empty)));
    assertEquals(NOT_FOUND, inTransaction(() -> stores().blobStores().deleteEmptyById(empty)));

    long inUse = insertBlobStore("repository-delete");
    jdbc().update("""
        INSERT INTO repository (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES ('delete-repo', 'raw', 'hosted', 'raw-hosted', ?, JSON_OBJECT())
        """, inUse);
    assertEquals(REPOSITORY_IN_USE,
        inTransaction(() -> stores().blobStores().deleteEmptyById(inUse)));
    jdbc().update("DELETE FROM repository WHERE name = 'delete-repo'");
    assertEquals(DELETED, inTransaction(() -> stores().blobStores().deleteEmptyById(inUse)));

    long withBlob = insertBlobStore("blob-delete");
    jdbc().update("""
        INSERT INTO asset_blob
          (blob_store_id, blob_ref, blob_ref_hash, object_key, object_key_hash, size, deleted_at)
        VALUES (?, 'ref', ?, 'object', ?, 1, CURRENT_TIMESTAMP)
        """, withBlob, new byte[32], new byte[32]);
    assertEquals(BLOBS_REMAIN, inTransaction(() -> stores().blobStores().deleteEmptyById(withBlob)));
    assertTrue(stores().blobStores().findById(withBlob).isPresent());
    jdbc().update("DELETE FROM asset_blob WHERE blob_store_id = ?", withBlob);
    assertEquals(DELETED, inTransaction(() -> stores().blobStores().deleteEmptyById(withBlob)));

    long withUpload = insertBlobStore("upload-delete");
    jdbc().update("""
        INSERT INTO gitlfs_upload (upload_id, repository_id, oid, expected_size, subject_key,
          blob_store_id, generation, fencing_token, repository_version, expires_at, state)
        VALUES (?, 42, ?, 1, 'user', ?, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'UPLOADING')
        """, UUID.randomUUID().toString(), "0".repeat(64), withUpload);
    assertEquals(UPLOADS_IN_PROGRESS,
        inTransaction(() -> stores().blobStores().deleteEmptyById(withUpload)));
    jdbc().update("DELETE FROM gitlfs_upload WHERE blob_store_id = ?", withUpload);
    assertEquals(DELETED, inTransaction(() -> stores().blobStores().deleteEmptyById(withUpload)));
  }
}
