package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.TokenRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDockerAuthTokenDao implements com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao {
  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;

  public JdbcDockerAuthTokenDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
  }

  public void insert(
      String tokenHash,
      String source,
      String userId,
      String realmId,
      Long apiKeyId,
      List<Map<String, Object>> scopes,
      Instant expiresAt) {
    jdbcTemplate.update("""
        INSERT INTO docker_auth_token
          (token_hash, subject_source, subject_user_id, subject_realm_id,
           subject_api_key_id, scopes_json, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        normalize(tokenHash),
        source,
        userId,
        realmId,
        apiKeyId,
        jsonColumns.parameter(Map.of("scopes", scopes == null ? List.of() : scopes)),
        Timestamp.from(expiresAt));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<TokenRecord> findValid(String tokenHash, Instant now) {
    return jdbcTemplate.query("""
        SELECT *
        FROM docker_auth_token
        WHERE token_hash = ?
          AND expires_at > ?
        FOR UPDATE
        """, (rs, rowNum) -> new TokenRecord(
            rs.getString("token_hash"),
            rs.getString("subject_source"),
            rs.getString("subject_user_id"),
            rs.getString("subject_realm_id"),
            rs.getObject("subject_api_key_id") == null ? null : rs.getLong("subject_api_key_id"),
            jsonColumns.read(rs.getString("scopes_json")),
            rs.getTimestamp("expires_at").toInstant()),
        normalize(tokenHash),
        Timestamp.from(now)).stream().findFirst();
  }

  public int deleteExpired(Instant now) {
    return jdbcTemplate.update("DELETE FROM docker_auth_token WHERE expires_at <= ?", Timestamp.from(now));
  }

  private static String normalize(String tokenHash) {
    if (tokenHash == null || tokenHash.length() != 64) {
      throw new IllegalArgumentException("tokenHash must be SHA-256 hex");
    }
    return tokenHash;
  }

}
