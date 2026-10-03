package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsRepositoryStateException;
import com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsRepositoryStateException.Reason;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** MySQL/PostgreSQL implementation using database time and short row-locked transitions. */
@Repository
public class JdbcGitLfsDao implements GitLfsDao {
  private final JdbcTemplate jdbc;
  public JdbcGitLfsDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  @Transactional
  public Upload create(long repositoryId, String oid, long size, String subjectKey, long blobStoreId) {
    Instant version = requireWritableRepository(repositoryId, blobStoreId);
    JdbcUpserts.updateThenInsert(jdbc,
        "UPDATE gitlfs_object SET oid = oid WHERE repository_id = ? AND oid = ?", new Object[] {repositoryId, oid},
        "INSERT INTO gitlfs_object(repository_id, oid, generation, fencing_token, published) VALUES (?, ?, 1, 0, FALSE)",
        new Object[] {repositoryId, oid});
    ObjectState object = lockObject(repositoryId, oid).orElseThrow();
    // ON DELETE SET NULL provides an atomic deletion fence for every generic asset deletion path.
    // Advance its generation only when a new explicit Batch opens a fresh upload opportunity.
    if (object.deleted()) {
      jdbc.update("""
          UPDATE gitlfs_object SET generation = generation + 1, published = FALSE,
            owner_id = NULL, lease_until = NULL, fencing_token = fencing_token + 1
          WHERE repository_id = ? AND oid = ?
          """, repositoryId, oid);
      object = lockObject(repositoryId, oid).orElseThrow();
    }
    String id = UUID.randomUUID().toString();
    Instant expiry = now().plusSeconds(900);
    jdbc.update("""
        INSERT INTO gitlfs_upload(upload_id, repository_id, oid, expected_size, subject_key,
          blob_store_id, generation, fencing_token, repository_version, expires_at, state)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 'READY')
        """, id, repositoryId, oid, size, subjectKey, blobStoreId, object.generation(),
        Timestamp.from(version), Timestamp.from(expiry));
    return find(id).orElseThrow();
  }

  @Override
  public Optional<Upload> find(String id) {
    return jdbc.query("SELECT * FROM gitlfs_upload WHERE upload_id = ?", this::map, id).stream().findFirst();
  }

  @Override
  @Transactional
  public Optional<Upload> claim(String id, String subjectKey, String blobRef, String objectKey) {
    Upload initial = find(id).orElse(null);
    if (initial == null) return Optional.empty();
    Instant version = lockRepository(initial.repositoryId(), initial.blobStoreId()).orElse(null);
    ObjectState object = lockObject(initial.repositoryId(), initial.oid()).orElse(null);
    Upload upload = lockedUpload(id).orElse(null);
    Instant now = now();
    if (upload == null || !upload.subjectKey().equals(subjectKey) || !"READY".equals(upload.state())
        || !upload.expiresAt().isAfter(now) || !upload.repositoryVersion().equals(version)
        || object == null || object.deleted() || object.generation() != upload.generation()
        || (object.owner() != null && object.leaseUntil() != null && object.leaseUntil().isAfter(now))) {
      return Optional.empty();
    }
    long fence = object.fence() + 1;
    Timestamp expiry = Timestamp.from(now.plusSeconds(300));
    jdbc.update("""
        UPDATE gitlfs_object SET owner_id = ?, fencing_token = ?, lease_until = ?
        WHERE repository_id = ? AND oid = ?
        """, id, fence, expiry, upload.repositoryId(), upload.oid());
    jdbc.update("""
        UPDATE gitlfs_upload SET state = 'UPLOADING', fencing_token = ?, expires_at = ?,
          blob_ref = ?, object_key = ? WHERE upload_id = ?
        """, fence, expiry, blobRef, objectKey, id);
    return find(id);
  }

