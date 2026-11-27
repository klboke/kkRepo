package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.core.security.SecretCipher;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class SecurityDaoMySqlIntegrationTest extends MySqlIntegrationTestSupport {
  @Test
  void anonymousAccountEditsPreserveImportedRealmAndSource() {
    SecurityDao dao = new JdbcSecurityDao(jdbc(), jsonColumns(), dialect());
    inTransaction(() -> {
      var previous = dao.findAnonymousConfig();
      jdbc().update("DELETE FROM security_anonymous_config WHERE id = 1");
      var defaults = new SecurityAnonymousConfigRecord(true, "Local", "guest", "NexusAuthorizingRealm");
      dao.upsertAnonymousAccount(defaults);
      assertEquals(defaults, dao.findAnonymousConfig().orElseThrow());

      dao.upsertAnonymousConfig(new SecurityAnonymousConfigRecord(true, "LDAP", "guest", "ImportedRealm"));
      dao.upsertAnonymousAccount(new SecurityAnonymousConfigRecord(false, "Local", "reader", "NexusAuthorizingRealm"));
      assertEquals(new SecurityAnonymousConfigRecord(false, "LDAP", "reader", "ImportedRealm"),
          dao.findAnonymousConfig().orElseThrow());

      jdbc().update("DELETE FROM security_anonymous_config WHERE id = 1");
      previous.ifPresent(dao::upsertAnonymousConfig);
    });
  }

  @Test
  void userRoleAndPrivilegeRelationshipsRespectUniqueKeysAndForeignKeys() {
    SecurityDao dao = new JdbcSecurityDao(jdbc(), jsonColumns(), dialect());
    long userId = dao.insertUser(user("alice"));
    assertThrows(DuplicateKeyException.class, () -> dao.insertUser(user("alice")));

    dao.upsertRole(role("developers", "Developers"));
    dao.upsertRole(role("readers", "Readers"));
    dao.upsertPrivilege(privilege("nx-repository-view-*-*-read"));
    dao.assignRole(userId, "developers");
    dao.assignRole(userId, "developers");
    dao.grantPrivilege("developers", "nx-repository-view-*-*-read");
    dao.grantPrivilege("developers", "nx-repository-view-*-*-read");
    dao.inheritRole("developers", "readers");

    assertEquals(List.of("developers"), dao.listUserRoleIds("Local", "alice"));
    assertEquals(List.of("nx-repository-view-*-*-read"), dao.listRolePrivilegeIds("developers"));
    assertEquals(List.of("readers"), dao.listRoleChildIds("developers"));
    assertEquals(1, dao.listPrivilegesForRoles(List.of("developers")).size());

    dao.replaceUserRoles(userId, List.of("readers"));
    dao.replaceRolePrivileges("developers", List.of());
    dao.replaceRoleInheritance("developers", null);

    assertEquals(List.of("readers"), dao.listUserRoleIds(userId));
    assertTrue(dao.listRolePrivilegeIds("developers").isEmpty());
    assertTrue(dao.listRoleChildIds("developers").isEmpty());
    dao.assignRole(userId, "missing-role");
    assertEquals(List.of("readers"), dao.listUserRoleIds(userId));
  }

  @Test
  void realmSecretsAreEncryptedAtRestAndDecryptedOnRead() {
    SecurityDao dao = new JdbcSecurityDao(jdbc(), jsonColumns(), dialect());
    dao.upsertRealm(new SecurityRealmRecord(
        null,
        "oidc-test",
        "OIDC",
        "Test OIDC",
        true,
        10,
        Map.of("issuer", "https://issuer.example", "clientSecret", "plain-secret")));

    String stored = jdbc().queryForObject("""
        SELECT JSON_UNQUOTE(JSON_EXTRACT(attributes_json, '$.clientSecret'))
        FROM security_realm
        WHERE realm_id = 'oidc-test'
        """, String.class);
    SecurityRealmRecord loaded = dao.findRealm("oidc-test").orElseThrow();

    assertNotEquals("plain-secret", stored);
    assertTrue(SecretCipher.isEncrypted(stored));
    assertEquals("plain-secret", loaded.attributes().get("clientSecret"));
    assertEquals("https://issuer.example", loaded.attributes().get("issuer"));
  }

  @Test
  void apiKeyUpsertAndOwnerQueriesRoundTripJsonAndTimestamps() {
    SecurityDao dao = new JdbcSecurityDao(jdbc(), jsonColumns(), dialect());
    LocalDateTime expiry = LocalDateTime.of(2027, 1, 1, 0, 0);
    dao.upsertApiKey(apiKey("hash-one", "ACTIVE", expiry));
    ApiKeyRecord created = dao.findApiKey("npm", "Local", "alice").orElseThrow();

    assertEquals(Map.of("repositories", List.of("npm-hosted")), created.scopes());
    assertTrue(dao.findApiKeyByDomainAndHash("npm", "hash-one").isPresent());
    assertFalse(dao.findApiKeyForOwner(created.id(), "Local", "bob").isPresent());

    dao.upsertApiKey(apiKey("hash-two", "REVOKED", expiry.plusDays(1)));
    ApiKeyRecord updated = dao.findApiKey(created.id()).orElseThrow();

    assertEquals("hash-two", updated.apiKeyHash());
    assertEquals("REVOKED", updated.status());
    assertEquals(1, dao.listApiKeysForOwner("Local", "alice").size());
    LocalDateTime usedAt = LocalDateTime.of(2026, 7, 13, 12, 30);
    dao.markApiKeyUsed(created.id(), usedAt);
    assertEquals(usedAt, dao.findApiKey(created.id()).orElseThrow().lastUsedAt());
    assertEquals(1, dao.deleteApiKeysForOwner("Local", "alice"));
    assertTrue(dao.listApiKeys().isEmpty());
  }

  @Test
  void apiKeyDomainLookupFollowsTheCaseAndAccentInsensitiveCollation() {
    // Services must authorize against the stored domain: other spellings resolve the same row here.
    SecurityDao dao = new JdbcSecurityDao(jdbc(), jsonColumns(), dialect());
    ApiKeyRecord npm = apiKey("user-token-hash", "ACTIVE", null);
    dao.upsertApiKey(new ApiKeyRecord(null, "UserToken", npm.ownerSource(), "carol",
        npm.displayName(), npm.status(), npm.apiKeyHash(), npm.tokenPrefix(), npm.scopes(),
        npm.encryptedPayload(), null, null, null, null));

    for (String variant : List.of("usertoken", "UsérToken", "\uFF35\uFF53\uFF45\uFF52\uFF34\uFF4F\uFF4B\uFF45\uFF4E")) {
      assertEquals("UserToken", dao.findApiKey(variant, "Local", "carol").orElseThrow().domain(), variant);
    }
    // The class shares one database; other tests count every API key.
    dao.deleteApiKeysForOwner("Local", "carol");
  }

  private static SecurityUserRecord user(String userId) {
    return new SecurityUserRecord(
        null,
        "Local",
        userId,
        "Alice",
        "Example",
        userId + "@example.com",
        "hash",
        "active",
        null,
        Map.of("department", "infra"));
  }

  private static SecurityRoleRecord role(String roleId, String name) {
    return new SecurityRoleRecord(roleId, "Local", name, name, false, Map.of());
  }

  private static SecurityPrivilegeRecord privilege(String privilegeId) {
    return new SecurityPrivilegeRecord(
        privilegeId,
        privilegeId,
        "read repositories",
        "repository-view",
        false,
        Map.of("actions", List.of("read")));
  }

  private static ApiKeyRecord apiKey(String hash, String status, LocalDateTime expiry) {
    return new ApiKeyRecord(
        null,
        "npm",
        "Local",
        "alice",
        "npm token",
        status,
        hash,
        "npm_",
        Map.of("repositories", List.of("npm-hosted")),
        "encrypted-payload",
        null,
        null,
        expiry,
        null);
  }
}
