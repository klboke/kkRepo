package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingGroupChangeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingPromotionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JDBC implementation of the multi-repository staging state machine. */
@Repository
public class JdbcStagingDao implements StagingDao {
  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<StagingSessionRecord> sessionMapper;
  private final RowMapper<StagingRepositoryRecord> repositoryMapper;
  private final RowMapper<StagingItemRecord> itemMapper;
  private final RowMapper<StagingPromotionRecord> promotionMapper;
  private final RowMapper<StagingGroupChangeRecord> groupChangeMapper;

  public JdbcStagingDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.sessionMapper = (rs, rowNum) -> new StagingSessionRecord(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("state"),
        rs.getLong("revision"),
        rs.getString("owner_source"),
        rs.getString("owner_user_id"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "opened_at"),
        nullableInstant(rs, "frozen_at"),
        nullableInstant(rs, "finished_at"),
        nullableInstant(rs, "expires_at"),
        nullableLong(rs, "promotion_id"),
        jsonColumns.read(rs.getString("attributes_json")));
    this.repositoryMapper = (rs, rowNum) -> new StagingRepositoryRecord(
        rs.getLong("session_id"),
        rs.getLong("repository_id"),
        RepositoryFormat.fromJson(rs.getString("format")),
        nullableInstant(rs, "created_at"));
    this.itemMapper = (rs, rowNum) -> new StagingItemRecord(
        rs.getLong("id"),
        rs.getLong("session_id"),
        rs.getLong("repository_id"),
        RepositoryFormat.fromJson(rs.getString("format")),
        rs.getString("logical_path"),
        rs.getBytes("logical_path_hash"),
        rs.getString("action"),
        rs.getString("status"),
        nullableLong(rs, "blob_store_id"),
        nullableLong(rs, "asset_blob_id"),
        rs.getString("content_type"),
        nullableLong(rs, "size"),
        rs.getString("sha256"),
        rs.getString("md5"),
        rs.getString("sha1"),
        rs.getString("source_path"),
        rs.getString("expected_sha256"),
        nullableLong(rs, "expected_size"),
        jsonColumns.read(rs.getString("attributes_json")),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.groupChangeMapper = (rs, rowNum) -> new StagingGroupChangeRecord(
        rs.getLong("id"),
        rs.getLong("session_id"),
        rs.getLong("group_repository_id"),
        rs.getLong("member_repository_id"),
        rs.getString("action"),
        rs.getObject("position") == null ? null : rs.getInt("position"),
        rs.getString("status"),
        nullableInstant(rs, "created_at"),
        nullableInstant(rs, "updated_at"));
    this.promotionMapper = (rs, rowNum) -> new StagingPromotionRecord(
        rs.getLong("id"),
        rs.getLong("session_id"),
        rs.getString("state"),
        rs.getString("fence_token"),
        rs.getString("manifest_sha256"),
        jsonColumns.read(rs.getString("report_json")),
        nullableInstant(rs, "started_at"),
        nullableInstant(rs, "finished_at"),
        nullableInstant(rs, "created_at"));
  }