  @Override
  @Transactional
  public boolean renew(String id, long fence) {
    Upload initial = find(id).orElse(null);
    if (initial == null) return false;
    // Match publication's object -> upload lock order. Validate both rows before changing either
    // deadline, so a collector that already claimed the upload cannot leave an orphaned lease.
    ObjectState object = lockObject(initial.repositoryId(), initial.oid()).orElse(null);
    Upload upload = lockedUpload(id).orElse(null);
    Instant now = now();
    if (upload == null || !"UPLOADING".equals(upload.state()) || upload.fence() != fence
        || !upload.expiresAt().isAfter(now) || object == null || object.deleted()
        || object.generation() != upload.generation() || !id.equals(object.owner()) || object.fence() != fence
        || object.leaseUntil() == null || !object.leaseUntil().isAfter(now)) return false;
    Timestamp expiry = Timestamp.from(now.plusSeconds(300));
    if (jdbc.update("UPDATE gitlfs_object SET lease_until = ? WHERE owner_id = ? AND fencing_token = ?",
        expiry, id, fence) != 1 || jdbc.update("""
            UPDATE gitlfs_upload SET expires_at = ?
            WHERE upload_id = ? AND fencing_token = ? AND state = 'UPLOADING'
            """, expiry, id, fence) != 1) {
      throw new IllegalStateException("LFS renewal fence lost");
    }
    return true;
  }

  @Override
  @Transactional
  public boolean lockForPublication(String id, long fence) {
    Upload initial = find(id).orElse(null);
    if (initial == null) return false;
    Instant version = lockRepository(initial.repositoryId(), initial.blobStoreId()).orElse(null);
    ObjectState object = lockObject(initial.repositoryId(), initial.oid()).orElse(null);
    Upload upload = lockedUpload(id).orElse(null);
    Instant now = now();
    return upload != null && "UPLOADING".equals(upload.state()) && upload.fence() == fence
        && upload.expiresAt().isAfter(now) && upload.repositoryVersion().equals(version)
        && object != null && !object.deleted() && object.generation() == upload.generation()
        && id.equals(object.owner()) && object.fence() == fence
        && object.leaseUntil() != null && object.leaseUntil().isAfter(now);
  }

  @Override
  @Transactional
  public void published(String id, long fence, long assetId) {
    if (jdbc.update("""
        UPDATE gitlfs_object SET asset_id = ?, published = TRUE, owner_id = NULL, lease_until = NULL
        WHERE owner_id = ? AND fencing_token = ?
        """, assetId, id, fence) != 1) throw new IllegalStateException("LFS publication fence lost");
    if (jdbc.update("""
        UPDATE gitlfs_upload SET state = 'PUBLISHED', expires_at = ?
        WHERE upload_id = ? AND fencing_token = ? AND state = 'UPLOADING'
        """, Timestamp.from(now().plusSeconds(86400)), id, fence) != 1) {
      throw new IllegalStateException("LFS upload ownership lost");
    }
  }

  @Override
  public void multipartStarted(String id, long fence, String multipartId) {
    if (jdbc.update("""
        UPDATE gitlfs_upload SET multipart_id = ?
        WHERE upload_id = ? AND fencing_token = ? AND state = 'UPLOADING' AND expires_at > CURRENT_TIMESTAMP
        """, multipartId, id, fence) != 1) throw new IllegalStateException("LFS upload expired");
  }

  @Override
  @Transactional
  public void abandon(String id, long fence) {
    jdbc.update("""
        UPDATE gitlfs_object SET owner_id = NULL, lease_until = NULL
        WHERE owner_id = ? AND fencing_token = ?
        """, id, fence);
    jdbc.update("""
        UPDATE gitlfs_upload SET state = 'GARBAGE', expires_at = CURRENT_TIMESTAMP
        WHERE upload_id = ? AND fencing_token = ? AND state = 'UPLOADING'
        """, id, fence);
  }

  @Override
  @Transactional
  public List<Upload> claimExpired(int limit) {
    List<Upload> candidates = jdbc.query("""
        SELECT * FROM gitlfs_upload WHERE state IN ('READY', 'UPLOADING', 'GARBAGE', 'REAPING')
          AND expires_at < CURRENT_TIMESTAMP ORDER BY expires_at, upload_id LIMIT ? FOR UPDATE SKIP LOCKED
        """, this::map, Math.max(1, Math.min(100, limit)));
    String token = UUID.randomUUID().toString();
    Timestamp expiry = Timestamp.from(now().plusSeconds(300));
    for (Upload upload : candidates) {
      jdbc.update("UPDATE gitlfs_upload SET state = 'REAPING', cleanup_token = ?, expires_at = ?, "
              + "cleanup_after = COALESCE(cleanup_after, ?) WHERE upload_id = ?",
          token, expiry, Timestamp.from(now().plusSeconds(86400)), upload.id());
    }
    // Do not acquire the object lease here: publishers take object then upload locks. Marking the
    // upload REAPING alone fences late publication; avoiding reverse lock order prevents deadlock.
    return candidates.stream().map(u -> find(u.id()).orElseThrow()).toList();
  }

