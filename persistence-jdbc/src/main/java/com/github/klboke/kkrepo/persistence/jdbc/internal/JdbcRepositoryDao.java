package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows.nullableLong;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.EnumColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRepositoryDao implements com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao {
  private static final List<String> SENSITIVE_PROXY_ATTRIBUTES = List.of("remotePassword");

  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final RowMapper<RepositoryRecord> rowMapper;
  private volatile SecretCipher secretCipher;

  public JdbcRepositoryDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.rowMapper = (rs, rowNum) -> new RepositoryRecord(
        rs.getLong("id"),
        rs.getString("name"),
        EnumColumns.read(RepositoryFormat.class, rs.getString("format")),
        EnumColumns.read(RepositoryType.class, rs.getString("type")),
        rs.getString("recipe_name"),
        rs.getBoolean("online"),
        nullableLong(rs, "blob_store_id"),
        nullableLong(rs, "routing_rule_id"),
        rs.getString("proxy_remote_url"),
        rs.getString("version_policy"),
        rs.getString("layout_policy"),
        rs.getString("write_policy"),
        rs.getBoolean("strict_content_type_validation"),
        decryptAttributes(jsonColumns.read(rs.getString("attributes_json"))));
  }

  /**
   * Repository proxy credentials are third-party credentials held by the server. Encrypt them at
   * the DAO boundary so direct row reads never expose plaintext in {@code attributes_json}; reads
   * decrypt for runtime use and admin views can choose whether to mask them.
   */
  private SecretCipher cipher() {
    SecretCipher local = secretCipher;
    if (local == null) {
      local = new SecretCipher(EncryptionSecrets.credentialSecret());
      secretCipher = local;
    }
    return local;
  }

  private Map<String, Object> storageAttributes(Map<String, Object> attributes) {
    return transformProxySensitive(attributes, cipher()::encrypt);
  }

  private Map<String, Object> decryptAttributes(Map<String, Object> attributes) {
    return transformProxySensitive(attributes, cipher()::decrypt);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> transformProxySensitive(
      Map<String, Object> attributes, UnaryOperator<String> transform) {
    if (attributes == null || attributes.isEmpty()) {
      return attributes;
    }
    Object rawProxy = attributes.get("proxy");
    if (!(rawProxy instanceof Map<?, ?> rawMap)) {
      return attributes;
    }
    Map<String, Object> proxy = new LinkedHashMap<>((Map<String, Object>) rawMap);
    boolean changed = false;
    for (String key : SENSITIVE_PROXY_ATTRIBUTES) {
      if (proxy.get(key) instanceof String value && !value.isBlank()) {
        proxy.put(key, transform.apply(value));
        changed = true;
      }
    }
    if (!changed) {
      return attributes;
    }
    Map<String, Object> copy = new LinkedHashMap<>(attributes);
    copy.put("proxy", proxy);
    return copy;
  }

  public long insert(RepositoryRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO repository
          (name, format, type, recipe_name, online, blob_store_id, routing_rule_id,
           proxy_remote_url, version_policy, layout_policy, write_policy,
           strict_content_type_validation, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, record.name());
      ps.setString(2, EnumColumns.write(record.format()));
      ps.setString(3, EnumColumns.write(record.type()));
      ps.setString(4, record.recipeName());
      ps.setBoolean(5, record.online());
      ps.setObject(6, record.blobStoreId());
      ps.setObject(7, record.routingRuleId());
      ps.setString(8, record.proxyRemoteUrl());
      ps.setString(9, record.versionPolicy());
      ps.setString(10, record.layoutPolicy());
      ps.setString(11, record.writePolicy());
      ps.setBoolean(12, record.strictContentTypeValidation());
      jsonColumns.bind(ps, 13, storageAttributes(record.attributes()));
    });
  }

  public Optional<RepositoryRecord> findById(long id) {
    return jdbcTemplate.query("SELECT * FROM repository WHERE id = ?", rowMapper, id)
        .stream()
        .findFirst();
  }

  public Optional<RepositoryRecord> findByName(String name) {
    return jdbcTemplate.query("SELECT * FROM repository WHERE name = ?", rowMapper, name)
        .stream()
        .findFirst();
  }

  public List<RepositoryRecord> list() {
    return jdbcTemplate.query("SELECT * FROM repository ORDER BY name", rowMapper);
  }

  public void update(RepositoryRecord record) {
    jdbcTemplate.update("""
        UPDATE repository
        SET online = ?, blob_store_id = ?, routing_rule_id = ?, proxy_remote_url = ?,
            version_policy = ?, layout_policy = ?, write_policy = ?,
            strict_content_type_validation = ?, attributes_json = ?
        WHERE id = ?
        """,
        record.online(),
        record.blobStoreId(),
        record.routingRuleId(),
        record.proxyRemoteUrl(),
        record.versionPolicy(),
        record.layoutPolicy(),
        record.writePolicy(),
        record.strictContentTypeValidation(),
        jsonColumns.parameter(storageAttributes(record.attributes())),
        record.id());
  }

  public RepositoryRecord upsertByName(RepositoryRecord record) {
    Optional<RepositoryRecord> existing = findByName(record.name());
    if (existing.isPresent()) {
      jdbcTemplate.update("""
          UPDATE repository
          SET format = ?, type = ?, recipe_name = ?, online = ?, blob_store_id = ?,
              routing_rule_id = ?, proxy_remote_url = ?, version_policy = ?,
              layout_policy = ?, write_policy = ?, strict_content_type_validation = ?,
              attributes_json = ?
          WHERE name = ?
          """,
          EnumColumns.write(record.format()),
          EnumColumns.write(record.type()),
          record.recipeName(),
          record.online(),
          record.blobStoreId(),
          record.routingRuleId(),
          record.proxyRemoteUrl(),
          record.versionPolicy(),
          record.layoutPolicy(),
          record.writePolicy(),
          record.strictContentTypeValidation(),
          jsonColumns.parameter(storageAttributes(record.attributes())),
          record.name());
      return findByName(record.name()).orElseThrow();
    }
    long id = insert(record);
    return findById(id).orElseThrow();
  }

  public int deleteById(long id) {
    return jdbcTemplate.update("DELETE FROM repository WHERE id = ?", id);
  }

  public boolean existsByName(String name) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM repository WHERE name = ?", Integer.class, name);
    return count != null && count > 0;
  }

  public void addMember(long groupRepositoryId, long memberRepositoryId, int sortOrder) {
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE repository_member SET sort_order = ?
        WHERE repository_id = ? AND member_repository_id = ?
        """,
        new Object[]{sortOrder, groupRepositoryId, memberRepositoryId},
        """
        INSERT INTO repository_member (repository_id, member_repository_id, sort_order)
        VALUES (?, ?, ?)
        """,
        new Object[]{groupRepositoryId, memberRepositoryId, sortOrder});
  }

  public List<RepositoryRecord> listMembers(long groupRepositoryId) {
    return jdbcTemplate.query("""
        SELECT r.*
        FROM repository_member rm
        JOIN repository r ON r.id = rm.member_repository_id
        WHERE rm.repository_id = ?
        ORDER BY rm.sort_order
        """, rowMapper, groupRepositoryId);
  }

  /**
   * Batch-load every group's ordered member names in a single query, keyed by group repository id.
   * Replaces the per-group {@link #listMembers(long)} fan-out when assembling the full catalog.
   */
  public Map<Long, List<String>> listAllGroupMembers() {
    Map<Long, List<String>> membersByGroupId = new LinkedHashMap<>();
    jdbcTemplate.query("""
        SELECT rm.repository_id AS group_id, r.name AS member_name
        FROM repository_member rm
        JOIN repository r ON r.id = rm.member_repository_id
        ORDER BY rm.repository_id, rm.sort_order
        """, rs -> {
          long groupId = rs.getLong("group_id");
          String memberName = rs.getString("member_name");
          membersByGroupId.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(memberName);
        });
    return membersByGroupId;
  }

  public void clearMembers(long groupRepositoryId) {
    jdbcTemplate.update("DELETE FROM repository_member WHERE repository_id = ?", groupRepositoryId);
  }

  public void replaceMembers(long groupRepositoryId, List<Long> memberRepositoryIds) {
    clearMembers(groupRepositoryId);
    int order = 0;
    for (Long memberId : memberRepositoryIds) {
      addMember(groupRepositoryId, memberId, order++);
    }
  }

  public List<RepositoryRecord> listGroupsContaining(long memberRepositoryId) {
    return jdbcTemplate.query("""
        SELECT r.*
        FROM repository_member rm
        JOIN repository r ON r.id = rm.repository_id
        WHERE rm.member_repository_id = ?
        ORDER BY r.name
        """, rowMapper, memberRepositoryId);
  }

  public List<String> findNamesUsingBlobStore(long blobStoreId) {
    return jdbcTemplate.queryForList(
        "SELECT name FROM repository WHERE blob_store_id = ? ORDER BY name",
        String.class,
        blobStoreId);
  }

  public boolean hasComponents(long repositoryId) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM component WHERE repository_id = ?", Integer.class, repositoryId);
    return count != null && count > 0;
  }
}
