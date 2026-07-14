package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao.Claim;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the async metadata-rebuild queue stored in {@code metadata_rebuild_marker}. Hosted PUT /
 * DELETE inserts a marker per (repository, scope_key) — {@code scope_key} encodes either a GA
 * ({@code ga:groupId/artifactId}) or a GAV-SNAPSHOT ({@code gav:groupId/artifactId/baseVersion}).
 * The marker primary key dedupes burst writes for the same artifact; the worker drains the queue
 * with {@code FOR UPDATE SKIP LOCKED} so multiple replicas can run side by side without contending.
 */
@Repository
public class JdbcMetadataRebuildDao implements com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao {
  private final JdbcTemplate jdbcTemplate;
  private final CoordinationPersistenceDialect coordinationDialect;

  public JdbcMetadataRebuildDao(JdbcTemplate jdbcTemplate, DatabaseDialect databaseDialect) {
    this.jdbcTemplate = jdbcTemplate;
    this.coordinationDialect = databaseDialect.coordination();
  }

  /** Idempotent enqueue — bumps {@code requested_at} on a re-enqueue so the worker re-runs. */
  public void enqueue(long repositoryId, String scopeKey) {
    Object[] updateArguments = {repositoryId, scopeKey};
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE metadata_rebuild_marker
        SET requested_at = CURRENT_TIMESTAMP, attempts = 0,
            last_attempted_at = NULL, last_error = NULL
        WHERE repository_id = ? AND scope_key = ?
        """,
        updateArguments,
        """
        INSERT INTO metadata_rebuild_marker
          (repository_id, scope_key, requested_at, attempts, last_attempted_at, last_error)
        VALUES (?, ?, CURRENT_TIMESTAMP, 0, NULL, NULL)
        """,
        updateArguments);
  }

  public void reenqueueFailure(Claim claim, RuntimeException error) {
    int attempts = claim.attempts() + 1;
    String lastError = truncate(errorSummary(error), 2000);
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE metadata_rebuild_marker
        SET requested_at = CURRENT_TIMESTAMP, attempts = ?,
            last_attempted_at = CURRENT_TIMESTAMP, last_error = ?
        WHERE repository_id = ? AND scope_key = ?
        """,
        new Object[]{attempts, lastError, claim.repositoryId(), claim.scopeKey()},
        """
        INSERT INTO metadata_rebuild_marker
          (repository_id, scope_key, requested_at, attempts, last_attempted_at, last_error)
        VALUES (?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
        """,
        new Object[]{claim.repositoryId(), claim.scopeKey(), attempts, lastError});
  }

  public int delete(long repositoryId, String scopeKey) {
    return jdbcTemplate.update("""
        DELETE FROM metadata_rebuild_marker
        WHERE repository_id = ? AND scope_key = ?
        """, repositoryId, scopeKey);
  }

  /**
   * Lease the oldest {@code maxItems} markers, deleting them in the same transaction. Caller MUST
   * call this from within a transaction with REQUIRES_NEW so the row locks are held until the
   * rebuild work either succeeds (commit) or fails (rollback re-queues by leaving the markers).
   *
   * <p>We delete-on-claim rather than mark-in-flight because the work is fully idempotent: if the
   * worker crashes mid-rebuild we lose at most one rebuild cycle, and any further write to the
   * same GA will re-enqueue it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Claim> claim(int maxItems) {
    List<Claim> claims = jdbcTemplate.query("""
        SELECT repository_id, scope_key, requested_at, attempts, last_error
        FROM metadata_rebuild_marker
        ORDER BY requested_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, (rs, rowNum) -> new Claim(
            rs.getLong("repository_id"),
            rs.getString("scope_key"),
            rs.getTimestamp("requested_at").toInstant(),
            rs.getInt("attempts"),
            rs.getString("last_error")),
        maxItems);
    if (claims.isEmpty()) return claims;
    List<Object[]> args = new ArrayList<>(claims.size());
    for (Claim c : claims) {
      args.add(new Object[]{c.repositoryId(), c.scopeKey()});
    }
    jdbcTemplate.batchUpdate(
        "DELETE FROM metadata_rebuild_marker WHERE repository_id = ? AND scope_key = ?",
        args);
    return claims;
  }

  public long countBacklog() {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM metadata_rebuild_marker",
        Long.class);
    return count == null ? 0 : count;
  }

  public long oldestBacklogAgeSeconds() {
    Long seconds = jdbcTemplate.queryForObject(
        "SELECT " + coordinationDialect.oldestBacklogAgeSecondsExpression("requested_at")
            + " FROM metadata_rebuild_marker",
        Long.class);
    return seconds == null ? 0 : seconds;
  }

  public long countFailures() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM metadata_rebuild_marker WHERE attempts > 0
        """, Long.class);
    return count == null ? 0 : count;
  }

  private static String errorSummary(RuntimeException error) {
    if (error == null) return "";
    String message = error.getMessage();
    return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) return value;
    return value.substring(0, maxLength);
  }

}