  @Override
  public void reaped(String id, String cleanupToken) {
    // Retain the attempt key for repeated cleanup through a full day. This closes the window
    // where a remote completion was already in flight when its owner lost the database lease.
    jdbc.update("DELETE FROM gitlfs_upload WHERE upload_id = ? AND state = 'REAPING' AND cleanup_token = ? "
        + "AND (blob_ref IS NULL OR cleanup_after < CURRENT_TIMESTAMP)", id, cleanupToken);
    jdbc.update("UPDATE gitlfs_upload SET state = 'GARBAGE', expires_at = ? "
        + "WHERE upload_id = ? AND state = 'REAPING' AND cleanup_token = ?",
        Timestamp.from(now().plusSeconds(3600)), id, cleanupToken);
  }

  @Override
  @Transactional
  public int prunePublished(int limit) {
    List<String> ids = jdbc.queryForList("""
        SELECT upload_id FROM gitlfs_upload WHERE state = 'PUBLISHED' AND expires_at < CURRENT_TIMESTAMP
        ORDER BY expires_at, upload_id LIMIT ? FOR UPDATE SKIP LOCKED
        """, String.class, Math.max(1, Math.min(100, limit)));
    for (String id : ids) jdbc.update("DELETE FROM gitlfs_upload WHERE upload_id = ? AND state = 'PUBLISHED'", id);
    return ids.size();
  }

  private Optional<Instant> lockRepository(long id, long blobStoreId) {
    try {
      return Optional.of(requireWritableRepository(id, blobStoreId));
    } catch (GitLfsRepositoryStateException changed) {
      return Optional.empty();
    }
  }

  private Instant requireWritableRepository(long id, long blobStoreId) {
    return jdbc.query("""
        SELECT updated_at, online, format, type, blob_store_id, write_policy
        FROM repository WHERE id = ? FOR UPDATE
        """, (rs, n) -> {
          if (!rs.getBoolean("online")) throw new GitLfsRepositoryStateException(Reason.OFFLINE);
          if (!"gitlfs".equals(rs.getString("format")) || !"hosted".equals(rs.getString("type"))
              || rs.getLong("blob_store_id") != blobStoreId) {
            throw new GitLfsRepositoryStateException(Reason.CONFIGURATION_CHANGED);
          }
          if ("DENY".equalsIgnoreCase(rs.getString("write_policy"))) {
            throw new GitLfsRepositoryStateException(Reason.READ_ONLY);
          }
          return rs.getTimestamp("updated_at").toInstant();
        }, id).stream().findFirst().orElseThrow(() -> new GitLfsRepositoryStateException(Reason.MISSING));
  }

  private Optional<ObjectState> lockObject(long repositoryId, String oid) {
    return jdbc.query("SELECT * FROM gitlfs_object WHERE repository_id = ? AND oid = ? FOR UPDATE",
        (rs, n) -> new ObjectState(rs.getLong("generation"), rs.getLong("fencing_token"),
            rs.getString("owner_id"), instant(rs, "lease_until"),
            rs.getBoolean("published") && rs.getObject("asset_id") == null), repositoryId, oid)
        .stream().findFirst();
  }

  private Optional<Upload> lockedUpload(String id) {
    return jdbc.query("SELECT * FROM gitlfs_upload WHERE upload_id = ? FOR UPDATE", this::map, id).stream().findFirst();
  }
  private Instant now() { return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant(); }
  private Upload map(ResultSet rs, int row) throws SQLException {
    return new Upload(rs.getString("upload_id"), rs.getLong("repository_id"), rs.getString("oid"),
        rs.getLong("expected_size"), rs.getString("subject_key"), rs.getLong("blob_store_id"),
        rs.getLong("generation"), rs.getLong("fencing_token"), instant(rs, "repository_version"),
        instant(rs, "expires_at"), rs.getString("state"), rs.getString("blob_ref"),
        rs.getString("object_key"), rs.getString("multipart_id"), rs.getString("cleanup_token"));
  }
  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
  private record ObjectState(long generation, long fence, String owner, Instant leaseUntil, boolean deleted) {}
}
