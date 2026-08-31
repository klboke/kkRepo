package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao.Claim;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRepositoryIndexRebuildDao implements com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao {
  public static final String HELM_INDEX = "HELM_INDEX";
  public static final String HELM_GROUP_CONTENT_INVALIDATION =
      "HELM_GROUP_CONTENT_INVALIDATION";
  public static final String HELM_GROUP_INVALIDATION = "HELM_GROUP_INVALIDATION";
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
    enqueueTracked(repositoryId, indexKind, scopeKey);
  }

  public String enqueueTracked(long repositoryId, String indexKind, String scopeKey) {
    String normalizedScope = scope(scopeKey);
    String requestToken = UUID.randomUUID().toString();
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE repository_index_rebuild_marker
        SET requested_at = CURRENT_TIMESTAMP, attempts = 0,
            last_attempted_at = NULL, last_error = NULL, request_token = ?
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ?
        """,
        new Object[]{requestToken, repositoryId, indexKind, normalizedScope},
        """
        INSERT INTO repository_index_rebuild_marker
          (repository_id, index_kind, scope_key, requested_at, attempts, last_attempted_at,
           last_error, request_token)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0, NULL, NULL, ?)
        """,
        new Object[]{repositoryId, indexKind, normalizedScope, requestToken});
    return requestToken;
  }

  public boolean acknowledgeIfRequestToken(
      long repositoryId, String indexKind, String scopeKey, String requestToken) {
    if (requestToken == null || requestToken.isBlank()) return false;
    return jdbcTemplate.update("""
        DELETE FROM repository_index_rebuild_marker
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ? AND request_token = ?
        """, repositoryId, indexKind, scope(scopeKey), requestToken) > 0;
  }

  public String enqueueHelmGroupInvalidation(long repositoryId, String invalidationKind) {
    requireHelmGroupInvalidationKind(invalidationKind);
    String requestToken = UUID.randomUUID().toString();
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE helm_group_invalidation_marker
        SET requested_at = CURRENT_TIMESTAMP, attempts = 0,
            last_attempted_at = NULL, last_error = NULL, request_token = ?
        WHERE repository_id = ? AND invalidation_kind = ?
        """,
        new Object[] {requestToken, repositoryId, invalidationKind},
        """
        INSERT INTO helm_group_invalidation_marker
          (repository_id, invalidation_kind, requested_at, request_token, attempts,
           last_attempted_at, last_error)
        VALUES (?, ?, CURRENT_TIMESTAMP, ?, 0, NULL, NULL)
        """,
        new Object[] {repositoryId, invalidationKind, requestToken});
    return requestToken;
  }

  public boolean acknowledgeHelmGroupInvalidationIfRequestToken(
      long repositoryId, String invalidationKind, String requestToken) {
    requireHelmGroupInvalidationKind(invalidationKind);
    if (requestToken == null || requestToken.isBlank()) return false;
    return jdbcTemplate.update(
            """
            DELETE FROM helm_group_invalidation_marker
            WHERE repository_id = ? AND invalidation_kind = ? AND request_token = ?
            """,
            repositoryId,
            invalidationKind,
            requestToken)
        > 0;
  }

  public void reenqueueFailure(Claim claim, RuntimeException error) {
    if (isHelmGroupInvalidationKind(claim.indexKind())) {
      reenqueueHelmGroupInvalidationFailure(claim, error);
      return;
    }
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

  @Transactional(propagation = Propagation.MANDATORY)
  public List<Claim> claimHelmGroupInvalidations(int maxItems) {
    List<Claim> claims =
        jdbcTemplate.query(
            """
            SELECT repository_id, invalidation_kind, requested_at, attempts, last_error
            FROM helm_group_invalidation_marker
            ORDER BY requested_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            (rs, rowNum) ->
                new Claim(
                    rs.getLong("repository_id"),
                    rs.getString("invalidation_kind"),
                    ROOT_SCOPE,
                    rs.getTimestamp("requested_at").toInstant(),
                    rs.getInt("attempts"),
                    rs.getString("last_error")),
            Math.max(1, maxItems));
    if (claims.isEmpty()) return claims;
    List<Object[]> args = new ArrayList<>(claims.size());
    for (Claim claim : claims) {
      args.add(new Object[] {claim.repositoryId(), claim.indexKind()});
    }
    jdbcTemplate.batchUpdate(
        """
        DELETE FROM helm_group_invalidation_marker
        WHERE repository_id = ? AND invalidation_kind = ?
        """,
        args);
    return claims;
  }

  public long countBacklog() {
    Long count = jdbcTemplate.queryForObject(
        """
        SELECT
          (SELECT COUNT(*) FROM repository_index_rebuild_marker)
          + (SELECT COUNT(*) FROM helm_group_invalidation_marker)
        """,
        Long.class);
    return count == null ? 0 : count;
  }

  public long oldestBacklogAgeSeconds() {
    Long seconds = jdbcTemplate.queryForObject(
        "SELECT " + coordinationDialect.oldestBacklogAgeSecondsExpression("requested_at")
            + " FROM ("
            + "SELECT requested_at FROM repository_index_rebuild_marker "
            + "UNION ALL SELECT requested_at FROM helm_group_invalidation_marker"
            + ") rebuild_backlog",
        Long.class);
    return seconds == null ? 0 : seconds;
  }

  public long countFailures() {
    Long count = jdbcTemplate.queryForObject("""
        SELECT
          (SELECT COUNT(*) FROM repository_index_rebuild_marker WHERE attempts > 0)
          + (SELECT COUNT(*) FROM helm_group_invalidation_marker WHERE attempts > 0)
        """, Long.class);
    return count == null ? 0 : count;
  }

  public boolean hasPending(long repositoryId, String indexKind, String scopeKey) {
    if (isHelmGroupInvalidationKind(indexKind)) {
      Long count =
          jdbcTemplate.queryForObject(
              """
              SELECT COUNT(*)
              FROM helm_group_invalidation_marker
              WHERE repository_id = ? AND invalidation_kind = ?
              """,
              Long.class,
              repositoryId,
              indexKind);
      return count != null && count > 0;
    }
    Long count = jdbcTemplate.queryForObject("""
        SELECT COUNT(*)
        FROM repository_index_rebuild_marker
        WHERE repository_id = ? AND index_kind = ? AND scope_key = ?
        """, Long.class, repositoryId, indexKind, scope(scopeKey));
    return count != null && count > 0;
  }

  private void reenqueueHelmGroupInvalidationFailure(Claim claim, RuntimeException error) {
    requireHelmGroupInvalidationKind(claim.indexKind());
    int attempts = claim.attempts() + 1;
    String lastError = truncate(errorSummary(error), 2000);
    String requestToken = UUID.randomUUID().toString();
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE helm_group_invalidation_marker
        SET requested_at = CURRENT_TIMESTAMP, request_token = ?, attempts = ?,
            last_attempted_at = CURRENT_TIMESTAMP, last_error = ?
        WHERE repository_id = ? AND invalidation_kind = ?
        """,
        new Object[] {
          requestToken, attempts, lastError, claim.repositoryId(), claim.indexKind()
        },
        """
        INSERT INTO helm_group_invalidation_marker
          (repository_id, invalidation_kind, requested_at, request_token, attempts,
           last_attempted_at, last_error)
        VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?, CURRENT_TIMESTAMP, ?)
        """,
        new Object[] {
          claim.repositoryId(), claim.indexKind(), requestToken, attempts, lastError
        });
  }

  private static boolean isHelmGroupInvalidationKind(String indexKind) {
    return HELM_GROUP_CONTENT_INVALIDATION.equals(indexKind)
        || HELM_GROUP_INVALIDATION.equals(indexKind);
  }

  private static void requireHelmGroupInvalidationKind(String invalidationKind) {
    if (!isHelmGroupInvalidationKind(invalidationKind)) {
      throw new IllegalArgumentException(
          "Unsupported Helm group invalidation kind: " + invalidationKind);
    }
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
