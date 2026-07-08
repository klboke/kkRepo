package com.github.klboke.kkrepo.persistence.mysql.dao;

import static com.github.klboke.kkrepo.persistence.mysql.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.mysql.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.mysql.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.mysql.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.mysql.support.JsonColumns;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PubUploadSessionDao {
  public static final String STATUS_NEW = "NEW";
  public static final String STATUS_UPLOADED = "UPLOADED";
  public static final String STATUS_FINALIZED = "FINALIZED";
  public static final String STATUS_FAILED = "FAILED";

  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<PubUploadSessionRecord> rowMapper;

  public PubUploadSessionDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.rowMapper = (rs, rowNum) -> new PubUploadSessionRecord(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        rs.getString("session_id"),
        rs.getString("field_token"),
        rs.getString("principal_user_id"),
        nullableLong(rs, "principal_api_key_id"),
        rs.getString("status"),
        nullableInstant(rs, "expires_at"),
        nullableLong(rs, "blob_store_id"),
        rs.getString("blob_ref"),
        rs.getString("object_key"),
        rs.getString("md5"),
        rs.getString("sha1"),
        rs.getString("sha256"),
        rs.getString("sha512"),
        nullableLong(rs, "size"),
        rs.getString("package_name"),
        rs.getString("version"),
        jsonColumns.read(rs.getString("pubspec_json")),
        rs.getString("error_message"),
        nullableInstant(rs, "finalized_at"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
  }

  public long insert(PubUploadSessionRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO pub_upload_session
          (repository_id, session_id, field_token, principal_user_id, principal_api_key_id,
           status, expires_at, blob_store_id, blob_ref, object_key, md5, sha1, sha256, sha512,
           size, package_name, version, pubspec_json, error_message, finalized_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, record.repositoryId());
      ps.setString(2, record.sessionId());
      ps.setString(3, record.fieldToken());
      ps.setString(4, record.principalUserId());
      ps.setObject(5, record.principalApiKeyId());
      ps.setString(6, record.status());
      ps.setTimestamp(7, nullableTimestamp(record.expiresAt()));
      ps.setObject(8, record.blobStoreId());
      ps.setString(9, record.blobRef());
      ps.setString(10, record.objectKey());
      ps.setString(11, record.md5());
      ps.setString(12, record.sha1());
      ps.setString(13, record.sha256());
      ps.setString(14, record.sha512());
      ps.setObject(15, record.size());
      ps.setString(16, record.packageName());
      ps.setString(17, record.version());
      ps.setString(18, jsonColumns.write(record.pubspec()));
      ps.setString(19, record.errorMessage());
      ps.setTimestamp(20, nullableTimestamp(record.finalizedAt()));
    });
  }

  public Optional<PubUploadSessionRecord> find(long repositoryId, String sessionId) {
    return jdbcTemplate.query("""
        SELECT * FROM pub_upload_session
        WHERE repository_id = ? AND session_id = ?
        """, rowMapper, repositoryId, sessionId).stream().findFirst();
  }

  public Optional<PubUploadSessionRecord> lock(long repositoryId, String sessionId) {
    return jdbcTemplate.query("""
        SELECT * FROM pub_upload_session
        WHERE repository_id = ? AND session_id = ?
        FOR UPDATE
        """, rowMapper, repositoryId, sessionId).stream().findFirst();
  }

  public Optional<PubUploadSessionRecord> lockById(long id) {
    return jdbcTemplate.query("""
        SELECT * FROM pub_upload_session
        WHERE id = ?
        FOR UPDATE
        """, rowMapper, id).stream().findFirst();
  }

  public List<PubUploadSessionRecord> listExpiredOpen(Instant now, int limit) {
    return jdbcTemplate.query("""
        SELECT * FROM pub_upload_session
        WHERE status IN (?, ?) AND expires_at < ?
        ORDER BY id
        LIMIT ?
        """,
        rowMapper,
        STATUS_NEW,
        STATUS_UPLOADED,
        nullableTimestamp(now),
        Math.max(1, limit));
  }

  public void markUploaded(
      long id,
      long blobStoreId,
      String blobRef,
      String objectKey,
      String md5,
      String sha1,
      String sha256,
      String sha512,
      long size,
      String packageName,
      String version,
      Map<String, Object> pubspec) {
    jdbcTemplate.update("""
        UPDATE pub_upload_session
        SET status = ?, blob_store_id = ?, blob_ref = ?, object_key = ?, md5 = ?, sha1 = ?,
            sha256 = ?, sha512 = ?, size = ?, package_name = ?, version = ?, pubspec_json = ?,
            error_message = NULL
        WHERE id = ?
        """,
        STATUS_UPLOADED,
        blobStoreId,
        blobRef,
        objectKey,
        md5,
        sha1,
        sha256,
        sha512,
        size,
        packageName,
        version,
        jsonColumns.write(pubspec),
        id);
  }

  public void markFinalized(long id, Instant finalizedAt) {
    jdbcTemplate.update("""
        UPDATE pub_upload_session
        SET status = ?, finalized_at = ?, error_message = NULL
        WHERE id = ?
        """, STATUS_FINALIZED, nullableTimestamp(finalizedAt), id);
  }

  public void markFailed(long id, String message) {
    jdbcTemplate.update("""
        UPDATE pub_upload_session
        SET status = ?, error_message = ?
        WHERE id = ?
        """, STATUS_FAILED, message, id);
  }

  public int expireOlderThan(Instant now) {
    return jdbcTemplate.update("""
        UPDATE pub_upload_session
        SET status = ?
        WHERE status IN (?, ?) AND expires_at < ?
        """, STATUS_FAILED, STATUS_NEW, STATUS_UPLOADED, nullableTimestamp(now));
  }
}