  @Override
  public long insertSession(StagingSessionRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO staging_session
          (name, state, revision, owner_source, owner_user_id, opened_at, expires_at,
           attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, record.name());
      ps.setString(2, record.state());
      ps.setLong(3, record.revision());
      ps.setString(4, record.ownerSource());
      ps.setString(5, record.ownerUserId());
      ps.setTimestamp(6, nullableTimestamp(
          record.openedAt() == null ? Instant.now() : record.openedAt()));
      ps.setTimestamp(7, nullableTimestamp(record.expiresAt()));
      jsonColumns.bind(ps, 8, record.attributes());
    });
  }

  @Override
  public Optional<StagingSessionRecord> findSessionById(long id) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_session WHERE id = ?", sessionMapper, id).stream().findFirst();
  }

  @Override
  public Optional<StagingSessionRecord> findSessionByName(String name) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_session WHERE name = ?", sessionMapper, name).stream().findFirst();
  }

  @Override
  public Optional<StagingSessionRecord> lockSession(long id) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_session WHERE id = ? FOR UPDATE", sessionMapper, id)
        .stream().findFirst();
  }

  @Override
  public Optional<StagingSessionRecord> lockSessionByName(String name) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_session WHERE name = ? FOR UPDATE", sessionMapper, name)
        .stream().findFirst();
  }

  @Override
  public List<StagingSessionRecord> listSessions(String state, int limit) {
    int bounded = Math.max(1, Math.min(limit, 500));
    if (state == null || state.isBlank()) {
      return jdbcTemplate.query("""
          SELECT * FROM staging_session
          ORDER BY id DESC
          LIMIT ?
          """, sessionMapper, bounded);
    }
    return jdbcTemplate.query("""
        SELECT * FROM staging_session
        WHERE state = ?
        ORDER BY id DESC
        LIMIT ?
        """, sessionMapper, state, bounded);
  }

  @Override
  public List<StagingSessionRecord> listExpiredActive(Instant now, int limit) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_session
        WHERE state IN (?, ?, ?)
          AND expires_at IS NOT NULL
          AND expires_at < ?
        ORDER BY expires_at, id
        LIMIT ?
        """,
        sessionMapper,
        StagingDao.SESSION_OPEN,
        StagingDao.SESSION_FROZEN,
        StagingDao.SESSION_PREFLIGHTED,
        nullableTimestamp(now),
        Math.max(1, Math.min(limit, 500)));
  }

  @Override
  public boolean transitionSession(
      long id,
      long expectedRevision,
      Collection<String> allowedStates,
      String nextState,
      Instant frozenAt,
      Instant finishedAt,
      Long promotionId) {
    List<String> states = sanitizeStates(allowedStates);
    if (states.isEmpty()) {
      return false;
    }
    List<Object> args = new ArrayList<>();
    args.add(nextState);
    args.add(nullableTimestamp(frozenAt));
    args.add(nullableTimestamp(finishedAt));
    args.add(promotionId);
    args.add(id);
    args.add(expectedRevision);
    args.addAll(states);
    String placeholders = String.join(", ", java.util.Collections.nCopies(states.size(), "?"));
    int updated = jdbcTemplate.update("""
        UPDATE staging_session
        SET state = ?,
            revision = revision + 1,
            frozen_at = COALESCE(?, frozen_at),
            finished_at = COALESCE(?, finished_at),
            promotion_id = COALESCE(?, promotion_id)
        WHERE id = ? AND revision = ? AND state IN (%s)
        """.formatted(placeholders), args.toArray());
    return updated == 1;
  }

  @Override
  public boolean updateSessionMetadata(
      long id, long expectedRevision, Instant expiresAt, Map<String, Object> attributes) {
    return jdbcTemplate.update("""
        UPDATE staging_session
        SET revision = revision + 1,
            expires_at = ?,
            attributes_json = ?
        WHERE id = ? AND revision = ?
        """,
        nullableTimestamp(expiresAt),
        jsonColumns.parameter(attributes),
        id,
        expectedRevision) == 1;
  }

  @Override
  public void addRepository(long sessionId, long repositoryId, String format) {
    JdbcInserts.tryUpdate(jdbcTemplate, """
        INSERT INTO staging_session_repository (session_id, repository_id, format)
        VALUES (?, ?, ?)
        """, ps -> {
      ps.setLong(1, sessionId);
      ps.setLong(2, repositoryId);
      ps.setString(3, format);
    });
  }

  @Override
  public boolean removeRepository(long sessionId, long repositoryId) {
    return jdbcTemplate.update("""
        DELETE FROM staging_session_repository
        WHERE session_id = ? AND repository_id = ?
        """, sessionId, repositoryId) == 1;
  }

  @Override
  public boolean hasRepository(long sessionId, long repositoryId) {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM staging_session_repository
        WHERE session_id = ? AND repository_id = ?
        """, Long.class, sessionId, repositoryId);
    return count != null && count > 0;
  }

  @Override
  public List<StagingRepositoryRecord> listRepositories(long sessionId) {
    return jdbcTemplate.query("""
        SELECT session_id, repository_id, format, created_at
        FROM staging_session_repository
        WHERE session_id = ?
        ORDER BY repository_id
        """, repositoryMapper, sessionId);
  }

  @Override
  public List<Long> lockRepositories(Collection<Long> repositoryIds) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return List.of();
    }
    List<Long> ids = repositoryIds.stream()
        .filter(java.util.Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
    String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    return jdbcTemplate.queryForList("""
        SELECT id FROM repository
        WHERE id IN (%s)
        ORDER BY id
        FOR UPDATE
        """.formatted(placeholders), Long.class, ids.toArray());
  }

  @Override
  public OptionalLong tryInsertItem(StagingItemRecord record) {
    return JdbcInserts.tryInsert(jdbcTemplate, """
        INSERT INTO staging_item
          (session_id, repository_id, format, logical_path, logical_path_hash, action, status,
           blob_store_id, asset_blob_id, content_type, size, sha256, md5, sha1, source_path,
           expected_sha256, expected_size, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, record.sessionId());
      ps.setLong(2, record.repositoryId());
      ps.setString(3, record.format().id());
      ps.setString(4, record.logicalPath());
      ps.setBytes(5, record.logicalPathHash());
      ps.setString(6, record.action());
      ps.setString(7, record.status());
      ps.setObject(8, record.blobStoreId());
      ps.setObject(9, record.assetBlobId());
      ps.setString(10, record.contentType());
      ps.setObject(11, record.size());
      ps.setString(12, record.sha256());
      ps.setString(13, record.md5());
      ps.setString(14, record.sha1());
      ps.setString(15, record.sourcePath());
      ps.setString(16, record.expectedSha256());
      ps.setObject(17, record.expectedSize());
      jsonColumns.bind(ps, 18, record.attributes());
    });
  }

  @Override
  public Optional<StagingItemRecord> findItem(
      long sessionId, long repositoryId, byte[] logicalPathHash) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_item
        WHERE session_id = ? AND repository_id = ? AND logical_path_hash = ?
        """, itemMapper, sessionId, repositoryId, logicalPathHash).stream().findFirst();
  }

  @Override
  public Optional<StagingItemRecord> lockItem(long itemId) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_item WHERE id = ? FOR UPDATE", itemMapper, itemId)
        .stream().findFirst();
  }

  @Override
  public List<StagingItemRecord> listItems(long sessionId) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_item
        WHERE session_id = ?
        ORDER BY repository_id, logical_path
        """, itemMapper, sessionId);
  }

  @Override
  public List<StagingItemRecord> listItems(long sessionId, String status) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_item
        WHERE session_id = ? AND status = ?
        ORDER BY repository_id, logical_path
        """, itemMapper, sessionId, status);
  }

  @Override
  public int updateItem(StagingItemRecord record) {
    if (record.id() == null) {
      throw new IllegalArgumentException("Staging item id is required for update");
    }
    return jdbcTemplate.update("""
        UPDATE staging_item
        SET action = ?,
            status = ?,
            blob_store_id = ?,
            asset_blob_id = ?,
            content_type = ?,
            size = ?,
            sha256 = ?,
            md5 = ?,
            sha1 = ?,
            source_path = ?,
            expected_sha256 = ?,
            expected_size = ?,
            attributes_json = ?,
            updated_at = CURRENT_TIMESTAMP(3)
        WHERE id = ?
        """,
        record.action(),
        record.status(),
        record.blobStoreId(),
        record.assetBlobId(),
        record.contentType(),
        record.size(),
        record.sha256(),
        record.md5(),
        record.sha1(),
        record.sourcePath(),
        record.expectedSha256(),
        record.expectedSize(),
        jsonColumns.parameter(record.attributes()),
        record.id());
  }

  @Override
  public int updateItemStatus(long itemId, String status) {
    return jdbcTemplate.update("""
        UPDATE staging_item
        SET status = ?, updated_at = CURRENT_TIMESTAMP(3)
        WHERE id = ?
        """, status, itemId);
  }

  @Override
  public int deleteItem(long itemId) {
    return jdbcTemplate.update("DELETE FROM staging_item WHERE id = ?", itemId);
  }

  @Override
  public int deleteItems(long sessionId) {
    return jdbcTemplate.update(
        "DELETE FROM staging_item WHERE session_id = ?", sessionId);
  }

  @Override
  public Optional<StagingGroupChangeRecord> findGroupChange(
      long sessionId, long groupRepositoryId, long memberRepositoryId) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_group_change
        WHERE session_id = ? AND group_repository_id = ? AND member_repository_id = ?
        """, groupChangeMapper, sessionId, groupRepositoryId, memberRepositoryId)
        .stream().findFirst();
  }

  @Override
  public List<StagingGroupChangeRecord> listGroupChanges(long sessionId) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_group_change
        WHERE session_id = ?
        ORDER BY group_repository_id, member_repository_id
        """, groupChangeMapper, sessionId);
  }

  @Override
  public void upsertGroupChange(StagingGroupChangeRecord record) {
    int updated = jdbcTemplate.update("""
        UPDATE staging_group_change
        SET action = ?, position = ?, status = ?, updated_at = CURRENT_TIMESTAMP(3)
        WHERE session_id = ? AND group_repository_id = ? AND member_repository_id = ?
        """,
        record.action(),
        record.position(),
        record.status(),
        record.sessionId(),
        record.groupRepositoryId(),
        record.memberRepositoryId());
    if (updated == 0) {
      jdbcTemplate.update("""
          INSERT INTO staging_group_change
            (session_id, group_repository_id, member_repository_id, action, position, status)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          record.sessionId(),
          record.groupRepositoryId(),
          record.memberRepositoryId(),
          record.action(),
          record.position(),
          record.status());
    }
  }

  @Override
  public int updateGroupChangeStatus(long changeId, String status) {
    return jdbcTemplate.update("""
        UPDATE staging_group_change
        SET status = ?, updated_at = CURRENT_TIMESTAMP(3)
        WHERE id = ?
        """, status, changeId);
  }

  @Override
  public int deleteGroupChange(long sessionId, long changeId) {
    return jdbcTemplate.update("""
        DELETE FROM staging_group_change
        WHERE session_id = ? AND id = ?
        """, sessionId, changeId);
  }

  @Override
  public int deleteGroupChanges(long sessionId) {
    return jdbcTemplate.update(
        "DELETE FROM staging_group_change WHERE session_id = ?", sessionId);
  }

  @Override
  public Map<String, Long> countSessionsByState() {
    return counts("SELECT state, COUNT(*) FROM staging_session GROUP BY state");
  }

  @Override
  public Map<String, Long> countItemsByStatus() {
    return counts("SELECT status, COUNT(*) FROM staging_item GROUP BY status");
  }

  @Override
  public Map<String, Long> countGroupChangesByStatus() {
    return counts("SELECT status, COUNT(*) FROM staging_group_change GROUP BY status");
  }

  private Map<String, Long> counts(String sql) {
    return jdbcTemplate.query(sql, (rs, rowNum) ->
            Map.entry(rs.getString(1), rs.getLong(2)))
        .stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public int deleteSession(long sessionId) {
    return jdbcTemplate.update("DELETE FROM staging_session WHERE id = ?", sessionId);

  }

  @Override
  public long insertPromotion(StagingPromotionRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO staging_promotion
          (session_id, state, fence_token, manifest_sha256, report_json, started_at, finished_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setLong(1, record.sessionId());
      ps.setString(2, record.state());
      ps.setString(3, record.fenceToken());
      ps.setString(4, record.manifestSha256());
      jsonColumns.bind(ps, 5, record.report());
      ps.setTimestamp(6, nullableTimestamp(record.startedAt()));
      ps.setTimestamp(7, nullableTimestamp(record.finishedAt()));
    });
  }

  @Override
  public Optional<StagingPromotionRecord> findPromotion(long promotionId) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_promotion WHERE id = ?", promotionMapper, promotionId)
        .stream().findFirst();
  }

  @Override
  public Optional<StagingPromotionRecord> lockPromotion(long promotionId) {
    return jdbcTemplate.query(
        "SELECT * FROM staging_promotion WHERE id = ? FOR UPDATE", promotionMapper, promotionId)
        .stream().findFirst();
  }

  @Override
  public Optional<StagingPromotionRecord> findLatestPromotion(long sessionId) {
    return jdbcTemplate.query("""
        SELECT * FROM staging_promotion
        WHERE session_id = ?
        ORDER BY id DESC
        LIMIT 1
        """, promotionMapper, sessionId).stream().findFirst();
  }

  @Override
  public int updatePromotion(
      long promotionId,
      String state,
      String manifestSha256,
      Map<String, Object> report,
      Instant startedAt,
      Instant finishedAt) {
    return jdbcTemplate.update("""
        UPDATE staging_promotion
        SET state = ?,
            manifest_sha256 = COALESCE(?, manifest_sha256),
            report_json = ?,
            started_at = COALESCE(?, started_at),
            finished_at = COALESCE(?, finished_at)
        WHERE id = ?
        """,
        state,
        manifestSha256,
        jsonColumns.parameter(report),
        nullableTimestamp(startedAt),
        nullableTimestamp(finishedAt),
        promotionId);
  }

  private static List<String> sanitizeStates(Collection<String> states) {
    if (states == null) {
      return List.of();
    }
    return states.stream()
        .filter(java.util.Objects::nonNull)
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
  }
}
