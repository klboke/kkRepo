package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadChunkRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDockerUploadDao implements com.github.klboke.kkrepo.persistence.jdbc.api.DockerUploadDao {
  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<DockerUploadSessionRecord> sessionMapper;
  private final RowMapper<DockerUploadChunkRecord> chunkMapper;

  public JdbcDockerUploadDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.sessionMapper = (rs, rowNum) -> new DockerUploadSessionRecord(
        rs.getString("uuid"),
        rs.getLong("repository_id"),
        rs.getString("image_name"),
        rs.getBytes("image_name_hash"),
        rs.getString("status"),
        rs.getLong("next_offset"),
        rs.getString("digest_algorithm"),
        rs.getString("expected_digest"),
        rs.getString("created_by"),
        rs.getString("created_by_ip"),
        nullableInstant(rs, "expires_at"),
        rs.getString("locked_by"),
        nullableInstant(rs, "locked_until"),
        jsonColumns.read(rs.getString("attributes_json")),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.chunkMapper = (rs, rowNum) -> new DockerUploadChunkRecord(
        rs.getLong("id"),
        rs.getString("session_uuid"),
        rs.getInt("chunk_index"),
        rs.getLong("start_offset"),
        rs.getLong("end_offset"),
        rs.getString("blob_ref"),
        rs.getString("object_key"),
        rs.getString("sha256"),
        rs.getLong("size"),
        nullableInstant(rs, "created_at"));
  }

  public void insertSession(DockerUploadSessionRecord record) {
    jdbcTemplate.update("""
        INSERT INTO docker_upload_session
          (uuid, repository_id, image_name, image_name_hash, status, next_offset,
           digest_algorithm, expected_digest, created_by, created_by_ip, expires_at,
           locked_by, locked_until, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.uuid(),
        record.repositoryId(),
        record.imageName(),
        record.imageNameHash(),
        record.status(),
        record.nextOffset(),
        record.digestAlgorithm(),
        record.expectedDigest(),
        record.createdBy(),
        record.createdByIp(),
        nullableTimestamp(record.expiresAt()),
        record.lockedBy(),
        nullableTimestamp(record.lockedUntil()),
        jsonColumns.write(record.attributes()));
  }

  public Optional<DockerUploadSessionRecord> findSession(String uuid) {
    return jdbcTemplate.query("SELECT * FROM docker_upload_session WHERE uuid = ?", sessionMapper, uuid)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<DockerUploadSessionRecord> lockSession(String uuid) {
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_upload_session
        WHERE uuid = ?
        FOR UPDATE
        """, sessionMapper, uuid).stream().findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void appendChunk(
      String uuid,
      int chunkIndex,
      long startOffset,
      long endOffset,
      String blobRef,
      String objectKey,
      String sha256,
      long size,
      long nextOffset) {
    jdbcTemplate.update("""
        INSERT INTO docker_upload_chunk
          (session_uuid, chunk_index, start_offset, end_offset, blob_ref, object_key, sha256, size)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, uuid, chunkIndex, startOffset, endOffset, blobRef, objectKey, sha256, size);
    jdbcTemplate.update("""
        UPDATE docker_upload_session
        SET next_offset = ?, status = 'STARTED'
        WHERE uuid = ?
        """, nextOffset, uuid);
  }

  public List<DockerUploadChunkRecord> listChunks(String uuid) {
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_upload_chunk
        WHERE session_uuid = ?
        ORDER BY chunk_index
        """, chunkMapper, uuid);
  }

  public int nextChunkIndex(String uuid) {
    Integer next = jdbcTemplate.queryForObject("""
        SELECT COALESCE(MAX(chunk_index), -1) + 1
        FROM docker_upload_chunk
        WHERE session_uuid = ?
        """, Integer.class, uuid);
    return next == null ? 0 : next;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void completeSession(String uuid, String expectedDigest, String digestAlgorithm) {
    jdbcTemplate.update("""
        UPDATE docker_upload_session
        SET status = 'COMPLETED', expected_digest = ?, digest_algorithm = ?
        WHERE uuid = ?
        """, expectedDigest, digestAlgorithm, uuid);
  }

  public int cancelSession(String uuid) {
    return jdbcTemplate.update("""
        UPDATE docker_upload_session
        SET status = 'CANCELLED'
        WHERE uuid = ?
          AND status <> 'COMPLETED'
        """, uuid);
  }

  public int expireSessions(Instant now) {
    return jdbcTemplate.update("""
        UPDATE docker_upload_session
        SET status = 'EXPIRED'
        WHERE expires_at <= ?
          AND status IN ('STARTED', 'COMPLETING')
        """, nullableTimestamp(now));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<DockerUploadSessionRecord> claimTerminalSessions(
      Instant now,
      String owner,
      Instant lockedUntil,
      int batchSize) {
    List<DockerUploadSessionRecord> sessions = jdbcTemplate.query("""
        SELECT *
        FROM docker_upload_session
        WHERE status IN ('EXPIRED', 'CANCELLED', 'COMPLETED')
          AND (locked_until IS NULL OR locked_until <= ? OR locked_by = ?)
        ORDER BY updated_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, sessionMapper, nullableTimestamp(now), owner, Math.max(1, batchSize));
    if (sessions.isEmpty()) {
      return sessions;
    }
    List<Object[]> args = sessions.stream()
        .map(session -> new Object[]{owner, nullableTimestamp(lockedUntil), session.uuid()})
        .toList();
    jdbcTemplate.batchUpdate("""
        UPDATE docker_upload_session
        SET locked_by = ?, locked_until = ?
        WHERE uuid = ?
        """, args);
    return sessions;
  }

  public int deleteSession(String uuid) {
    return jdbcTemplate.update("DELETE FROM docker_upload_session WHERE uuid = ?", uuid);
  }

  public static byte[] imageHash(String imageName) {
    return HashColumns.sha256(imageName == null ? "" : imageName);
  }

  public static Map<String, Object> attributes(Map<String, Object> attributes) {
    return attributes == null ? Map.of() : attributes;
  }
}
