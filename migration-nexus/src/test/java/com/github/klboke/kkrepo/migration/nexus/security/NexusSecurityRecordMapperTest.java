package com.github.klboke.kkrepo.migration.nexus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusApiKey;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusAnonymousConfig;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusContentSelector;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusPrivilege;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusRole;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUser;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUserRoleMapping;
import com.github.klboke.kkrepo.core.security.ApiKeyTokenPayloads;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.SecurityUserRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NexusSecurityRecordMapperTest {
  private final NexusSecurityRecordMapper mapper = new NexusSecurityRecordMapper();

  @Test
  void mapsUserWithoutRewritingNexusPasswordHashOrStatus() {
    SecurityUserRecord user = mapper.mapUser(new NexusUser(
        "admin",
        null,
        "Admin",
        "User",
        "admin@example.com",
        "$shiro1$SHA-512$1024$salt$hash",
        "changepassword",
        Map.of("version", 1)));

    assertEquals("Local", user.source());
    assertEquals("admin", user.userId());
    assertEquals("$shiro1$SHA-512$1024$salt$hash", user.passwordHash());
    assertEquals("changepassword", user.status());
    assertEquals(1, user.attributes().get("version"));
  }

  @Test
  void normalizesNexusSourceAliasesToLocalSource() {
    SecurityUserRecord user = mapper.mapUser(new NexusUser(
        "admin",
        "Nexus",
        "Admin",
        "User",
        "admin@example.com",
        "$shiro1$hash",
        "active",
        Map.of()));
    var role = mapper.mapRole(new NexusRole(
        "nx-admin",
        "LDAP",
        "Admin",
        "Admin role",
        true,
        List.of("nx-all"),
        List.of(),
        Map.of()));
    var mapping = mapper.mapUserRoleMapping(new NexusUserRoleMapping(
        "admin",
        "NexusAuthorizingRealm",
        List.of("nx-admin")));
    ApiKeyRecord apiKey = mapper.mapApiKey(new NexusApiKey(
        "NpmToken",
        "local",
        "admin",
        "raw-secret",
        "npm token",
        "ACTIVE"));
    SecurityAnonymousConfigRecord anonymous = mapper.mapAnonymousConfig(new NexusAnonymousConfig(
        true,
        "Nexus",
        "anonymous",
        "NexusAuthorizingRealm"));

    assertEquals("Local", user.source());
    assertEquals("Local", role.record().source());
    assertEquals("Local", mapping.source());
    assertEquals("Local", apiKey.ownerSource());
    assertEquals("Local", anonymous.userSource());
  }

  @Test
  void mapsRoleAndUserRoleEdgesSeparately() {
    var role = mapper.mapRole(new NexusRole(
        "nx-deploy",
        "default",
        "Deployment",
        "Deploy role",
        false,
        List.of("nx-repository-view-maven2-releases-add", "nx-repository-view-maven2-releases-add"),
        List.of("nx-anonymous"),
        Map.of()));
    var mapping = mapper.mapUserRoleMapping(new NexusUserRoleMapping(
        "admin",
        "default",
        List.of("nx-admin")));

    assertEquals("nx-deploy", role.record().roleId());
    assertEquals(List.of("nx-repository-view-maven2-releases-add"), role.privileges());
    assertEquals(List.of("nx-anonymous"), role.childRoles());
    assertEquals(List.of("nx-admin"), mapping.roles());
  }

  @Test
  void mapsPrivilegePropertiesVerbatim() {
    SecurityPrivilegeRecord privilege = mapper.mapPrivilege(new NexusPrivilege(
        "nx-repository-view-maven2-releases-read",
        "read releases",
        "Read release artifacts",
        "repository-view",
        true,
        Map.of(
            "format", "maven2",
            "repository", "releases",
            "actions", "read,browse")));

    assertEquals("repository-view", privilege.type());
    assertEquals("maven2", privilege.properties().get("format"));
    assertEquals("read,browse", privilege.properties().get("actions"));
    assertEquals(true, privilege.readOnly());
  }

  @Test
  void mapsContentSelectorPrivilegeAliasesToRuntimeCanonicalProperties() {
    SecurityPrivilegeRecord privilege = mapper.mapPrivilege(new NexusPrivilege(
        "nx-repository-content-selector-team-read",
        "team selector read",
        "Read team selector",
        "repository-content-selector",
        false,
        Map.of(
            "selectorName", "team-selector",
            "repositoryId", "releases",
            "repositoryFormat", "maven2",
            "action", "read")));

    assertEquals("team-selector", privilege.properties().get("contentSelector"));
    assertEquals("releases", privilege.properties().get("repository"));
    assertEquals("maven2", privilege.properties().get("format"));
    assertEquals("read", privilege.properties().get("actions"));
  }

  @Test
  void mapsApiKeyToDomainScopedRawHash() {
    ApiKeyRecord apiKey = mapper.mapApiKey(new NexusApiKey(
        "NpmToken",
        "default",
        "admin",
        "raw-secret",
        "npm token",
        "ACTIVE"));

    assertEquals("NpmToken", apiKey.domain());
    assertEquals("admin", apiKey.ownerUserId());
    assertEquals("96820dfd08df73515beece5adfea687bed8e38234e63ff1b8251d5d20af172e4", apiKey.apiKeyHash());
    assertEquals("NpmToken.raw", apiKey.tokenPrefix());
    assertEquals("nexus-orient", apiKey.scopes().get("source"));
    assertEquals(List.of(), apiKey.scopes().get("values"));
    assertFalse(apiKey.encryptedPayload().contains("raw-secret"));
    assertEquals("raw-secret", ApiKeyTokenPayloads.decryptRawToken(apiKey.encryptedPayload()).orElseThrow());
  }

  @Test
  void mapsAnonymousConfigToNexusDefaultUserAndRealmSource() {
    SecurityAnonymousConfigRecord config = mapper.mapAnonymousConfig(new NexusAnonymousConfig(
        true,
        null,
        null,
        "NexusAuthorizingRealm"));

    assertEquals(true, config.enabled());
    assertEquals("Local", config.userSource());
    assertEquals("anonymous", config.userId());
    assertEquals("NexusAuthorizingRealm", config.realmName());
  }

  @Test
  void preservesDisabledAnonymousConfig() {
    SecurityAnonymousConfigRecord config = mapper.mapAnonymousConfig(new NexusAnonymousConfig(
        false,
        null,
        "anonymous",
        "NexusAuthorizingRealm"));

    assertEquals(false, config.enabled());
  }

  @Test
  void mapsContentSelectorAsRepositoryTargetCompatibleRecord() {
    SecurityRepositoryTargetRecord selector = mapper.mapContentSelector(new NexusContentSelector(
        "team-a",
        "csel",
        "Team A content",
        "path =~ \"^/team-a/.*\"",
        "*",
        Map.of()));

    assertEquals("team-a", selector.targetId());
    assertEquals("team-a", selector.name());
    assertEquals("*", selector.format());
    assertEquals("path =~ \"^/team-a/.*\"", selector.contentExpression());
    assertEquals("nexus-content-selector", selector.attributes().get("source"));
  }

  @Test
  void mapsOnlySupportedRealmOrderWithPriority() {
    List<SecurityRealmRecord> realms = mapper.mapRealmOrder(List.of(
        "LdapRealm",
        "NexusAuthenticatingRealm",
        "NexusAuthorizingRealm",
        "SomeUnsupportedRealm",
        "OidcRealm"));

    assertEquals(3, realms.size());
    assertEquals("ldap", realms.get(0).realmId());
    assertEquals(0, realms.get(0).priority());
    assertEquals("local", realms.get(1).realmId());
    assertEquals(10, realms.get(1).priority());
    assertEquals("oidc", realms.get(2).realmId());
    assertEquals(20, realms.get(2).priority());
  }
}
