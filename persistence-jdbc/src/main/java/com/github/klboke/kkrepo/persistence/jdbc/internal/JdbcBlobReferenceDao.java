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
    if (inserted) {
      int fenced = jdbc.update("""
          UPDATE asset_blob
          SET external_reference_count = external_reference_count + 1
          WHERE id = ?
            AND deleted_at IS NULL
          """, blobId);
      if (fenced != 1) {
        throw new IllegalStateException("Could not publish blob reference fence for blob " + blobId);
      }
      return true;
    }
    return !jdbc.queryForList("""
        SELECT blob_id
        FROM blob_reference
        WHERE owner_type = ? AND owner_id = ? AND blob_id = ?
        LIMIT 1
        """, Long.class, ownerType, ownerId, blobId).isEmpty();
  }

  @Override
  @Transactional
  public int release(String ownerType, long ownerId, long blobId) {
    lockBlob(blobId);
    int released = jdbc.update("""
        DELETE FROM blob_reference
        WHERE owner_type = ? AND owner_id = ? AND blob_id = ?
        """, ownerType, ownerId, blobId);
    decrementFence(blobId, released);
    return released;
  }

  @Override
  @Transactional
  public int releaseOwner(String ownerType, long ownerId) {
    // Use a write/current read for both databases. Under MySQL REPEATABLE READ, an earlier plain
    // SELECT can retain a snapshot from before a concurrent publisher commits, while the DELETE
    // below still sees and removes that new row. Updating the fence first avoids leaving a stale
    // positive count after such a cancellation race.
    jdbc.update("""
        UPDATE asset_blob b
        SET external_reference_count = external_reference_count - (
          SELECT COUNT(*)
          FROM blob_reference owner_ref
          WHERE owner_ref.blob_id = b.id
            AND owner_ref.owner_type = ?
            AND owner_ref.owner_id = ?
        )
        WHERE EXISTS (
          SELECT 1
          FROM blob_reference owner_ref
          WHERE owner_ref.blob_id = b.id
            AND owner_ref.owner_type = ?
            AND owner_ref.owner_id = ?
        )
        """, ownerType, ownerId, ownerType, ownerId);
    int released = jdbc.update("""
        DELETE FROM blob_reference
        WHERE owner_type = ? AND owner_id = ?
        """, ownerType, ownerId);
    return released;
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

  private void lockBlob(long blobId) {
    jdbc.queryForList("""
        SELECT id
        FROM asset_blob
        WHERE id = ?
        FOR UPDATE
        """, Long.class, blobId);
  }

  private void decrementFence(long blobId, int released) {
    if (released == 0) return;
    int updated = jdbc.update("""
        UPDATE asset_blob
        SET external_reference_count = external_reference_count - ?
        WHERE id = ?
          AND external_reference_count >= ?
        """, released, blobId, released);
    if (updated != 1) {
      throw new IllegalStateException("Blob reference fence underflow for blob " + blobId);
    }
  }
}
