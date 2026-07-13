package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMigrationCheckpointDao implements com.github.klboke.kkrepo.persistence.jdbc.api.MigrationCheckpointDao {
  private final JdbcTemplate jdbcTemplate;
  private final RowMapper<MigrationCheckpointRecord> rowMapper;

  public JdbcMigrationCheckpointDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.rowMapper = (rs, rowNum) -> new MigrationCheckpointRecord(
        rs.getLong("job_id"),
        rs.getString("source_database"),
        rs.getString("source_class"),
        rs.getString("source_rid"),
        rs.getString("target_table"),
        rs.getString("target_id"),
        rs.getString("source_checksum"),
        nullableInstant(rs, "migrated_at"));
  }

  public void upsert(MigrationCheckpointRecord record) {
    jdbcTemplate.update("""
        INSERT INTO migration_checkpoint
          (job_id, source_database, source_class, source_rid, target_table, target_id, source_checksum)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          target_table = VALUES(target_table),
          target_id = VALUES(target_id),
          source_checksum = VALUES(source_checksum),
          migrated_at = CURRENT_TIMESTAMP(3)
        """,
        record.jobId(),
        record.sourceDatabase(),
        record.sourceClass(),
        record.sourceRid(),
        record.targetTable(),
        record.targetId(),
        record.sourceChecksum());
  }

  public Optional<MigrationCheckpointRecord> find(
      long jobId,
      String sourceDatabase,
      String sourceClass,
      String sourceRid) {
    return jdbcTemplate.query("""
        SELECT * FROM migration_checkpoint
        WHERE job_id = ? AND source_database = ? AND source_class = ? AND source_rid = ?
        """, rowMapper, jobId, sourceDatabase, sourceClass, sourceRid).stream().findFirst();
  }
}
