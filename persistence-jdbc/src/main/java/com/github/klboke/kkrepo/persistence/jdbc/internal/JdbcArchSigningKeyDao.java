package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableInstant;
import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableTimestamp;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArchSigningKeyDao;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Portable MySQL/PostgreSQL implementation of pacman signing-key storage. */
@Repository
public class JdbcArchSigningKeyDao implements ArchSigningKeyDao {
  private final JdbcTemplate jdbc;
  private final RowMapper<SigningKey> mapper = this::map;

  public JdbcArchSigningKeyDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<SigningKey> findActiveSigningKey(long repositoryId) {
    return jdbc.query(
        """
        SELECT * FROM arch_signing_key
        WHERE repository_id = ? AND active = TRUE ORDER BY revision DESC
        """, mapper, repositoryId).stream().findFirst();
  }

  @Override
  public Optional<SigningKey> findSigningKey(long repositoryId, int revision) {
    return jdbc.query(
        "SELECT * FROM arch_signing_key WHERE repository_id = ? AND revision = ?",
        mapper, repositoryId, revision).stream().findFirst();
  }

  @Override
  public List<SigningKey> listSigningKeys(long repositoryId, int limit) {
    int boundedLimit = Math.max(1, Math.min(limit, 16));
    return jdbc.query(
        """
        SELECT * FROM arch_signing_key
        WHERE repository_id = ? ORDER BY revision DESC LIMIT ?
        """, mapper, repositoryId, boundedLimit);
  }

  @Override
  public int nextRevisionLocked(long repositoryId) {
    // The lock row is the repository itself: arch_signing_key may be empty on the first rotation,
    // so locking existing key rows would not serialize concurrent first-time rotations.
    jdbc.queryForObject("SELECT id FROM repository WHERE id = ? FOR UPDATE", Long.class,
        repositoryId);
    Integer revision = jdbc.queryForObject(
        "SELECT COALESCE(MAX(revision), 0) FROM arch_signing_key WHERE repository_id = ?",
        Integer.class, repositoryId);
    return (revision == null ? 0 : revision) + 1;
  }

  @Override
  @Transactional
  public void insertSigningKey(SigningKey key) {
    jdbc.update("UPDATE arch_signing_key SET active = FALSE WHERE repository_id = ?",
        key.repositoryId());
    jdbc.update(
        """
        INSERT INTO arch_signing_key
          (repository_id, revision, key_id, fingerprint, encrypted_private_key, public_key,
           active, created_at)
        VALUES (?, ?, ?, ?, ?, ?, TRUE, ?)
        """, key.repositoryId(), key.revision(), key.keyId(), key.fingerprint(),
        key.encryptedPrivateKey(), key.publicKey(), nullableTimestamp(key.createdAt()));
  }

  private SigningKey map(ResultSet rs, int rowNum) throws SQLException {
    return new SigningKey(
        rs.getLong("repository_id"),
        rs.getInt("revision"),
        rs.getString("key_id"),
        rs.getString("fingerprint"),
        rs.getString("encrypted_private_key"),
        rs.getString("public_key"),
        rs.getBoolean("active"),
        nullableInstant(rs, "created_at"));
  }
}
