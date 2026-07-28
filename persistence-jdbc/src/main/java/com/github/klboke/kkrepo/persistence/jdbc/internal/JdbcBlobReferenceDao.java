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
    // Soft deletion is the committed, irreversible fence before GC touches external storage.
    // Explicitly lock the active blob row before publishing ownership. The orphan marker and final
    // GC take the same row lock, so this ordering is portable across MySQL and PostgreSQL: retain
    // either commits first and defeats the marker, or observes the committed fence and is rejected.
    boolean active = !jdbc.query("""
        SELECT id
        FROM asset_blob
        WHERE id = ?
          AND deleted_at IS NULL
        FOR UPDATE
        """, (rs, rowNum) -> rs.getLong(1), blobId).isEmpty();
    if (!active) return false;
    boolean inserted = JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO blob_reference (owner_type, owner_id, blob_id, created_at)
        SELECT ?, ?, ?, CURRENT_TIMESTAMP
          FROM asset_blob source_blob
         WHERE source_blob.id = ?
           AND source_blob.deleted_at IS NULL
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
    if (inserted) return true;
    return !jdbc.queryForList("""
        SELECT blob_id
        FROM blob_reference
        WHERE owner_type = ? AND owner_id = ? AND blob_id = ?
        LIMIT 1
        """, Long.class, ownerType, ownerId, blobId).isEmpty();
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
