package com.github.klboke.kkrepo.persistence.jdbc.internal;

import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMaintenanceCursorDao implements com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao {
  public static final String BLOB_UNREFERENCED_RECONCILE = "blob_unreferenced_reconcile";

  private final JdbcTemplate jdbcTemplate;

  public JdbcMaintenanceCursorDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public OptionalLong tryLockLastSeenId(String taskName) {
    return jdbcTemplate.query("""
        SELECT last_seen_id
        FROM maintenance_cursor
        WHERE task_name = ?
        FOR UPDATE SKIP LOCKED
        """, rs -> rs.next() ? OptionalLong.of(rs.getLong("last_seen_id")) : OptionalLong.empty(), taskName);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int updateLastSeenId(String taskName, long lastSeenId) {
    return jdbcTemplate.update("""
        UPDATE maintenance_cursor
        SET last_seen_id = ?, updated_at = NOW(3)
        WHERE task_name = ?
        """, Math.max(0, lastSeenId), taskName);
  }

  public long lastSeenId(String taskName) {
    Long value = jdbcTemplate.queryForObject("""
        SELECT last_seen_id
        FROM maintenance_cursor
        WHERE task_name = ?
        """, Long.class, taskName);
    return value == null ? 0 : value;
  }
}
