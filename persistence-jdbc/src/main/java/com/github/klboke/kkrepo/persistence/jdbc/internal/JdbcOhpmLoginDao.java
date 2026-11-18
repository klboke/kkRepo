package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmLoginDao;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JdbcOhpmLoginDao implements OhpmLoginDao {
  private static final String SELECT = """
      SELECT signing.*, owner.source, owner.user_id, owner.status AS user_status
      FROM ohpm_login_key signing JOIN security_user owner ON owner.id = signing.user_numeric_id
      """;
  private final JdbcTemplate jdbc;
  private final RowMapper<Key> mapper = (row, index) -> new Key(row.getString("publish_id"),
      row.getLong("user_numeric_id"), row.getString("source"), row.getString("user_id"), row.getString("user_status"),
      row.getString("public_key"), row.getString("fingerprint"), row.getBoolean("enabled"),
      row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

  public JdbcOhpmLoginDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Key putKey(String source, String userId, String publicKey, String fingerprint, boolean enabled) {
    requireTransaction();
    if (publicKey == null || publicKey.length() > 16384 || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid OHPM signing key");
    }
    long userNumericId = jdbc.query("SELECT id FROM security_user WHERE source = ? AND user_id = ? FOR UPDATE",
        (row, index) -> row.getLong("id"), source, userId).stream().findFirst()
        .orElseThrow(() -> new IllegalArgumentException("OHPM signing key owner is missing"));
    Optional<Key> existing = jdbc.query(SELECT + " WHERE signing.user_numeric_id = ? FOR UPDATE",
        mapper, userNumericId).stream().findFirst();
    String publishId = existing.map(Key::publishId).orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
    LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    if (existing.isPresent()) {
      jdbc.update("UPDATE ohpm_login_key SET public_key = ?, fingerprint = ?, enabled = ?, updated_at = ? WHERE publish_id = ?",
          publicKey, fingerprint, enabled, now, publishId);
    } else {
      jdbc.update("""
          INSERT INTO ohpm_login_key (publish_id, user_numeric_id, public_key, fingerprint, enabled, updated_at)
          VALUES (?, ?, ?, ?, ?, ?)
          """, publishId, userNumericId, publicKey, fingerprint, enabled, now);
    }
    return lockKey(publishId).orElseThrow();
  }

  @Override
  public Optional<Key> findKey(String publishId) {
    return jdbc.query(SELECT + " WHERE signing.publish_id = ?", mapper, publishId).stream().findFirst();
  }

  @Override
  public Optional<Key> lockKey(String publishId) {
    requireTransaction();
    return jdbc.query(SELECT + " WHERE signing.publish_id = ? FOR UPDATE", mapper, publishId).stream().findFirst();
  }

  @Override
  public List<Key> listKeys(int offset, int limit) {
    return jdbc.query(SELECT + " ORDER BY signing.publish_id LIMIT ? OFFSET ?", mapper,
        Math.max(1, Math.min(100, limit)), Math.max(0, offset));
  }

  @Override
  public boolean deleteKey(String publishId) {
    requireTransaction();
    return jdbc.update("DELETE FROM ohpm_login_key WHERE publish_id = ?", publishId) == 1;
  }

  @Override
  public boolean recordNonce(String publishId, String nonce, Instant expiresAt) {
    lockKey(publishId).orElseThrow();
    if (nonce == null || !nonce.matches("[a-f0-9]{32}") || expiresAt == null) {
      throw new IllegalArgumentException("Invalid OHPM nonce");
    }
    if (!jdbc.queryForList("SELECT nonce FROM ohpm_login_nonce WHERE publish_id = ? AND nonce = ? FOR UPDATE",
        String.class, publishId, nonce).isEmpty()) return false;
    jdbc.update("INSERT INTO ohpm_login_nonce (publish_id, nonce, expires_at) VALUES (?, ?, ?)",
        publishId, nonce, LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    return true;
  }

  @Override
  public int deleteExpiredNonces(Instant now) {
    LocalDateTime cutoff = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    List<Object[]> expired = jdbc.query("""
        SELECT publish_id, nonce FROM ohpm_login_nonce WHERE expires_at <= ? ORDER BY expires_at LIMIT 1000
        """, (row, index) -> new Object[] {row.getString("publish_id"), row.getString("nonce"), cutoff}, cutoff);
    if (expired.isEmpty()) return 0;
    int[] deleted = jdbc.batchUpdate(
        "DELETE FROM ohpm_login_nonce WHERE publish_id = ? AND nonce = ? AND expires_at <= ?", expired);
    return java.util.Arrays.stream(deleted).map(count -> Math.max(0, count)).sum();
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("OHPM signing key and nonce changes require a transaction");
    }
  }
}
