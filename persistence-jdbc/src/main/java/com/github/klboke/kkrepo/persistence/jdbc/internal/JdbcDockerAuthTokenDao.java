package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.TokenRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.TokenKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.ScannerResourceKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao.ScannerTokenResource;
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
      TokenKind tokenKind,
      List<Map<String, Object>> scopes,
      Instant expiresAt) {
    TokenKind storedKind = tokenKind == null ? TokenKind.USER : tokenKind;
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
        jsonColumns.parameter(Map.of(
            "tokenKind", storedKind.name(),
            "scopes", scopes == null ? List.of() : scopes)),
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
        """, (rs, rowNum) -> {
          Map<String, Object> tokenData = jsonColumns.read(rs.getString("scopes_json"));
          return new TokenRecord(
              rs.getString("token_hash"),
              rs.getString("subject_source"),
              rs.getString("subject_user_id"),
              rs.getString("subject_realm_id"),
              rs.getObject("subject_api_key_id") == null ? null : rs.getLong("subject_api_key_id"),
              tokenKind(tokenData),
              tokenData,
              rs.getTimestamp("expires_at").toInstant());
        },
        normalize(tokenHash),
        Timestamp.from(now)).stream().findFirst();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insertScannerResources(
      String tokenHash, List<ScannerTokenResource> resources) {
    String normalizedHash = normalize(tokenHash);
    List<ScannerTokenResource> distinct = resources == null
        ? List.of()
        : resources.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return;
    }
    jdbcTemplate.batchUpdate("""
        INSERT INTO docker_scanner_token_resource
          (token_hash, resource_kind, digest)
        VALUES (?, ?, ?)
        """,
        distinct,
        500,
        (ps, resource) -> {
          ps.setString(1, normalizedHash);
          ps.setString(2, resource.resourceKind().name());
          ps.setString(3, resource.digest());
        });
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean scannerResourceAllowed(
      String tokenHash, ScannerResourceKind resourceKind, String digest) {
    if (resourceKind == null || digest == null || digest.isBlank()) {
      return false;
    }
    return !jdbcTemplate.queryForList("""
        SELECT token_hash
        FROM docker_scanner_token_resource
        WHERE token_hash = ?
          AND resource_kind = ?
          AND digest = ?
        LIMIT 1
        """,
        String.class,
        normalize(tokenHash),
        resourceKind.name(),
        digest).isEmpty();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteExpired(Instant now, int maxItems) {
    if (maxItems <= 0) {
      return 0;
    }
    List<String> tokenHashes = jdbcTemplate.queryForList("""
        SELECT token_hash
        FROM docker_auth_token
        WHERE expires_at <= ?
        ORDER BY expires_at
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """, String.class, Timestamp.from(now), maxItems);
    if (tokenHashes.isEmpty()) {
      return 0;
    }
    String placeholders =
        String.join(",", java.util.Collections.nCopies(tokenHashes.size(), "?"));
    return jdbcTemplate.update(
        "DELETE FROM docker_auth_token WHERE token_hash IN (" + placeholders + ")",
        tokenHashes.toArray());
  }

  private static String normalize(String tokenHash) {
    if (tokenHash == null || tokenHash.length() != 64) {
      throw new IllegalArgumentException("tokenHash must be SHA-256 hex");
    }
    return tokenHash;
  }

  private static TokenKind tokenKind(Map<String, Object> tokenData) {
    Object value = tokenData.get("tokenKind");
    if (value == null) {
      return TokenKind.USER;
    }
    try {
      return TokenKind.valueOf(value.toString());
    } catch (IllegalArgumentException ignored) {
      return TokenKind.USER;
    }
  }

}
