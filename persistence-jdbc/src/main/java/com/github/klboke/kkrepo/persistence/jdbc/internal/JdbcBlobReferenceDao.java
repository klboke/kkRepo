package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database-neutral JDBC implementation of generic non-asset blob ownership. */
@Repository
public class JdbcBlobReferenceDao implements BlobReferenceDao {
  private final JdbcTemplate jdbc;

  public JdbcBlobReferenceDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean retain(String ownerType, long ownerId, long blobId) {
    return JdbcInserts.tryUpdate(jdbc, """
        INSERT INTO blob_reference (owner_type, owner_id, blob_id, created_at)
        SELECT ?, ?, ?, CURRENT_TIMESTAMP
        WHERE NOT EXISTS (
          SELECT 1
          FROM blob_reference
          WHERE owner_type = ? AND owner_id = ? AND blob_id = ?
        )
        """, ps -> {
      ps.setString(1, ownerType);
      ps.setLong(2, ownerId);
      ps.setLong(3, blobId);
      ps.setString(4, ownerType);
      ps.setLong(5, ownerId);
      ps.setLong(6, blobId);
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
