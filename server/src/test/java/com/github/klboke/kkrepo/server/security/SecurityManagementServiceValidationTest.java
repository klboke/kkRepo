package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.security.ApiKeyTokenPayloads;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.persistence.mysql.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AnonymousSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.LdapSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.PrivilegeCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityManagementServiceValidationTest {

  @Test
  void saveUserRejectsMissingRoleReference() {
    FakeSecurityDao dao = new FakeSecurityDao();
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.saveUser(new UserCommand(
            "Local",
            "alice",
            "Alice",
            null,
            "alice@example.com",
            "secret",
            null,
            "ACTIVE",
            null,
            List.of("missing-role"),
            Map.of())));

    assertEquals("Role not found: missing-role", error.getMessage());
  }

  @Test
  void saveUserPersistsValidRoleReferences() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("nx-reader", false));
    SecurityManagementService service = new SecurityManagementService(dao);

    var saved = service.saveUser(new UserCommand(
        "Local",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "secret",
        null,
        "ACTIVE",
        null,
        List.of("nx-reader"),
        Map.of()));

    assertEquals(List.of("nx-reader"), saved.roles());
  }

  @Test
  void adminBootstrapStatusRequiresSetupWhenNoAdministrativeUserExists() {
    FakeSecurityDao dao = new FakeSecurityDao();
    seedAdminRole(dao);
    SecurityManagementService service = new SecurityManagementService(dao);

    var status = service.adminBootstrapStatus();

    assertEquals(true, status.required());
    assertEquals("Local", status.source());
    assertEquals("admin", status.userId());
    assertEquals("nx-admin", status.roleId());
    assertEquals(8, status.minPasswordLength());
    assertEquals(false, status.anonymousAccessEnabled());
  }

  @Test
  void initializeAdminCreatesLocalAdminWithAdministratorRole() {
    FakeSecurityDao dao = new FakeSecurityDao();
    seedAdminRole(dao);
    SecurityManagementService service = new SecurityManagementService(dao);

    var saved = service.initializeAdmin(new AdminBootstrapCommand("Admin1234", "Admin1234", null));

    assertEquals("Local", saved.source());
    assertEquals("admin", saved.userId());
    assertEquals(List.of("nx-admin"), saved.roles());
    SecurityUserRecord stored = dao.findUser("Local", "admin").orElseThrow();
    assertNotEquals("Admin1234", stored.passwordHash());
    assertTrue(SecurityHashing.verifyPassword(stored.passwordHash(), "Admin1234"));
    assertEquals(false, service.adminBootstrapStatus().required());
    assertEquals(false, service.anonymousSettings().enabled());
  }

  @Test
  void initializeAdminCanEnableAnonymousAccess() {
    FakeSecurityDao dao = new FakeSecurityDao();
    seedAdminRole(dao);
    SecurityManagementService service = new SecurityManagementService(dao);

    service.initializeAdmin(new AdminBootstrapCommand("Admin1234", "Admin1234", true));

    assertEquals(true, service.anonymousSettings().enabled());
    assertEquals(true, service.adminBootstrapStatus().anonymousAccessEnabled());
  }

  @Test
  void initializeAdminRejectsRepeatInitialization() {
    FakeSecurityDao dao = new FakeSecurityDao();
    seedAdminRole(dao);
    SecurityManagementService service = new SecurityManagementService(dao);
    service.initializeAdmin(new AdminBootstrapCommand("Admin1234", "Admin1234", false));

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.initializeAdmin(new AdminBootstrapCommand("Changed1234", "Changed1234", true)));

    assertEquals("Administrator is already initialized.", error.getMessage());
  }

  @Test
  void initializeAdminRejectsPasswordMismatch() {
    FakeSecurityDao dao = new FakeSecurityDao();
    seedAdminRole(dao);
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.initializeAdmin(new AdminBootstrapCommand("Admin1234", "Admin5678", true)));

    assertEquals("passwordConfirm does not match password", error.getMessage());
  }

  @Test
    void sourceAliasesNormalizeBeforePersistenceAndLookup() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("nx-reader", false));
    SecurityManagementService service = new SecurityManagementService(dao);

    var user = service.saveUser(new UserCommand(
        "Nexus",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "secret",
        null,
        "ACTIVE",
        null,
        List.of("nx-reader"),
        Map.of()));
    var role = service.saveRole(new RoleCommand(
        "team",
        "LDAP",
        "Team",
        null,
        false,
        List.of(),
        List.of(),
        Map.of()));
    var apiKey = service.createApiKey(new ApiKeyCommand(
        "NpmToken",
        "local",
        "alice",
        "npm",
        "ACTIVE",
        List.of(),
        null,
        null,
        null));
    var anonymous = service.saveAnonymousSettings(new AnonymousSettingsCommand(
        true,
        "NexusAuthorizingRealm",
        "anonymous",
        "NexusAuthorizingRealm"));

    assertEquals("Local", user.source());
    assertEquals("Local", service.findUser("Nexus", "alice").orElseThrow().source());
    assertEquals("Local", role.source());
    assertEquals("Local", apiKey.apiKey().ownerSource());
    assertEquals(1, service.listApiKeysForOwner("Nexus", "alice").size());
      assertEquals("Local", anonymous.userSource());
    }

    @Test
    void saveLdapSettingsSynchronizesNexusFieldsAndRuntimeAliases() {
      FakeSecurityDao dao = new FakeSecurityDao();
      SecurityManagementService service = new SecurityManagementService(dao);

      var saved = service.saveLdapSettings(new LdapSettingsCommand(
          true,
          5,
          "LDAP",
          "Corporate LDAP",
          null,
          "ldaps",
          "ldap.example.com",
          636,
          true,
          "dc=example,dc=com",
          "simple",
          null,
          "cn=bind,dc=example,dc=com",
          "secret",
          30,
          null,
          null,
          "ou=users",
          true,
          "inetOrgPerson",
          "(enabled=TRUE)",
          "uid",
          "cn",
          "memberOf",
          "mail",
          "userPassword",
          true,
          "static",
          "ou=groups",
          true,
          "cn",
          "member",
          "${dn}",
          "groupOfNames",
          Map.of("custom", "kept")));

      Map<String, Object> attributes = dao.findRealm("ldap").orElseThrow().attributes();
      assertEquals(true, saved.enabled());
      assertEquals(5, saved.priority());
      assertEquals("Corporate LDAP", saved.name());
      assertEquals("********", saved.authPassword());
      assertEquals(null, saved.attributes().get("authPassword"));
      assertEquals(null, saved.attributes().get("bindPassword"));
      assertEquals("ldaps://ldap.example.com:636/dc=example,dc=com", attributes.get("url"));
      assertEquals("cn=bind,dc=example,dc=com", attributes.get("managerDn"));
      assertEquals("cn=bind,dc=example,dc=com", attributes.get("bindDn"));
      assertEquals("secret", attributes.get("bindPassword"));
      assertEquals("ou=users", attributes.get("userSearchBase"));
      assertEquals("uid", attributes.get("externalIdAttribute"));
      assertEquals("cn", attributes.get("firstNameAttribute"));
      assertEquals("mail", attributes.get("emailAttribute"));
      assertEquals("ou=groups", attributes.get("groupSearchBase"));
      assertEquals("(&(objectClass=inetOrgPerson)(uid={0})(enabled=TRUE))", attributes.get("userSearchFilter"));
      assertEquals("(&(objectClass=groupOfNames)(member={1}))", attributes.get("groupSearchFilter"));
      assertEquals(30000, attributes.get("timeoutMs"));
      assertEquals("kept", attributes.get("custom"));
    }

    @Test
    void saveLdapSettingsKeepsExplicitUrlWhenDefaultEndpointFieldsArePosted() {
      FakeSecurityDao dao = new FakeSecurityDao();
      SecurityManagementService service = new SecurityManagementService(dao);

      var saved = service.saveLdapSettings(new LdapSettingsCommand(
          true,
          10,
          "LDAP",
          "LDAP",
          "ldap://directory.example.org:389/dc=example,dc=org",
          "ldap",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          Map.of()));

      Map<String, Object> attributes = dao.findRealm("ldap").orElseThrow().attributes();
      assertEquals("ldap://directory.example.org:389/dc=example,dc=org", attributes.get("url"));
      assertEquals("directory.example.org", saved.host());
      assertEquals(389, saved.port());
      assertEquals("dc=example,dc=org", saved.searchBase());
    }

    @Test
    void enabledLdapSettingsRequireUrlOrEndpointFields() {
      FakeSecurityDao dao = new FakeSecurityDao();
      SecurityManagementService service = new SecurityManagementService(dao);

      SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
          service.saveLdapSettings(new LdapSettingsCommand(
              true,
              10,
              "LDAP",
              "LDAP",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              Map.of())));

      assertEquals("LDAP URL is required when LDAP realm is enabled", error.getMessage());
    }

    @Test
    void saveRoleRejectsMissingPrivilegeReference() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("deploy", false));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.saveRole(new RoleCommand(
            "deploy",
            "Local",
            "Deploy",
            null,
            false,
            List.of("missing-privilege"),
            List.of(),
            Map.of())));

    assertEquals("Privilege not found: missing-privilege", error.getMessage());
  }

  @Test
  void saveRoleRejectsMissingChildRoleReference() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("parent", false));
    dao.privilege(privilege("nx-repository-view-maven2-releases-read", false));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.saveRole(new RoleCommand(
            "parent",
            "Local",
            "Parent",
            null,
            false,
            List.of("nx-repository-view-maven2-releases-read"),
            List.of("missing-role"),
            Map.of())));

    assertEquals("Role not found: missing-role", error.getMessage());
  }

  @Test
  void saveRoleRejectsDirectSelfContainment() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("parent", false));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.saveRole(new RoleCommand(
            "parent",
            "Local",
            "Parent",
            null,
            false,
            List.of(),
            List.of("parent"),
            Map.of())));

    assertEquals("Role cannot contain itself: parent", error.getMessage());
  }

  @Test
  void saveRoleRejectsIndirectSelfContainment() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("parent", false));
    dao.role(role("child", false));
    dao.role(role("grandchild", false));
    dao.children("child", "grandchild");
    dao.children("grandchild", "parent");
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.saveRole(new RoleCommand(
            "parent",
            "Local",
            "Parent",
            null,
            false,
            List.of(),
            List.of("child"),
            Map.of())));

    assertEquals("Role cannot contain itself through child role: child", error.getMessage());
  }

  @Test
  void saveRoleRejectsReadOnlyRoleUpdate() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("nx-admin", true));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.saveRole(new RoleCommand(
            "nx-admin",
            "Local",
            "Administrator",
            null,
            true,
            List.of(),
            List.of(),
            Map.of())));

    assertEquals("Role is read-only: nx-admin", error.getMessage());
  }

  @Test
  void savePrivilegeRejectsReadOnlyPrivilegeUpdate() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.privilege(privilege("nx-repository-view-*-*-read", true));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.savePrivilege(new PrivilegeCommand(
            "nx-repository-view-*-*-read",
            "Repository read",
            null,
            "repository-view",
            true,
            Map.of("format", "*", "repository", "*", "actions", "read"))));

    assertEquals("Privilege is read-only: nx-repository-view-*-*-read", error.getMessage());
  }

  @Test
  void deleteRoleRejectsReadOnlyRole() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("nx-admin", true));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.deleteRole("nx-admin"));

    assertEquals("Role is read-only: nx-admin", error.getMessage());
    assertEquals(true, dao.findRole("nx-admin").isPresent());
  }

  @Test
  void deletePrivilegeRejectsReadOnlyPrivilege() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.privilege(privilege("nx-repository-view-*-*-read", true));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.deletePrivilege("nx-repository-view-*-*-read"));

    assertEquals("Privilege is read-only: nx-repository-view-*-*-read", error.getMessage());
    assertEquals(true, dao.findPrivilege("nx-repository-view-*-*-read").isPresent());
  }

  @Test
  void deleteRoleCleansReferencesFromUsersAndParentRoles() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("deploy", false));
    dao.role(role("parent", false));
    long userId = dao.insertUser(new SecurityUserRecord(
        null,
        "Local",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.children("parent", "deploy");
    dao.replaceUserRoles(userId, List.of("deploy", "parent"));
    SecurityManagementService service = new SecurityManagementService(dao);

    service.deleteRole("deploy");

    assertEquals(Optional.empty(), dao.findRole("deploy"));
    assertEquals(List.of(), dao.listRoleChildIds("parent"));
    assertEquals(List.of("parent"), dao.listUserRoleIds("Local", "alice"));
  }

  @Test
  void deletePrivilegeCleansReferencesFromRoles() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("reader", false));
    dao.privilege(privilege("nx-repository-view-*-*-read", false));
    dao.privilege(privilege("nx-repository-view-*-*-browse", false));
    dao.privileges("reader", "nx-repository-view-*-*-read", "nx-repository-view-*-*-browse");
    SecurityManagementService service = new SecurityManagementService(dao);

    service.deletePrivilege("nx-repository-view-*-*-read");

    assertEquals(Optional.empty(), dao.findPrivilege("nx-repository-view-*-*-read"));
    assertEquals(List.of("nx-repository-view-*-*-browse"), dao.listRolePrivilegeIds("reader"));
  }

  @Test
  void saveRolePersistsValidReferences() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("parent", false));
    dao.role(role("child", false));
    dao.privilege(privilege("nx-repository-view-maven2-releases-read", false));
    SecurityManagementService service = new SecurityManagementService(dao);

    var saved = service.saveRole(new RoleCommand(
        "parent",
        "Local",
        "Parent",
        null,
        false,
        List.of("nx-repository-view-maven2-releases-read"),
        List.of("child"),
        Map.of()));

    assertEquals(List.of("nx-repository-view-maven2-releases-read"), saved.privileges());
    assertEquals(List.of("child"), saved.roles());
  }

  @Test
  void nexusAnonymousBuiltinRepositoryViewPrivilegesAllowBrowseAndReadOnly() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("nx-anonymous", true));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-repository-view-*-*-browse",
        "nx-repository-view-*-*-browse",
        null,
        "repository-view",
        true,
        Map.of("format", "*", "repository", "*", "actions", "browse")));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-repository-view-*-*-read",
        "nx-repository-view-*-*-read",
        null,
        "repository-view",
        true,
        Map.of("format", "*", "repository", "*", "actions", "read")));
    dao.privileges("nx-anonymous", "nx-repository-view-*-*-browse", "nx-repository-view-*-*-read");
    SecurityManagementService service = new SecurityManagementService(dao);
    PermissionSubject anonymous = new PermissionSubject("Local", "anonymous", Set.of("nx-anonymous"), null);

    assertEquals(true, service.decide(anonymous,
        new RepositoryPermission("maven-public", RepositoryFormat.MAVEN2, "junit/junit", PermissionAction.BROWSE)).allowed());
    assertEquals(true, service.decide(anonymous,
        new RepositoryPermission("maven-public", RepositoryFormat.MAVEN2, "junit/junit", PermissionAction.READ)).allowed());
    assertEquals(false, service.decide(anonymous,
        new RepositoryPermission("maven-public", RepositoryFormat.MAVEN2, "junit/junit", PermissionAction.ADD)).allowed());
  }

  @Test
  void createApiKeyForOwnerIgnoresRequestOwnerFields() {
    FakeSecurityDao dao = new FakeSecurityDao();
    SecurityManagementService service = new SecurityManagementService(dao);

    var created = service.createApiKeyForOwner(
        "LDAP",
        "alice",
        new ApiKeyCommand(
            "NpmToken",
            "Local",
            "bob",
            "npm login",
            null,
            List.of("publish"),
            null,
            null,
            null));

    assertEquals("NpmToken", created.apiKey().domain());
    assertEquals("LDAP", created.apiKey().ownerSource());
    assertEquals("alice", created.apiKey().ownerUserId());
    assertEquals(List.of("publish"), created.apiKey().scopes());
    assertEquals(true, created.token().startsWith("NpmToken."));
    assertEquals(1, service.listApiKeysForOwner("LDAP", "alice").size());
    assertEquals(0, service.listApiKeysForOwner("Local", "bob").size());
  }

  @Test
  void createApiKeyForOwnerReusesRecoverableExistingToken() {
    FakeSecurityDao dao = new FakeSecurityDao();
    SecurityManagementService service = new SecurityManagementService(dao);

    var first = service.createApiKeyForOwner(
        "Local",
        "alice",
        new ApiKeyCommand("NpmToken", null, null, "npm login", null, List.of(), null, null, null));
    var second = service.createApiKeyForOwner(
        "Local",
        "alice",
        new ApiKeyCommand("NpmToken", null, null, "npm login", null, List.of(), null, null, null));

    String rawToken = first.token().substring("NpmToken.".length());
    ApiKeyRecord stored = dao.findApiKey("NpmToken", "Local", "alice").orElseThrow();
    assertEquals(first.token(), second.token());
    assertEquals(first.apiKey().id(), second.apiKey().id());
    assertEquals(1, service.listApiKeysForOwner("Local", "alice").size());
    assertEquals(Optional.of(rawToken), ApiKeyTokenPayloads.decryptRawToken(stored.encryptedPayload()));
    assertEquals(SecurityHashing.sha256(rawToken), stored.apiKeyHash());
  }

  @Test
  void resetApiKeyForOwnerRotatesRecoverableExistingToken() {
    FakeSecurityDao dao = new FakeSecurityDao();
    SecurityManagementService service = new SecurityManagementService(dao);

    var first = service.createApiKeyForOwner(
        "Local",
        "alice",
        new ApiKeyCommand("NpmToken", null, null, "npm login", null, List.of(), null, null, null));
    var reset = service.resetApiKeyForOwner(first.apiKey().id(), "Local", "alice");
    var afterResetCreate = service.createApiKeyForOwner(
        "Local",
        "alice",
        new ApiKeyCommand("NpmToken", null, null, "npm login", null, List.of(), null, null, null));

    assertNotEquals(first.token(), reset.token());
    assertEquals(first.apiKey().id(), reset.apiKey().id());
    assertEquals(reset.token(), afterResetCreate.token());
    assertEquals(1, service.listApiKeysForOwner("Local", "alice").size());
  }

  @Test
  void ownerScopedApiKeyDeleteRejectsOtherOwners() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.apiKey(new ApiKeyRecord(
        10L,
        "NpmToken",
        "Local",
        "bob",
        "npm login",
        "ACTIVE",
        "hash",
        "NpmToken.abc",
        Map.of("values", List.of()),
        "{imported}",
        null,
        null,
        null,
        null));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.deleteApiKeyForOwner(10L, "Local", "alice"));

    assertEquals("API key not found: 10", error.getMessage());
    assertEquals(1, dao.listApiKeysForOwner("Local", "bob").size());
  }

  @Test
  void deleteUserDeletesOwnerApiKeys() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.insertUser(new SecurityUserRecord(
        null,
        "Local",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.insertUser(new SecurityUserRecord(
        null,
        "Local",
        "bob",
        "Bob",
        null,
        "bob@example.com",
        "hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.apiKey(apiKey(10L, "NpmToken", "Local", "alice"));
    dao.apiKey(apiKey(11L, "NuGetApiKey", "Local", "alice"));
    dao.apiKey(apiKey(12L, "NpmToken", "Local", "bob"));
    SecurityManagementService service = new SecurityManagementService(dao);

    service.deleteUser("Local", "alice");

    assertEquals(Optional.empty(), dao.findUser("Local", "alice"));
    assertEquals(0, service.listApiKeysForOwner("Local", "alice").size());
    assertEquals(1, service.listApiKeysForOwner("Local", "bob").size());
  }

  @Test
  void anonymousUserCannotBeDeletedOrHavePasswordChanged() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.insertUser(new SecurityUserRecord(
        null,
        "Local",
        "anonymous",
        "Anonymous",
        null,
        "anonymous@example.invalid",
        "hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.upsertAnonymousConfig(new SecurityAnonymousConfigRecord(
        true,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException deleteError = assertThrows(SecurityValidationException.class, () ->
        service.deleteUser("Local", "anonymous"));
    SecurityValidationException passwordError = assertThrows(SecurityValidationException.class, () ->
        service.changePassword("anonymous", "new-password"));

    assertEquals("User anonymous cannot be deleted, since is marked as the Anonymous user", deleteError.getMessage());
    assertEquals(
        "Password cannot be changed for user anonymous, since is marked as the Anonymous user",
        passwordError.getMessage());
    assertEquals(true, dao.findUser("Local", "anonymous").isPresent());
  }

  @Test
  void currentUserCannotBeDeletedFromCoreUiPath() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.insertUser(new SecurityUserRecord(
        null,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "hash",
        "ACTIVE",
        null,
        Map.of()));
    SecurityManagementService service = new SecurityManagementService(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        service.deleteUser("Local", "admin", "admin"));

    assertEquals(
        "User admin cannot be deleted, since is the user currently logged into the application",
        error.getMessage());
    assertEquals(true, dao.findUser("Local", "admin").isPresent());
  }

  @Test
  void deleteApiKeyEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.apiKey(apiKey(7L, "NpmToken", "Local", "alice"));
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.deleteApiKey(7L);

    assertEquals(1, cache.evictAllCalls, "deleted api key must drop every cached subject");
  }

  @Test
  void deleteApiKeyMissDoesNotEvict() {
    FakeSecurityDao dao = new FakeSecurityDao();
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    assertThrows(SecurityValidationException.class, () -> service.deleteApiKey(404L));

    assertEquals(0, cache.evictAllCalls, "failed delete must not touch cache");
  }

  @Test
  void createApiKeyEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.createApiKey(new ApiKeyCommand(
        "NpmToken", "Local", "alice", "alice key", "ACTIVE", List.of(), null, null, null));

    assertEquals(1, cache.evictAllCalls, "creating an api key may overwrite a prior hash for the same owner");
  }

  @Test
  void resetApiKeyEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.apiKey(apiKey(7L, "NpmToken", "Local", "alice"));
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.resetApiKey(7L);

    assertTrue(cache.evictAllCalls >= 1, "reset must drop the cached subject for the old hash");
  }

  @Test
  void deleteUserEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.insertUser(new SecurityUserRecord(
        null, "Local", "alice", "Alice", null, "alice@example.com",
        "hash", "ACTIVE", null, Map.of()));
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.deleteUser("Local", "alice");

    assertTrue(cache.evictAllCalls >= 1, "deleting a user must drop their cached api-key subjects");
  }

  @Test
  void saveRoleEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.saveRole(new RoleCommand(
        "nx-reader", "Local", "Reader", null, false, List.of(), List.of(), Map.of()));

    assertTrue(cache.evictAllCalls >= 1, "role changes must invalidate cached subjects that embed roles");
  }

  @Test
  void deleteRoleEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.role(role("nx-reader", false));
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.deleteRole("nx-reader");

    assertTrue(cache.evictAllCalls >= 1, "deleting a role must invalidate api-key subjects that embed it");
  }

  @Test
  void savePrivilegeEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.savePrivilege(new PrivilegeCommand(
        "p1", "p1", null, "wildcard", false, Map.of("pattern", "nexus:read:*")));

    assertTrue(cache.evictAllCalls >= 1, "privilege changes must invalidate cached subjects");
  }

  @Test
  void deletePrivilegeEvictsApiKeyAuthCache() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.privilege(privilege("p1", false));
    CountingApiKeyAuthCache cache = new CountingApiKeyAuthCache();
    SecurityManagementService service = new SecurityManagementService(dao, null, cache);

    service.deletePrivilege("p1");

    assertTrue(cache.evictAllCalls >= 1, "deleting a privilege must invalidate cached subjects");
  }

  @Test
  void nullApiKeyAuthCacheIsHandledGracefully() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.apiKey(apiKey(7L, "NpmToken", "Local", "alice"));
    SecurityManagementService service = new SecurityManagementService(dao, null, null);

    // Must not throw NPE.
    service.deleteApiKey(7L);
  }

  private static class CountingApiKeyAuthCache extends ApiKeyAuthCache {
    int evictAllCalls;

    CountingApiKeyAuthCache() {
      super(new com.github.klboke.kkrepo.server.support.InMemorySharedCache(), true, 60, 5);
    }

    @Override
    public void evictAll() {
      evictAllCalls++;
      super.evictAll();
    }
  }

  private static SecurityRoleRecord role(String roleId, boolean readOnly) {
    return new SecurityRoleRecord(roleId, "Local", roleId, null, readOnly, Map.of());
  }

  private static void seedAdminRole(FakeSecurityDao dao) {
    dao.role(role("nx-admin", true));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "nx-all",
        "All Nexus permissions",
        "wildcard",
        true,
        Map.of("pattern", "nexus:*")));
    dao.privileges("nx-admin", "nx-all");
  }

  private static SecurityPrivilegeRecord privilege(String privilegeId, boolean readOnly) {
    return new SecurityPrivilegeRecord(privilegeId, privilegeId, null, "wildcard", readOnly, Map.of());
  }

  private static ApiKeyRecord apiKey(long id, String domain, String ownerSource, String ownerUserId) {
    return new ApiKeyRecord(
        id,
        domain,
        ownerSource,
        ownerUserId,
        domain + " key",
        "ACTIVE",
        "hash-" + id,
        domain + "." + id,
        Map.of("values", List.of()),
        "{imported}",
        null,
        null,
        null,
        null);
  }

  private static class FakeSecurityDao extends SecurityDao {
    private final Map<String, SecurityRoleRecord> roles = new LinkedHashMap<>();
    private final Map<String, SecurityPrivilegeRecord> privileges = new LinkedHashMap<>();
    private final Map<String, SecurityUserRecord> users = new LinkedHashMap<>();
    private final Map<String, List<String>> rolePrivileges = new LinkedHashMap<>();
      private final Map<String, List<String>> roleChildren = new LinkedHashMap<>();
      private final Map<Long, List<String>> userRoles = new LinkedHashMap<>();
      private final Map<Long, ApiKeyRecord> apiKeys = new LinkedHashMap<>();
      private final Map<String, SecurityRealmRecord> realms = new LinkedHashMap<>();
      private SecurityAnonymousConfigRecord anonymousConfig;
    private long nextUserId = 1L;
    private long nextApiKeyId = 1L;

    private FakeSecurityDao() {
      super(null, null);
    }

    private void role(SecurityRoleRecord role) {
      roles.put(role.roleId(), role);
    }

    private void privilege(SecurityPrivilegeRecord privilege) {
      privileges.put(privilege.privilegeId(), privilege);
    }

    private void privileges(String roleId, String... privilegeIds) {
      rolePrivileges.put(roleId, List.of(privilegeIds));
    }

    private void children(String roleId, String... childRoleIds) {
      roleChildren.put(roleId, List.of(childRoleIds));
    }

      private void apiKey(ApiKeyRecord apiKey) {
        apiKeys.put(apiKey.id(), apiKey);
        nextApiKeyId = Math.max(nextApiKeyId, apiKey.id() + 1);
      }

      @Override
      public List<SecurityRealmRecord> listRealms() {
        return List.copyOf(realms.values());
      }

      @Override
      public Optional<SecurityRealmRecord> findRealm(String realmId) {
        return Optional.ofNullable(realms.get(realmId));
      }

      @Override
      public void upsertRealm(SecurityRealmRecord record) {
        SecurityRealmRecord stored = new SecurityRealmRecord(
            record.id() == null ? (long) realms.size() + 1 : record.id(),
            record.realmId(),
            record.type(),
            record.name(),
            record.enabled(),
            record.priority(),
            record.attributes());
        realms.put(stored.realmId(), stored);
      }

      @Override
      public void updateRealmConfig(List<String> activeRealmIds) {
        // not needed for service-level assertions
      }

      @Override
      public Optional<SecurityRoleRecord> findRole(String roleId) {
      return Optional.ofNullable(roles.get(roleId));
    }

    @Override
    public void upsertRole(SecurityRoleRecord record) {
      roles.put(record.roleId(), record);
    }

    @Override
    public int deleteRole(String roleId) {
      if (roles.remove(roleId) == null) {
        return 0;
      }
      rolePrivileges.remove(roleId);
      roleChildren.remove(roleId);
      return 1;
    }

    @Override
    public void removeRoleReferences(String roleId) {
      roleChildren.replaceAll((key, value) -> value.stream()
          .filter(childRoleId -> !roleId.equals(childRoleId))
          .toList());
      userRoles.replaceAll((key, value) -> value.stream()
          .filter(userRoleId -> !roleId.equals(userRoleId))
          .toList());
    }

    @Override
    public Optional<SecurityPrivilegeRecord> findPrivilege(String privilegeId) {
      return Optional.ofNullable(privileges.get(privilegeId));
    }

    @Override
    public void upsertPrivilege(SecurityPrivilegeRecord record) {
      privileges.put(record.privilegeId(), record);
    }

    @Override
    public int deletePrivilege(String privilegeId) {
      return privileges.remove(privilegeId) == null ? 0 : 1;
    }

    @Override
    public void removePrivilegeReferences(String privilegeId) {
      rolePrivileges.replaceAll((key, value) -> value.stream()
          .filter(rolePrivilegeId -> !privilegeId.equals(rolePrivilegeId))
          .toList());
    }

    @Override
    public List<SecurityPrivilegeRecord> listPrivilegesForRoles(List<String> roleIds) {
      return roleIds.stream()
          .flatMap(roleId -> rolePrivileges.getOrDefault(roleId, List.of()).stream())
          .distinct()
          .map(privileges::get)
          .filter(java.util.Objects::nonNull)
          .toList();
    }

    @Override
    public long insertUser(SecurityUserRecord record) {
      long id = nextUserId++;
      SecurityUserRecord stored = new SecurityUserRecord(
          id,
          record.source(),
          record.userId(),
          record.firstName(),
          record.lastName(),
          record.email(),
          record.passwordHash(),
          record.status(),
          record.externalId(),
          record.attributes());
      users.put(stored.source() + "/" + stored.userId(), stored);
      return id;
    }

    @Override
    public void updateUser(SecurityUserRecord record) {
      users.put(record.source() + "/" + record.userId(), record);
    }

    @Override
    public List<SecurityUserRecord> listUsers() {
      return List.copyOf(users.values());
    }

    @Override
    public List<SecurityRepositoryTargetRecord> listRepositoryTargets() {
      return List.of();
    }

    @Override
    public Optional<SecurityUserRecord> findUser(String source, String userId) {
      return Optional.ofNullable(users.get(source + "/" + userId));
    }

    @Override
    public Optional<SecurityAnonymousConfigRecord> findAnonymousConfig() {
      return Optional.ofNullable(anonymousConfig);
    }

    @Override
    public void upsertAnonymousConfig(SecurityAnonymousConfigRecord record) {
      anonymousConfig = record;
    }

    @Override
    public int deleteUser(String source, String userId) {
      return users.remove(source + "/" + userId) == null ? 0 : 1;
    }

    @Override
    public void replaceUserRoles(long userNumericId, List<String> roleIds) {
      userRoles.put(userNumericId, roleIds == null ? List.of() : List.copyOf(roleIds));
    }

    @Override
    public List<String> listUserRoleIds(long userNumericId) {
      return userRoles.getOrDefault(userNumericId, List.of());
    }

    @Override
    public List<String> listUserRoleIds(String source, String userId) {
      return users.values().stream()
          .filter(user -> user.source().equals(source) && user.userId().equals(userId))
          .findFirst()
          .map(user -> userRoles.getOrDefault(user.id(), List.of()))
          .orElse(List.of());
    }

    @Override
    public void replaceRolePrivileges(String roleId, List<String> privilegeIds) {
      rolePrivileges.put(roleId, privilegeIds == null ? List.of() : List.copyOf(privilegeIds));
    }

    @Override
    public List<String> listRolePrivilegeIds(String roleId) {
      return rolePrivileges.getOrDefault(roleId, List.of());
    }

    @Override
    public void replaceRoleInheritance(String roleId, List<String> childRoleIds) {
      roleChildren.put(roleId, childRoleIds == null ? List.of() : List.copyOf(childRoleIds));
    }

    @Override
    public List<String> listRoleChildIds(String roleId) {
      return roleChildren.getOrDefault(roleId, List.of());
    }

    @Override
    public List<ApiKeyRecord> listApiKeysForOwner(String ownerSource, String ownerUserId) {
      return apiKeys.values().stream()
          .filter(apiKey -> apiKey.ownerSource().equals(ownerSource) && apiKey.ownerUserId().equals(ownerUserId))
          .toList();
    }

    @Override
    public Optional<ApiKeyRecord> findApiKey(long id) {
      return Optional.ofNullable(apiKeys.get(id));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
      return Optional.ofNullable(apiKeys.get(id))
          .filter(apiKey -> apiKey.ownerSource().equals(ownerSource) && apiKey.ownerUserId().equals(ownerUserId));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKey(String domain, String ownerSource, String ownerUserId) {
      return apiKeys.values().stream()
          .filter(apiKey -> apiKey.domain().equals(domain))
          .filter(apiKey -> apiKey.ownerSource().equals(ownerSource))
          .filter(apiKey -> apiKey.ownerUserId().equals(ownerUserId))
          .findFirst();
    }

    @Override
    public void upsertApiKey(ApiKeyRecord record) {
      ApiKeyRecord existing = findApiKey(record.domain(), record.ownerSource(), record.ownerUserId()).orElse(null);
      Long id = existing == null ? nextApiKeyId++ : existing.id();
      apiKeys.put(id, new ApiKeyRecord(
          id,
          record.domain(),
          record.ownerSource(),
          record.ownerUserId(),
          record.displayName(),
          record.status(),
          record.apiKeyHash(),
          record.tokenPrefix(),
          record.scopes(),
          record.encryptedPayload(),
          existing == null ? null : existing.createdAt(),
          null,
          record.expiresAt(),
          record.lastUsedAt()));
    }

    @Override
    public int deleteApiKey(long id) {
      return apiKeys.remove(id) == null ? 0 : 1;
    }

    @Override
    public int deleteApiKeysForOwner(String ownerSource, String ownerUserId) {
      List<Long> matches = apiKeys.values().stream()
          .filter(apiKey -> ownerSource.equals(apiKey.ownerSource()))
          .filter(apiKey -> ownerUserId.equals(apiKey.ownerUserId()))
          .map(ApiKeyRecord::id)
          .toList();
      matches.forEach(apiKeys::remove);
      return matches.size();
    }
  }
}
