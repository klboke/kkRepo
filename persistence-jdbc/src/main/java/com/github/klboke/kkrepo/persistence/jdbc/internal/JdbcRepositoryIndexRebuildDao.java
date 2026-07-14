package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao.Claim;
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

@Repository
public class JdbcRepositoryIndexRebuildDao implements com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao {
  public static final String HELM_INDEX = "HELM_INDEX";
  public static final String PYPI_ROOT = "PYPI_ROOT";
  public static final String PYPI_PROJECT = "PYPI_PROJECT";
  public static final String YUM_METADATA = "YUM_METADATA";
  public static final String RUBYGEMS_METADATA = "RUBYGEMS_METADATA";
  public static final String ROOT_SCOPE = "";

  private final JdbcTemplate jdbcTemplate;
  private final CoordinationPersistenceDialect coordinationDialect;

  public JdbcRepositoryIndexRebuildDao(
      JdbcTemplate jdbcTemplate,
      DatabaseDialect databaseDialect) {
    this.jdbcTemplate = jdbcTemplate;
    this.coordinationDialect = databaseDialect.coordination();
  }

  public void enqueue(long repositoryId, String indexKind) {
    enqueue(repositoryId, indexKind, ROOT_SCOPE);
  }

  public void enqueue(long repositoryId, String indexKind, String scopeKey) {
    String normalizedScope = scope(scopeKey);
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE repository_index_rebuild_marker
        SET requested_at = CURRENT_TIMESTAMP, attempts = 0,
            last_attempted_at = NULL, last_error = NULL
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ?
        """,
        new Object[]{repositoryId, indexKind, normalizedScope},
        """
        INSERT INTO repository_index_rebuild_marker
          (repository_id, index_kind, scope_key, requested_at, attempts, last_attempted_at, last_error)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0, NULL, NULL)
        """,
        new Object[]{repositoryId, indexKind, normalizedScope});
  }

  public void reenqueueFailure(Claim claim, RuntimeException error) {
    int attempts = claim.attempts() + 1;
    String lastError = truncate(errorSummary(error), 2000);
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE repository_index_rebuild_marker
        SET requested_at = CURRENT_TIMESTAMP, attempts = ?,
            last_attempted_at = CURRENT_TIMESTAMP, last_error = ?
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ?
        """,
        new Object[]{attempts, lastError, claim.repositoryId(), claim.indexKind(), claim.scopeKey()},
        """
        INSERT INTO repository_index_rebuild_marker
          (repository_id, index_kind, scope_key, requested_at, attempts, last_attempted_at, last_error)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
        """,
        new Object[]{claim.repositoryId(), claim.indexKind(), claim.scopeKey(), attempts, lastError});
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<Claim> claim(int maxItems) {
    List<Claim> claims = jdbcTemplate.query("""
        SELECT repository_id, index_kind, scope_key, requested_at, attempts, last_error
        FROM repository_index_rebuild_marker
        ORDER BY requested_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, (rs, rowNum) -> new Claim(
            rs.getLong("repository_id"),
            rs.getString("index_kind"),
            rs.getString("scope_key"),
            rs.getTimestamp("requested_at").toInstant(),
            rs.getInt("attempts"),
            rs.getString("last_error")),
        Math.max(1, maxItems));
    if (claims.isEmpty()) return claims;
    List<Object[]> args = new ArrayList<>(claims.size());
    for (Claim claim : claims) {
      args.add(new Object[]{claim.repositoryId(), claim.indexKind(), claim.scopeKey()});
    }
    jdbcTemplate.batchUpdate("""
        DELETE FROM repository_index_rebuild_marker
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ?
        """, args);
    return claims;
  }

  public long countBacklog() {
    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM repository_index_rebuild_marker",
        Long.class);
    return count == null ? 0 : count;
  }

  public long oldestBacklogAgeSeconds() {
    Long seconds = jdbcTemplate.queryForObject(
        "SELECT " + coordinationDialect.oldestBacklogAgeSecondsExpression("requested_at")
            + " FROM repository_index_rebuild_marker",
        Long.class);
    return seconds == null ? 0 : seconds;
  }

  public long countFailures() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*) FROM repository_index_rebuild_marker WHERE attempts > 0
        """, Long.class);
    return count == null ? 0 : count;
  }

  public boolean hasPending(long repositoryId, String indexKind, String scopeKey) {
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM repository_index_rebuild_marker
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ?
        """, Long.class, repositoryId, indexKind, scope(scopeKey));
    return count != null && count > 0;
  }

  private static String scope(String scopeKey) {
    return scopeKey == null ? ROOT_SCOPE : scopeKey;
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
