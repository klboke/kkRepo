package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import java.util.List;
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
}
