package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcInserts;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SecurityPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SecurityPersistenceDialect.PrivilegeInsert;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSecurityDao implements com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao {
  /**
   * Authentication-realm attribute keys holding reusable third-party credentials (OIDC client
   * secret, LDAP bind/manager/system passwords). These are encrypted at rest and decrypted on read
   * so they are never persisted as plaintext in {@code security_realm.attributes_json}. Local user
   * passwords are not here — they are stored as one-way PBKDF2 hashes, which need no key.
   */
  private static final List<String> SENSITIVE_REALM_ATTRIBUTES = List.of(
      "clientSecret", "client_secret",
      "authPassword", "managerPassword", "systemPassword", "bindPassword");

  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;
  private final SecurityPersistenceDialect securityDialect;
  private volatile SecretCipher secretCipher;
  private final RowMapper<SecurityUserRecord> userRowMapper;
  private final RowMapper<SecurityRoleRecord> roleRowMapper;
  private final RowMapper<SecurityPrivilegeRecord> privilegeRowMapper;
  private final RowMapper<SecurityRealmRecord> realmRowMapper;
  private final RowMapper<SecurityRepositoryTargetRecord> repositoryTargetRowMapper;
  private final RowMapper<SecurityAnonymousConfigRecord> anonymousConfigRowMapper;
  private final RowMapper<ApiKeyRecord> apiKeyRowMapper;

  public JdbcSecurityDao(
      JdbcTemplate jdbcTemplate,
      JsonColumns jsonColumns,
      DatabaseDialect databaseDialect) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
    this.securityDialect = databaseDialect.security();
    this.userRowMapper = (rs, rowNum) -> new SecurityUserRecord(
        rs.getLong("id"),
        rs.getString("source"),
        rs.getString("user_id"),
        rs.getString("first_name"),
        rs.getString("last_name"),
        rs.getString("email"),
        rs.getString("password_hash"),
        rs.getString("status"),
        rs.getString("external_id"),
        jsonColumns.read(rs.getString("attributes_json")));
    this.roleRowMapper = (rs, rowNum) -> new SecurityRoleRecord(
        rs.getString("role_id"),
        rs.getString("source"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getBoolean("read_only"),
        jsonColumns.read(rs.getString("attributes_json")));
    this.privilegeRowMapper = (rs, rowNum) -> new SecurityPrivilegeRecord(
        rs.getString("privilege_id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("type"),
        rs.getBoolean("read_only"),
        jsonColumns.read(rs.getString("properties_json")));
    this.realmRowMapper = (rs, rowNum) -> new SecurityRealmRecord(
        rs.getLong("id"),
        rs.getString("realm_id"),
        rs.getString("type"),
        rs.getString("name"),
        rs.getBoolean("enabled"),
        rs.getInt("priority"),
        decryptRealmAttributes(jsonColumns.read(rs.getString("attributes_json"))));
    this.repositoryTargetRowMapper = (rs, rowNum) -> new SecurityRepositoryTargetRecord(
        rs.getLong("id"),
        rs.getString("target_id"),
        rs.getString("name"),
        rs.getString("format"),
        rs.getString("content_expression"),
        jsonColumns.read(rs.getString("path_patterns_json")),
        jsonColumns.read(rs.getString("attributes_json")));
    this.anonymousConfigRowMapper = (rs, rowNum) -> new SecurityAnonymousConfigRecord(
        rs.getBoolean("enabled"),
        rs.getString("user_source"),
        rs.getString("user_id"),
        rs.getString("realm_name"));
    this.apiKeyRowMapper = (rs, rowNum) -> new ApiKeyRecord(
        rs.getLong("id"),
        rs.getString("domain"),
        rs.getString("owner_source"),
        rs.getString("owner_user_id"),
        rs.getString("display_name"),
        rs.getString("status"),
        rs.getString("api_key_hash"),
        rs.getString("token_prefix"),
        jsonColumns.read(rs.getString("scopes_json")),
        rs.getString("encrypted_payload"),
        nullableDateTime(rs, "created_at"),
        nullableDateTime(rs, "updated_at"),
        nullableDateTime(rs, "expires_at"),
        nullableDateTime(rs, "last_used_at"));
  }

  public long insertUser(SecurityUserRecord record) {
    return JdbcInserts.insert(jdbcTemplate, """
        INSERT INTO security_user
          (source, user_id, first_name, last_name, email, password_hash, status,
           external_id, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, ps -> {
      ps.setString(1, record.source());
      ps.setString(2, record.userId());
      ps.setString(3, record.firstName());
      ps.setString(4, record.lastName());
      ps.setString(5, record.email());
      ps.setString(6, record.passwordHash());
      ps.setString(7, record.status());
      ps.setString(8, record.externalId());
      jsonColumns.bind(ps, 9, record.attributes());
    });
  }

  public void updateUser(SecurityUserRecord record) {
    jdbcTemplate.update("""
        UPDATE security_user
        SET first_name = ?, last_name = ?, email = ?, password_hash = ?, status = ?,
            external_id = ?, attributes_json = ?
        WHERE source = ? AND user_id = ?
        """,
        record.firstName(),
        record.lastName(),
        record.email(),
        record.passwordHash(),
        record.status(),
        record.externalId(),
        jsonColumns.parameter(record.attributes()),
        record.source(),
        record.userId());
  }

  public void updatePasswordHash(String source, String userId, String passwordHash) {
    jdbcTemplate.update("""
        UPDATE security_user
        SET password_hash = ?
        WHERE source = ? AND user_id = ?
        """,
        passwordHash,
        source,
        userId);
  }

  public Optional<SecurityUserRecord> findUser(String source, String userId) {
    return jdbcTemplate.query("""
        SELECT * FROM security_user
        WHERE source = ? AND user_id = ?
        """, userRowMapper, source, userId).stream().findFirst();
  }

  public List<SecurityUserRecord> listUsers() {
    return jdbcTemplate.query(
        "SELECT * FROM security_user ORDER BY source, user_id",
        userRowMapper);
  }

  public int deleteUser(String source, String userId) {
    return jdbcTemplate.update(
        "DELETE FROM security_user WHERE source = ? AND user_id = ?",
        source,
        userId);
  }

  public void upsertRole(SecurityRoleRecord record) {
    Object attributes = jsonColumns.parameter(record.attributes());
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE security_role
        SET source = ?, name = ?, description = ?, read_only = ?, attributes_json = ?
        WHERE role_id = ?
        """,
        new Object[]{record.source(), record.name(), record.description(), record.readOnly(),
            attributes, record.roleId()},
        """
        INSERT INTO security_role
          (role_id, source, name, description, read_only, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        new Object[]{record.roleId(), record.source(), record.name(), record.description(),
            record.readOnly(), attributes});
  }

  public Optional<SecurityRoleRecord> findRole(String roleId) {
    return jdbcTemplate.query("SELECT * FROM security_role WHERE role_id = ?", roleRowMapper, roleId)
        .stream()
        .findFirst();
  }

  public List<SecurityRoleRecord> listRoles() {
    return jdbcTemplate.query("SELECT * FROM security_role ORDER BY role_id", roleRowMapper);
  }

  public int deleteRole(String roleId) {
    return jdbcTemplate.update("DELETE FROM security_role WHERE role_id = ?", roleId);
  }

  public void removeRoleReferences(String roleId) {
    jdbcTemplate.update("DELETE FROM security_role_inheritance WHERE child_role_id = ?", roleId);
    jdbcTemplate.update("DELETE FROM security_user_role WHERE role_id = ?", roleId);
  }

  public void upsertPrivilege(SecurityPrivilegeRecord record) {
    Object properties = jsonColumns.parameter(record.properties());
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE security_privilege
        SET name = ?, description = ?, type = ?, read_only = ?, properties_json = ?
        WHERE privilege_id = ?
        """,
        new Object[]{record.name(), record.description(), record.type(), record.readOnly(),
            properties, record.privilegeId()},
        """
        INSERT INTO security_privilege
          (privilege_id, name, description, type, read_only, properties_json)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        new Object[]{record.privilegeId(), record.name(), record.description(), record.type(),
            record.readOnly(), properties});
  }

  public void insertPrivilegeIfAbsent(SecurityPrivilegeRecord record) {
    securityDialect.insertPrivilegeIfAbsent(jdbcTemplate, new PrivilegeInsert(
        record.privilegeId(),
        record.name(),
        record.description(),
        record.type(),
        record.readOnly(),
        jsonColumns.write(record.properties())));
  }

  public Optional<SecurityPrivilegeRecord> findPrivilege(String privilegeId) {
    return jdbcTemplate.query(
            "SELECT * FROM security_privilege WHERE privilege_id = ?",
            privilegeRowMapper,
            privilegeId)
        .stream()
        .findFirst();
  }

  public List<SecurityPrivilegeRecord> listPrivileges() {
    return jdbcTemplate.query("SELECT * FROM security_privilege ORDER BY privilege_id", privilegeRowMapper);
  }

  public List<SecurityPrivilegeRecord> listPrivilegesForRoles(List<String> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", roleIds.stream().map(ignored -> "?").toList());
    return jdbcTemplate.query("""
        SELECT DISTINCT p.*
        FROM security_role_privilege rp
        JOIN security_privilege p ON p.privilege_id = rp.privilege_id
        WHERE rp.role_id IN (%s)
        ORDER BY p.privilege_id
        """.formatted(placeholders), privilegeRowMapper, roleIds.toArray());
  }

  public int deletePrivilege(String privilegeId) {
    return jdbcTemplate.update("DELETE FROM security_privilege WHERE privilege_id = ?", privilegeId);
  }

  public void removePrivilegeReferences(String privilegeId) {
    jdbcTemplate.update("DELETE FROM security_role_privilege WHERE privilege_id = ?", privilegeId);
  }

  public void assignRole(long userNumericId, String roleId) {
    securityDialect.assignRoleIfAbsent(jdbcTemplate, userNumericId, roleId);
  }

  public void replaceUserRoles(long userNumericId, List<String> roleIds) {
    jdbcTemplate.update("DELETE FROM security_user_role WHERE user_id = ?", userNumericId);
    if (roleIds == null) {
      return;
    }
    for (String roleId : roleIds) {
      assignRole(userNumericId, roleId);
    }
  }

  public List<String> listUserRoleIds(long userNumericId) {
    return jdbcTemplate.queryForList("""
        SELECT role_id
        FROM security_user_role
        WHERE user_id = ?
        ORDER BY role_id
        """, String.class, userNumericId);
  }

  public List<String> listUserRoleIds(String source, String userId) {
    return jdbcTemplate.queryForList("""
        SELECT ur.role_id
        FROM security_user u
        JOIN security_user_role ur ON ur.user_id = u.id
        WHERE u.source = ? AND u.user_id = ?
        ORDER BY ur.role_id
        """, String.class, source, userId);
  }

  public void grantPrivilege(String roleId, String privilegeId) {
    securityDialect.grantPrivilegeIfAbsent(jdbcTemplate, roleId, privilegeId);
  }

  public void replaceRolePrivileges(String roleId, List<String> privilegeIds) {
    jdbcTemplate.update("DELETE FROM security_role_privilege WHERE role_id = ?", roleId);
    if (privilegeIds == null) {
      return;
    }
    for (String privilegeId : privilegeIds) {
      grantPrivilege(roleId, privilegeId);
    }
  }

  public List<String> listRolePrivilegeIds(String roleId) {
    return jdbcTemplate.queryForList("""
        SELECT privilege_id
        FROM security_role_privilege
        WHERE role_id = ?
        ORDER BY privilege_id
        """, String.class, roleId);
  }

  public void inheritRole(String roleId, String childRoleId) {
    securityDialect.inheritRoleIfAbsent(jdbcTemplate, roleId, childRoleId);
  }

  public void replaceRoleInheritance(String roleId, List<String> childRoleIds) {
    jdbcTemplate.update("DELETE FROM security_role_inheritance WHERE role_id = ?", roleId);
    if (childRoleIds == null) {
      return;
    }
    for (String childRoleId : childRoleIds) {
      inheritRole(roleId, childRoleId);
    }
  }

  public List<String> listRoleChildIds(String roleId) {
    return jdbcTemplate.queryForList("""
        SELECT child_role_id
        FROM security_role_inheritance
        WHERE role_id = ?
        ORDER BY child_role_id
        """, String.class, roleId);
  }

  public List<SecurityRealmRecord> listRealms() {
    return jdbcTemplate.query(
        "SELECT * FROM security_realm ORDER BY enabled DESC, priority, realm_id",
        realmRowMapper);
  }

  public Optional<SecurityRealmRecord> findRealm(String realmId) {
    return jdbcTemplate.query(
            "SELECT * FROM security_realm WHERE realm_id = ?",
            realmRowMapper,
            realmId)
        .stream()
        .findFirst();
  }

  public void upsertRealm(SecurityRealmRecord record) {
    Object attributes = jsonColumns.parameter(encryptRealmAttributes(record.attributes()));
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE security_realm
        SET type = ?, name = ?, enabled = ?, priority = ?, attributes_json = ?
        WHERE realm_id = ?
        """,
        new Object[]{record.type(), record.name(), record.enabled(), record.priority(), attributes,
            record.realmId()},
        """
        INSERT INTO security_realm
          (realm_id, type, name, enabled, priority, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        new Object[]{record.realmId(), record.type(), record.name(), record.enabled(),
            record.priority(), attributes});
  }

  /**
   * Resolves the credential cipher lazily so the secret bound from the application config file via
   * {@link EncryptionSecrets} is in place before the first realm read/write (which only happens at
   * request time, after the Spring context — and its config bridge — has fully started).
   */
  private SecretCipher cipher() {
    SecretCipher local = secretCipher;
    if (local == null) {
      local = new SecretCipher(EncryptionSecrets.credentialSecret());
      secretCipher = local;
    }
    return local;
  }

  private Map<String, Object> encryptRealmAttributes(Map<String, Object> attributes) {
    return transformSensitive(attributes, cipher()::encrypt);
  }

  private Map<String, Object> decryptRealmAttributes(Map<String, Object> attributes) {
    return transformSensitive(attributes, cipher()::decrypt);
  }

  private static Map<String, Object> transformSensitive(
      Map<String, Object> attributes, UnaryOperator<String> transform) {
    if (attributes == null || attributes.isEmpty()) {
      return attributes;
    }
    Map<String, Object> copy = new LinkedHashMap<>(attributes);
    for (String key : SENSITIVE_REALM_ATTRIBUTES) {
      if (copy.get(key) instanceof String value && !value.isBlank()) {
        copy.put(key, transform.apply(value));
      }
    }
    return copy;
  }

  public void updateRealmConfig(List<String> activeRealmIds) {
    Object realms = jsonColumns.parameter(
        Map.of("realms", activeRealmIds == null ? List.of() : activeRealmIds));
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        "UPDATE security_realm_config SET realms_json = ? WHERE id = 1",
        new Object[]{realms},
        """
        INSERT INTO security_realm_config (id, realms_json)
        VALUES (1, ?)
        """,
        new Object[]{realms});
  }

  public Optional<SecurityAnonymousConfigRecord> findAnonymousConfig() {
    return jdbcTemplate.query(
            "SELECT * FROM security_anonymous_config WHERE id = 1",
            anonymousConfigRowMapper)
        .stream()
        .findFirst();
  }

  public void upsertAnonymousConfig(SecurityAnonymousConfigRecord record) {
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE security_anonymous_config
        SET enabled = ?, user_source = ?, user_id = ?, realm_name = ?
        WHERE id = 1
        """,
        new Object[]{record.enabled(), record.userSource(), record.userId(), record.realmName()},
        """
        INSERT INTO security_anonymous_config
          (id, enabled, user_source, user_id, realm_name)
        VALUES (1, ?, ?, ?, ?)
        """,
        new Object[]{record.enabled(), record.userSource(), record.userId(), record.realmName()});
  }

  public List<SecurityRepositoryTargetRecord> listRepositoryTargets() {
    return jdbcTemplate.query(
        "SELECT * FROM security_repository_target ORDER BY target_id",
        repositoryTargetRowMapper);
  }

  public Optional<SecurityRepositoryTargetRecord> findRepositoryTarget(String targetId) {
    return jdbcTemplate.query("""
        SELECT * FROM security_repository_target
        WHERE target_id = ?
        """, repositoryTargetRowMapper, targetId).stream().findFirst();
  }

  public void upsertRepositoryTarget(SecurityRepositoryTargetRecord record) {
    Object pathPatterns = jsonColumns.parameter(record.pathPatterns());
    Object attributes = jsonColumns.parameter(record.attributes());
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE security_repository_target
        SET name = ?, format = ?, content_expression = ?, path_patterns_json = ?,
            attributes_json = ?
        WHERE target_id = ?
        """,
        new Object[]{record.name(), record.format(), record.contentExpression(), pathPatterns,
            attributes, record.targetId()},
        """
        INSERT INTO security_repository_target
          (target_id, name, format, content_expression, path_patterns_json, attributes_json)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        new Object[]{record.targetId(), record.name(), record.format(), record.contentExpression(),
            pathPatterns, attributes});
  }

  public int deleteRepositoryTarget(String targetId) {
    return jdbcTemplate.update("DELETE FROM security_repository_target WHERE target_id = ?", targetId);
  }

  public List<ApiKeyRecord> listApiKeys() {
    return jdbcTemplate.query("SELECT * FROM api_key ORDER BY domain, owner_source, owner_user_id", apiKeyRowMapper);
  }

  public List<ApiKeyRecord> listApiKeysForOwner(String ownerSource, String ownerUserId) {
    return jdbcTemplate.query("""
        SELECT * FROM api_key
        WHERE owner_source = ? AND owner_user_id = ?
        ORDER BY domain, id
        """, apiKeyRowMapper, ownerSource, ownerUserId);
  }

  public Optional<ApiKeyRecord> findApiKey(long id) {
    return jdbcTemplate.query("SELECT * FROM api_key WHERE id = ?", apiKeyRowMapper, id)
        .stream()
        .findFirst();
  }

  public Optional<ApiKeyRecord> findApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
    return jdbcTemplate.query("""
        SELECT * FROM api_key
        WHERE id = ? AND owner_source = ? AND owner_user_id = ?
        """, apiKeyRowMapper, id, ownerSource, ownerUserId).stream().findFirst();
  }

  public Optional<ApiKeyRecord> findApiKey(String domain, String ownerSource, String ownerUserId) {
    return jdbcTemplate.query("""
        SELECT * FROM api_key
        WHERE domain = ? AND owner_source = ? AND owner_user_id = ?
        """, apiKeyRowMapper, domain, ownerSource, ownerUserId).stream().findFirst();
  }

  public Optional<ApiKeyRecord> findApiKeyByHash(String apiKeyHash) {
    return jdbcTemplate.query(
            "SELECT * FROM api_key WHERE api_key_hash = ?",
            apiKeyRowMapper,
            apiKeyHash)
        .stream()
        .findFirst();
  }

  public Optional<ApiKeyRecord> findApiKeyByDomainAndHash(String domain, String apiKeyHash) {
    return jdbcTemplate.query("""
            SELECT * FROM api_key
            WHERE domain = ? AND api_key_hash = ?
            """,
            apiKeyRowMapper,
            domain,
            apiKeyHash)
        .stream()
        .findFirst();
  }

  public void upsertApiKey(ApiKeyRecord record) {
    Object scopes = jsonColumns.parameter(record.scopes());
    Object[] updateArguments = {
        record.displayName(), record.status(), record.apiKeyHash(), record.tokenPrefix(), scopes,
        record.encryptedPayload(), record.expiresAt(), record.lastUsedAt(), record.domain(),
        record.ownerSource(), record.ownerUserId()
    };
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE api_key
        SET display_name = ?, status = ?, api_key_hash = ?, token_prefix = ?, scopes_json = ?,
            encrypted_payload = ?, expires_at = ?, last_used_at = ?
        WHERE domain = ? AND owner_source = ? AND owner_user_id = ?
        """,
        updateArguments,
        """
        INSERT INTO api_key
          (domain, owner_source, owner_user_id, display_name, status, api_key_hash,
           token_prefix, scopes_json, encrypted_payload, expires_at, last_used_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new Object[]{record.domain(), record.ownerSource(), record.ownerUserId(),
            record.displayName(), record.status(), record.apiKeyHash(), record.tokenPrefix(), scopes,
            record.encryptedPayload(), record.expiresAt(), record.lastUsedAt()});
  }

  public int deleteApiKey(long id) {
    return jdbcTemplate.update("DELETE FROM api_key WHERE id = ?", id);
  }

  public int deleteApiKeysForOwner(String ownerSource, String ownerUserId) {
    return jdbcTemplate.update("""
        DELETE FROM api_key
        WHERE owner_source = ? AND owner_user_id = ?
        """, ownerSource, ownerUserId);
  }

  public void markApiKeyUsed(long id, LocalDateTime usedAt) {
    jdbcTemplate.update("UPDATE api_key SET last_used_at = ? WHERE id = ?", usedAt, id);
  }

  private static LocalDateTime nullableDateTime(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }
}
