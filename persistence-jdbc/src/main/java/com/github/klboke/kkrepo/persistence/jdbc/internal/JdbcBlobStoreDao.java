package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBlobStoreDao implements com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao {
  /**
   * Attribute keys holding blob-store credentials. These are encrypted at rest and decrypted on
   * read so they are never persisted as plaintext in the {@code attributes_json} column.
   */
  private static final List<String> SENSITIVE_ATTRIBUTES = List.of("accessKey", "secretKey");

  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<BlobStoreRecord> rowMapper;
  private volatile SecretCipher secretCipher;

  public JdbcBlobStoreDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.rowMapper = (rs, rowNum) -> new BlobStoreRecord(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("type"),
        rs.getString("endpoint"),
        rs.getString("region"),
        rs.getString("bucket"),
        rs.getString("prefix"),
        decryptAttributes(jsonColumns.read(rs.getString("attributes_json"))));
  }

  /**
   * Resolves the credential cipher lazily so the secret bound from the application config file via
   * {@link EncryptionSecrets} is in place before the first read/write (which only happens at request
   * time, after the Spring context — and its config bridge — has fully started).
   */
  private SecretCipher cipher() {
    SecretCipher local = secretCipher;
    if (local == null) {
      local = new SecretCipher(EncryptionSecrets.credentialSecret());
      secretCipher = local;
    }
    return local;
  }

  /** Prepares attributes for storage, encrypting credential values in place. */
  private Map<String, Object> storageAttributes(Map<String, Object> attributes) {
    return transformSensitive(attributes, cipher()::encrypt);
  }

  /** Decrypts credential values after reading attributes back from storage. */
  private Map<String, Object> decryptAttributes(Map<String, Object> attributes) {
    return transformSensitive(attributes, cipher()::decrypt);
  }

  private static Map<String, Object> transformSensitive(
      Map<String, Object> attributes, UnaryOperator<String> transform) {
    if (attributes == null || attributes.isEmpty()) {
      return attributes;
    }
    Map<String, Object> copy = new LinkedHashMap<>(attributes);
    for (String key : SENSITIVE_ATTRIBUTES) {
      if (copy.get(key) instanceof String value && !value.isBlank()) {
        copy.put(key, transform.apply(value));
      }
    }
    return copy;
  }

  public long insert(BlobStoreRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO blob_store
          (name, type, endpoint, region, bucket, prefix, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, record.name());
      ps.setString(2, record.type());
      ps.setString(3, record.endpoint());
      ps.setString(4, record.region());
      ps.setString(5, record.bucket());
      ps.setString(6, record.prefix());
      jsonColumns.bind(ps, 7, storageAttributes(record.attributes()));
    });
  }

  public void update(BlobStoreRecord record) {
    jdbcTemplate.update("""
        UPDATE blob_store
        SET type = ?, endpoint = ?, region = ?, bucket = ?, prefix = ?, attributes_json = ?
        WHERE name = ?
        """,
        record.type(),
        record.endpoint(),
        record.region(),
        record.bucket(),
        record.prefix(),
        jsonColumns.parameter(storageAttributes(record.attributes())),
        record.name());
  }

  public void updateById(BlobStoreRecord record) {
    jdbcTemplate.update("""
        UPDATE blob_store
        SET type = ?, endpoint = ?, region = ?, bucket = ?, prefix = ?, attributes_json = ?
        WHERE id = ?
        """,
        record.type(),
        record.endpoint(),
        record.region(),
        record.bucket(),
        record.prefix(),
        jsonColumns.parameter(storageAttributes(record.attributes())),
        record.id());
  }

  public BlobStoreRecord upsertByName(BlobStoreRecord record) {
    Optional<BlobStoreRecord> existing = findByName(record.name());
    if (existing.isPresent()) {
      update(record);
      return findByName(record.name()).orElseThrow();
    }
    long id = insert(record);
    return findById(id).orElseThrow();
  }

  public Optional<BlobStoreRecord> findById(long id) {
    return jdbcTemplate.query("SELECT * FROM blob_store WHERE id = ?", rowMapper, id)
        .stream()
        .findFirst();
  }

  public Optional<BlobStoreRecord> findByName(String name) {
    return jdbcTemplate.query("SELECT * FROM blob_store WHERE name = ?", rowMapper, name)
        .stream()
        .findFirst();
  }

  public List<BlobStoreRecord> list() {
    return jdbcTemplate.query("SELECT * FROM blob_store ORDER BY name", rowMapper);
  }

  public int deleteById(long id) {
    return jdbcTemplate.update("DELETE FROM blob_store WHERE id = ?", id);
  }
}
