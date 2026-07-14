package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao.ProxyRemoteState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * DB-backed proxy circuit-breaker state. All replicas read/write the same row so block decisions
 * stay consistent across the cluster.
 */
@Repository
public class JdbcProxyStateDao implements com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao {
  private final JdbcTemplate jdbcTemplate;
  private final RowMapper<ProxyRemoteState> rowMapper;
  private final int successThrottleSeconds;

  public JdbcProxyStateDao(
      JdbcTemplate jdbcTemplate,
      @Value("${kkrepo.maven.proxy-success-throttle-seconds:30}") int successThrottleSeconds) {
    this.jdbcTemplate = jdbcTemplate;
    this.successThrottleSeconds = Math.max(0, successThrottleSeconds);
    this.rowMapper = (rs, rowNum) -> new ProxyRemoteState(
        rs.getLong("repository_id"),
        nullableInstant(rs, "blocked_until"),
        rs.getInt("fail_count"),
        nullableInstant(rs, "last_success_at"),
        nullableInstant(rs, "last_failure_at"),
        rs.getString("last_error"));
  }

  public Optional<ProxyRemoteState> loadState(long repositoryId) {
    return jdbcTemplate.query(
        "SELECT * FROM proxy_remote_state WHERE repository_id = ?",
        rowMapper, repositoryId).stream().findFirst();
  }

  public boolean isBlocked(long repositoryId, Instant now) {
    return loadState(repositoryId)
        .map(s -> s.blockedUntil() != null && s.blockedUntil().isAfter(now))
        .orElse(false);
  }

  /**
   * Records an upstream success. Proxy GETs fan out to hundreds per second per repo, so we throttle
   * the actual row write: if the row is already healthy (fail_count = 0, no block) and the last
   * recorded success is within {@code successThrottleSeconds}, the read path returns without a
   * write. The throttled path costs one round trip plus an index lookup without changing the shared
   * row. The transition out of a failure state (fail_count > 0 or blocked_until set) always writes
   * immediately so the circuit breaker reopens without delay.
   */
  public void recordSuccess(long repositoryId, Instant now) {
    var recordedAt = nullableTimestamp(now);
    Instant throttleBoundaryInstant = now.minusSeconds(successThrottleSeconds);
    var throttleBoundary = nullableTimestamp(throttleBoundaryInstant);
    Optional<ProxyRemoteState> existing = loadState(repositoryId);
    if (existing.isPresent()) {
      ProxyRemoteState state = existing.orElseThrow();
      if (state.failCount() == 0
          && state.blockedUntil() == null
          && state.lastSuccessAt() != null
          && !state.lastSuccessAt().isBefore(throttleBoundaryInstant)) {
        return;
      }
      clearFailureState(repositoryId, recordedAt, throttleBoundary);
      return;
    }
    try {
      jdbcTemplate.update("""
          INSERT INTO proxy_remote_state
            (repository_id, blocked_until, fail_count, last_success_at, last_failure_at, last_error)
          VALUES (?, NULL, 0, ?, NULL, NULL)
          """, repositoryId, recordedAt);
    } catch (DuplicateKeyException concurrentInsertOrThrottledExistingRow) {
      // A concurrent failure may have inserted the row after the initial read. Retry the clearing
      // UPDATE so that success wins that race; a concurrently inserted healthy row remains a
      // deliberate no-op when it is inside the throttle window.
      clearFailureState(repositoryId, recordedAt, throttleBoundary);
    }
  }

  private int clearFailureState(
      long repositoryId, Timestamp recordedAt, Timestamp throttleBoundary) {
    return jdbcTemplate.update("""
        UPDATE proxy_remote_state
        SET blocked_until = NULL, fail_count = 0, last_success_at = ?, last_error = NULL
        WHERE repository_id = ?
          AND (fail_count <> 0 OR blocked_until IS NOT NULL
            OR last_success_at IS NULL OR last_success_at < ?)
        """, recordedAt, repositoryId, throttleBoundary);
  }

  public ProxyRemoteState recordFailure(long repositoryId, long blockSeconds, String error, Instant now) {
    Optional<ProxyRemoteState> existing = loadState(repositoryId);
    int failCount = existing.map(ProxyRemoteState::failCount).orElse(0) + 1;
    Instant blockUntil = blockSeconds > 0 ? now.plusSeconds(blockSeconds) : null;
    String truncated = error == null ? null : (error.length() > 1024 ? error.substring(0, 1024) : error);
    if (existing.isPresent()) {
      jdbcTemplate.update("""
          UPDATE proxy_remote_state
          SET blocked_until = ?, fail_count = ?, last_failure_at = ?, last_error = ?
          WHERE repository_id = ?
          """, nullableTimestamp(blockUntil), failCount, nullableTimestamp(now), truncated, repositoryId);
    } else {
      jdbcTemplate.update("""
          INSERT INTO proxy_remote_state
            (repository_id, blocked_until, fail_count, last_success_at, last_failure_at, last_error)
          VALUES (?, ?, ?, NULL, ?, ?)
          """, repositoryId, nullableTimestamp(blockUntil), failCount, nullableTimestamp(now), truncated);
    }
    return new ProxyRemoteState(repositoryId, blockUntil, failCount, existing.flatMap(s -> Optional.ofNullable(s.lastSuccessAt())).orElse(null), now, truncated);
  }

}
