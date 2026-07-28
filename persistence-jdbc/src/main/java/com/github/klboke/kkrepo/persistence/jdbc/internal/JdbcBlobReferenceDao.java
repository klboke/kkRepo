package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Database-neutral JDBC implementation of generic non-asset blob ownership. */
@Repository
public class JdbcBlobReferenceDao implements BlobReferenceDao {
  private final JdbcTemplate jdbc;

  public JdbcBlobReferenceDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public boolean retain(String ownerType, long ownerId, long blobId) {
    // Lock the blob row in the same statement that publishes ownership. GC takes the same row
    // lock before its final reference check and object deletion, so a retain either wins first or
    // observes that GC has already removed the row; it can never appear after physical deletion.
    return JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO blob_reference (owner_type, owner_id, blob_id, created_at)
        SELECT ?, ?, ?, CURRENT_TIMESTAMP
          FROM asset_blob source_blob
         WHERE source_blob.id = ?
           AND NOT EXISTS (
          SELECT 1
          FROM blob_reference
          WHERE owner_type = ? AND owner_id = ? AND blob_id = ?
        )
        FOR UPDATE
        """, ps -> {
      ps.setString(1, ownerType);
      ps.setLong(2, ownerId);
      ps.setLong(3, blobId);
      ps.setLong(4, blobId);
      ps.setString(5, ownerType);
      ps.setLong(6, ownerId);
      ps.setLong(7, blobId);
    });
  }

  @Override
  public int release(String ownerType, long ownerId, long blobId) {
    return jdbc.update("""
        DELETE FROM blob_reference
        WHERE owner_type = ? AND owner_id = ? AND blob_id = ?
        """, ownerType, ownerId, blobId);
  }

  @Override
  public boolean isReferenced(long blobId) {
    return !jdbc.queryForList("""
        SELECT blob_id
        FROM blob_reference
        WHERE blob_id = ?
        LIMIT 1
        """, Long.class, blobId).isEmpty();
  }
}
