package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Database-neutral JDBC implementation of the generic artifact content-change stream. */
@Repository
public class JdbcArtifactChangeDao implements ArtifactChangeDao {
  private final JdbcTemplate jdbc;

  public JdbcArtifactChangeDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long append(ArtifactChange change) {
    return JdbcInserts.insert(jdbc, """
        INSERT INTO artifact_change_event
          (repository_id, asset_id, previous_asset_blob_id, asset_blob_id,
           change_kind, occurred_at)
        VALUES (?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))
        """, ps -> {
      ps.setLong(1, change.repositoryId());
      ps.setLong(2, change.assetId());
      if (change.previousAssetBlobId() == null) {
        ps.setObject(3, null);
      } else {
        ps.setLong(3, change.previousAssetBlobId());
      }
      ps.setLong(4, change.assetBlobId());
      ps.setString(5, change.changeKind().name());
      ps.setTimestamp(6, nullableTimestamp(change.occurredAt()));
    });
  }

  @Override
  public List<ArtifactChange> listAfter(long lastSeenId, int maxItems) {
    return jdbc.query("""
        SELECT id, repository_id, asset_id, previous_asset_blob_id, asset_blob_id,
               change_kind, occurred_at
        FROM artifact_change_event
        WHERE id > ?
        ORDER BY id
        LIMIT ?
        """, (rs, rowNum) -> new ArtifactChange(
        rs.getLong("id"),
        rs.getLong("repository_id"),
        rs.getLong("asset_id"),
        nullableLong(rs, "previous_asset_blob_id"),
        rs.getLong("asset_blob_id"),
        ChangeKind.valueOf(rs.getString("change_kind")),
        nullableInstant(rs, "occurred_at")),
        Math.max(0, lastSeenId),
        Math.max(1, maxItems));
  }

  @Override
  public int deleteThrough(long consumedThroughId, int maxItems) {
    List<Long> ids = jdbc.queryForList("""
        SELECT id
        FROM artifact_change_event
        WHERE id <= ?
        ORDER BY id
        LIMIT ?
        """, Long.class, Math.max(0, consumedThroughId), Math.max(1, maxItems));
    if (ids.isEmpty()) return 0;
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    return jdbc.update(
        "DELETE FROM artifact_change_event WHERE id IN (" + placeholders + ")",
        ids.toArray());
  }

  @Override
  public Optional<EventRange> retainedRange() {
    return jdbc.query("""
        SELECT oldest.id AS oldest_id,
               newest.id AS newest_id,
               oldest.occurred_at AS oldest_occurred_at
        FROM (
          SELECT id, occurred_at
          FROM artifact_change_event
          ORDER BY id
          LIMIT 1
        ) oldest
        CROSS JOIN (
          SELECT id
          FROM artifact_change_event
          ORDER BY id DESC
          LIMIT 1
        ) newest
        """, (rs, rowNum) -> new EventRange(
        rs.getLong("oldest_id"),
        rs.getLong("newest_id"),
        nullableInstant(rs, "oldest_occurred_at"))).stream().findFirst();
  }
}
