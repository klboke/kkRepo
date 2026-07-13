package com.github.klboke.kkrepo.persistence.jdbc.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAuthTicketDao implements com.github.klboke.kkrepo.persistence.jdbc.api.AuthTicketDao {
  private final JdbcTemplate jdbcTemplate;

  public JdbcAuthTicketDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void insert(String tokenHash, String payload, Instant expiresAt) {
    jdbcTemplate.update("""
        INSERT INTO auth_ticket (token_hash, payload, expires_at, created_at)
        VALUES (?, ?, ?, NOW(3))
        """, normalizeTokenHash(tokenHash), requiredPayload(payload), Timestamp.from(expiresAt));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<String> consume(String tokenHash, Instant now) {
    String normalized = normalizeTokenHash(tokenHash);
    List<TicketRow> rows = jdbcTemplate.query("""
        SELECT payload, expires_at
        FROM auth_ticket
        WHERE token_hash = ?
        FOR UPDATE
        """, (rs, rowNum) -> new TicketRow(
            rs.getString("payload"),
            rs.getTimestamp("expires_at").toInstant()),
        normalized);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    jdbcTemplate.update("DELETE FROM auth_ticket WHERE token_hash = ?", normalized);
    TicketRow row = rows.get(0);
    return row.expiresAt().isAfter(now) ? Optional.of(row.payload()) : Optional.empty();
  }

  public int deleteExpired(Instant now) {
    return jdbcTemplate.update("DELETE FROM auth_ticket WHERE expires_at <= ?", Timestamp.from(now));
  }

  private static String normalizeTokenHash(String tokenHash) {
    if (tokenHash == null || tokenHash.isBlank()) {
      throw new IllegalArgumentException("tokenHash is required");
    }
    String normalized = tokenHash.trim();
    if (normalized.length() != 64) {
      throw new IllegalArgumentException("tokenHash must be a SHA-256 hex string");
    }
    return normalized;
  }

  private static String requiredPayload(String payload) {
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("payload is required");
    }
    return payload;
  }

  private record TicketRow(String payload, Instant expiresAt) {}
}
