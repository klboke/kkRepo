package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.persistence.mysql.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityUserRecord;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.repositories.RepositoryView;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAnonymousSettings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusApiKeyCommand;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusContentSelector;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusCreatedApiKey;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusPrivilege;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusRealmSettings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusRole;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusAuthToken;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiPrivilege;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiReference;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiRole;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUiUserRoleMappings;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUserAccount;
import com.github.klboke.kkrepo.server.security.NexusSecurityPayloads.NexusUserAccountPassword;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.OidcSettingsCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RealmCommand;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class NexusSecurityRestControllerTest {

  @Test
  void userSourcesExposeConfiguredRealmSources() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", false, 10, Map.of("source", "LDAP")));
    dao.realm(new SecurityRealmRecord(3L, "oidc", "OIDC", "OIDC", false, 20, Map.of("source", "OIDC")));
    NexusSecurityRestController controller = controller(dao);

    var sources = controller.userSources();

    assertEquals(3, sources.size());
    assertEquals("Local", sources.get(0).id());
    assertEquals("Local", sources.get(0).name());
    assertEquals("LDAP", sources.get(1).id());
    assertEquals("LDAP", sources.get(1).name());
    assertEquals("OIDC", sources.get(2).id());
    assertEquals("OIDC", sources.get(2).name());
  }

  @Test
  void nexusRealmRestApiUsesNexusRealmIdsAtTheBoundary() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0,
        Map.of("source", "Local", "nexusRealm", "NexusAuthenticatingRealm")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", true, 10,
        Map.of("source", "LDAP", "nexusRealm", "LdapRealm")));
    dao.realm(new SecurityRealmRecord(3L, "oidc", "OIDC", "OIDC", false, 20,
        Map.of("source", "OIDC", "nexusRealm", "OidcRealm")));
    NexusSecurityRestController controller = controller(dao);

    assertEquals(Set.of("NexusAuthenticatingRealm", "NexusAuthorizingRealm", "LdapRealm", "OidcRealm"),
        controller.availableRealms().stream()
            .map(realm -> realm.id())
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(List.of("NexusAuthenticatingRealm", "NexusAuthorizingRealm", "LdapRealm"),
        controller.activeRealms());

    controller.updateActiveRealms(List.of("OidcRealm", "NexusAuthenticatingRealm"));

    assertEquals(List.of("OidcRealm", "NexusAuthenticatingRealm", "NexusAuthorizingRealm"),
        controller.activeRealms());
    assertEquals(true, dao.findRealm("oidc").orElseThrow().enabled());
    assertEquals(0, dao.findRealm("oidc").orElseThrow().priority());
    assertEquals(true, dao.findRealm("local").orElseThrow().enabled());
    assertEquals(10, dao.findRealm("local").orElseThrow().priority());
    assertEquals(false, dao.findRealm("ldap").orElseThrow().enabled());
  }

  @Test
  void nexusDeleteUserRejectsNonLocalUsers() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "LDAP",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        null,
        "ACTIVE",
        "alice",
        Map.of()));
    NexusSecurityRestController controller = controller(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        controller.deleteUser("alice", null));

    assertEquals("Non-local user cannot be deleted.", error.getMessage());
    assertEquals(0, dao.deletedUsers);
  }

  @Test
  void nexusDeleteUserPrefersLocalUserWhenSourceIsOmitted() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "LDAP",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        null,
        "ACTIVE",
        "alice",
        Map.of()));
    dao.user(new SecurityUserRecord(
        2L,
        "Local",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    NexusSecurityRestController controller = controller(dao);

    controller.deleteUser("alice", null);

    assertEquals(1, dao.deletedUsers);
    assertEquals(true, dao.findUser("LDAP", "alice").isPresent());
    assertEquals(Optional.empty(), dao.findUser("Local", "alice"));
  }

  @Test
  void nexusDeleteUserDeletesLocalUsers() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "$shiro1$hash",
        "ACTIVE",
        "alice",
        Map.of()));
    NexusSecurityRestController controller = controller(dao);

    controller.deleteUser("alice", null);

    assertEquals(1, dao.deletedUsers);
  }

  @Test
  void restSecuritySourceAliasesResolveToDefaultSource() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("nx-reader", "Local", "Reader", null, false, Map.of()));
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-reader");
    dao.apiKey(new ApiKeyRecord(
        10L,
        "NpmToken",
        "Local",
        "alice",
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
    NexusSecurityRestController controller = controller(dao);

    var createdRole = controller.createRole(new NexusRole(
        "team-role",
        "Nexus",
        "Team Role",
        null,
        Set.of(),
        Set.of()));

    assertEquals("Local", dao.findRole("team-role").orElseThrow().source());
    assertEquals("Local", createdRole.source());
    assertEquals(1, controller.roles("Nexus").stream()
        .filter(role -> "team-role".equals(role.id()))
        .count());
    assertEquals("team-role", controller.getRole("team-role", "Nexus").id());
    assertEquals(List.of("alice"), controller.users(null, "Nexus").stream()
        .map(user -> user.userId())
        .toList());
    assertEquals(1, controller.apiKeys(null, "Nexus", "alice").size());
  }

  @Test
  void nexusRoleRestUsesDefaultSourceAndIgnoresSourceFilters() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", true, 10, Map.of("source", "LDAP")));
    dao.upsertRole(new SecurityRoleRecord("existing", "Local", "Existing", null, false, Map.of()));
    NexusSecurityRestController controller = controller(dao);

    assertEquals(List.of("existing"), controller.roles("bad").stream()
        .map(NexusRole::id)
        .toList());
    assertEquals("existing", controller.getRole("existing", "bad").id());
    assertEquals(List.of("existing"), controller.roles("LDAP").stream()
        .map(NexusRole::id)
        .toList());
    assertEquals("existing", controller.getRole("existing", "LDAP").id());
    var created = controller.createRole(new NexusRole(
        "api-role",
        "LDAP",
        "API Role",
        null,
        Set.of(),
        Set.of()));
    controller.updateRole("api-role", new NexusRole(
        "api-role",
        "LDAP",
        "API Role Updated",
        null,
        Set.of(),
        Set.of()));

    assertEquals("Local", created.source());
    assertEquals("Local", dao.findRole("api-role").orElseThrow().source());
    assertEquals("API Role Updated", dao.findRole("api-role").orElseThrow().name());
  }

  @Test
  void nexusRoleRestReadTreatsRolesAsSharedAcrossUserSources() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("team-role", "Local", "Team Role", null, false, Map.of()));
    NexusSecurityRestController controller = controller(dao);

    var ldapRole = controller.getRole("team-role", "LDAP");
    var role = controller.getRole("team-role", null);

    assertEquals("team-role", ldapRole.id());
    assertEquals("team-role", role.id());
    assertEquals("Local", role.source());
    assertEquals(List.of("team-role"), controller.roles("LDAP").stream()
        .map(NexusRole::id)
        .toList());
    assertEquals(1, controller.roles(null).size());
  }

  @Test
  void nexusRoleRestWriteDefaultsToDefaultSourceOnly() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("ldap-role", "LDAP", "LDAP Role", null, false, Map.of()));
    NexusSecurityRestController controller = controller(dao);

    ResponseStatusException updateError = assertThrows(ResponseStatusException.class, () ->
        controller.updateRole("ldap-role", new NexusRole(
            "ldap-role",
            "LDAP",
            "Updated",
            null,
            Set.of(),
            Set.of())));
    ResponseStatusException deleteError = assertThrows(ResponseStatusException.class, () ->
        controller.deleteRole("ldap-role"));

    SecurityRoleRecord stored = dao.findRole("ldap-role").orElseThrow();
    assertEquals(404, updateError.getStatusCode().value());
    assertEquals(404, deleteError.getStatusCode().value());
    assertEquals("LDAP", stored.source());
    assertEquals("LDAP Role", stored.name());
  }

  @Test
  void nexusRoleRestValidatesRequestLikeNexusRoleApi() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("child", "Local", "Child", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-users-read",
        "nx-users-read",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:users:read")));
    NexusSecurityRestController controller = controller(dao);

    SecurityValidationException missingName = assertThrows(SecurityValidationException.class, () ->
        controller.createRole(new NexusRole(
            "missing-name",
            null,
            null,
            null,
            Set.of(),
            Set.of())));
    SecurityValidationException missingPrivilege = assertThrows(SecurityValidationException.class, () ->
        controller.createRole(new NexusRole(
            "missing-privilege",
            null,
            "Missing Privilege",
            null,
            Set.of("absent-privilege"),
            Set.of())));
    SecurityValidationException missingRole = assertThrows(SecurityValidationException.class, () ->
        controller.createRole(new NexusRole(
            "missing-role",
            null,
            "Missing Role",
            null,
            Set.of(),
            Set.of("absent-role"))));
    SecurityValidationException selfRole = assertThrows(SecurityValidationException.class, () ->
        controller.createRole(new NexusRole(
            "self-role",
            null,
            "Self Role",
            null,
            Set.of(),
            Set.of("self-role"))));

    var created = controller.createRole(new NexusRole(
        "parent",
        null,
        "Parent",
        null,
        Set.of("nx-users-read"),
        Set.of("child")));

    assertEquals("name is required", missingName.getMessage());
    assertEquals("\"Privilege 'absent-privilege' contained in role 'missing-privilege' not found.\"",
        missingPrivilege.getMessage());
    assertEquals("\"Role 'absent-role' contained in role 'missing-role' not found.\"", missingRole.getMessage());
    assertEquals("\"Role 'self-role' cannot contain itself either directly or indirectly through child roles.\"",
        selfRole.getMessage());
    assertEquals("Parent", created.name());
    assertEquals("parent", created.description());
    assertEquals("child", controller.getRole("child", null).description());
  }

  @Test
  void nexusPrivilegeRestIgnoresRequestReadOnlyAndPreservesExistingTypeOnUpdate() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.privilege(new SecurityPrivilegeRecord(
        "custom-wildcard",
        "custom-wildcard",
        "Old",
        "wildcard",
        false,
        Map.of("pattern", "nexus:old:read")));
    NexusSecurityRestController controller = controller(dao);

    controller.createWildcardPrivilege(new NexusPrivilege(
        null,
        "created-wildcard",
        "Created",
        true,
        "nexus:created:read",
        null,
        null,
        null,
        null,
        null,
        null,
        null));
    controller.updateRepositoryViewPrivilege(
        "custom-wildcard",
        new NexusPrivilege(
            null,
            "custom-wildcard",
            "Updated",
            true,
            null,
            null,
            List.of("read"),
            "maven2",
            "releases",
            null,
            null,
            null));

    SecurityPrivilegeRecord created = dao.findPrivilege("created-wildcard").orElseThrow();
    SecurityPrivilegeRecord updated = dao.findPrivilege("custom-wildcard").orElseThrow();
    assertEquals(false, created.readOnly());
    assertEquals("wildcard", created.type());
    assertEquals("nexus:created:read", created.properties().get("pattern"));
    assertEquals(false, updated.readOnly());
    assertEquals("wildcard", updated.type());
    assertEquals("Updated", updated.description());
    assertEquals("maven2", updated.properties().get("format"));
    assertEquals("releases", updated.properties().get("repository"));
    assertEquals(List.of("read"), updated.properties().get("actions"));
  }

  @Test
  void nexusRoleAndPrivilegeRestListsUseNexusOrdering() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("team-z", "Local", "Team Z", null, false, Map.of()));
    dao.upsertRole(new SecurityRoleRecord("team-a", "Local", "Team A", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "z-privilege",
        "z-privilege",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:z:read")));
    dao.privilege(new SecurityPrivilegeRecord(
        "a-privilege",
        "a-privilege",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:a:read")));
    NexusSecurityRestController controller = controller(dao);

    assertEquals(List.of("team-a", "team-z"), controller.roles(null).stream()
        .map(NexusRole::id)
        .toList());
    assertEquals(List.of("a-privilege", "z-privilege"), controller.privileges().stream()
        .map(NexusPrivilege::name)
        .toList());
  }

  @Test
  void nexusPrivilegeRestValidatesDescriptorFieldsAndActions() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        1L,
        "team-a",
        "team-a",
        "*",
        "path =~ \"^/team-a/.*\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector", "type", "csel")));
    NexusSecurityRestController controller = controller(dao);

    SecurityValidationException wildcardError = assertThrows(SecurityValidationException.class, () ->
        controller.createWildcardPrivilege(new NexusPrivilege(
            null,
            "bad-wildcard",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null)));
    SecurityValidationException appActionError = assertThrows(SecurityValidationException.class, () ->
        controller.createApplicationPrivilege(new NexusPrivilege(
            null,
            "bad-app",
            null,
            null,
            null,
            "security",
            List.of("read", "run"),
            null,
            null,
            null,
            null,
            null)));
    SecurityValidationException repoActionError = assertThrows(SecurityValidationException.class, () ->
        controller.createRepositoryViewPrivilege(new NexusPrivilege(
            null,
            "bad-repo",
            null,
            null,
            null,
            null,
            List.of("run"),
            "maven2",
            "*",
            null,
            null,
            null)));
    SecurityValidationException selectorError = assertThrows(SecurityValidationException.class, () ->
        controller.createRepositoryContentSelectorPrivilege(new NexusPrivilege(
            null,
            "bad-selector",
            null,
            null,
            null,
            null,
            List.of("read"),
            "maven2",
            "*",
            "missing",
            null,
            null)));

    controller.createApplicationPrivilege(new NexusPrivilege(
        null,
        "app-ok",
        "App",
        null,
        null,
        "security",
        List.of("read", "add", "edit", "delete"),
        null,
        null,
        null,
        null,
        null));
    controller.createRepositoryContentSelectorPrivilege(new NexusPrivilege(
        null,
        "selector-ok",
        "Selector",
        null,
        null,
        null,
        List.of("browse", "read"),
        "maven2",
        "*",
        "team-a",
        null,
        null));

    assertEquals("pattern is required", wildcardError.getMessage());
    assertEquals("\"Privilege of type 'application' cannot use action(s) of type 'RUN'.\"",
        appActionError.getMessage());
    assertEquals("\"Privilege of type 'repository-view' cannot use action(s) of type 'RUN'.\"",
        repoActionError.getMessage());
    assertEquals("\"Invalid selector 'missing' supplied.\"", selectorError.getMessage());
    assertEquals(List.of("read", "create", "update", "delete"),
        dao.findPrivilege("app-ok").orElseThrow().properties().get("actions"));
    assertEquals(List.of("browse", "read"),
        dao.findPrivilege("selector-ok").orElseThrow().properties().get("actions"));
  }

  @Test
  void contentSelectorsDoNotExposeRepositoryTargets() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        1L,
        "maven-target",
        "Maven target",
        "maven2",
        null,
        Map.of("patterns", List.of("/org/example/.*")),
        Map.of("source", "nexus-repository-target")));
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        2L,
        "team-a",
        "team-a",
        "*",
        "path =~ \"^/team-a/.*\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector", "type", "csel", "description", "Team A")));
    NexusSecurityRestController controller = controller(dao);

    List<NexusContentSelector> selectors = controller.contentSelectors();

    assertEquals(1, selectors.size());
    assertEquals("team-a", selectors.get(0).name());
    assertEquals("Team A", selectors.get(0).description());
  }

  @Test
  void createdContentSelectorsAreTaggedSeparatelyFromRepositoryTargets() {
    FakeSecurityDao dao = new FakeSecurityDao();
    NexusSecurityRestController controller = controller(dao);

    var response = controller.createContentSelector(new NexusContentSelector(
        "team-b",
        "jexl",
        "Team B",
        "path =~ \"^/team-b/.*\""));

    SecurityRepositoryTargetRecord record = dao.findRepositoryTarget("team-b").orElseThrow();
    assertEquals(204, response.getStatusCode().value());
    assertEquals("nexus-content-selector", record.attributes().get("source"));
    assertEquals("csel", record.attributes().get("type"));
    assertEquals("path =~ \"^/team-b/.*\"", record.contentExpression());
  }

  @Test
  void contentSelectorRestDoesNotTreatRepositoryTargetsAsSelectorsAndPreservesTypeOnUpdate() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        1L,
        "maven-target",
        "Maven target",
        "maven2",
        null,
        Map.of("patterns", List.of("/org/example/.*")),
        Map.of("source", "nexus-repository-target")));
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        2L,
        "legacy-selector",
        "legacy-selector",
        "*",
        "format == \"maven2\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector", "type", "jexl", "description", "Legacy")));
    NexusSecurityRestController controller = controller(dao);

    ResponseStatusException targetError = assertThrows(ResponseStatusException.class, () ->
        controller.getContentSelector("maven-target"));
    controller.updateContentSelector("legacy-selector", new NexusContentSelector(
        "renamed-selector",
        "csel",
        "Updated",
        "path =^ \"/org/example\""));

    SecurityRepositoryTargetRecord updated = dao.findRepositoryTarget("legacy-selector").orElseThrow();
    assertEquals(404, targetError.getStatusCode().value());
    assertEquals(Optional.empty(), dao.findRepositoryTarget("renamed-selector"));
    assertEquals("jexl", updated.attributes().get("type"));
    assertEquals("Updated", updated.attributes().get("description"));
    assertEquals("path =^ \"/org/example\"", updated.contentExpression());
  }

  @Test
  void contentSelectorRestRejectsDeleteWhenReferencedByPrivilege() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        1L,
        "team-a",
        "team-a",
        "*",
        "path =~ \"^/team-a/.*\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector", "type", "csel", "description", "Team A")));
    dao.privilege(new SecurityPrivilegeRecord(
        "team-a-read",
        "Team A read",
        null,
        "repository-content-selector",
        false,
        Map.of("contentSelector", "team-a", "format", "maven2", "repository", "*", "actions", List.of("read"))));
    NexusSecurityRestController controller = controller(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        controller.deleteContentSelector("team-a"));

    assertTrue(error.getMessage().contains("Team A read"));
    assertEquals(true, dao.findRepositoryTarget("team-a").isPresent());
  }

  @Test
  void nexusAnonymousSettingsExposeConfiguredNexusFields() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(
        true,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    NexusSecurityRestController controller = controller(dao);

    NexusAnonymousSettings settings = controller.anonymousSettings();

    assertEquals(true, settings.enabled());
    assertEquals("anonymous", settings.userId());
    assertEquals("NexusAuthorizingRealm", settings.realmName());
  }

  @Test
  void nexusAnonymousSettingsUpdateKeepsAnonymousSourceLocal() {
    FakeSecurityDao dao = new FakeSecurityDao();
    NexusSecurityRestController controller = controller(dao);

    NexusAnonymousSettings updated = controller.updateAnonymousSettings(
        new NexusAnonymousSettings(true, "ldap-anonymous", "LdapRealm"));

    SecurityAnonymousConfigRecord record = dao.findAnonymousConfig().orElseThrow();
    assertEquals(true, updated.enabled());
    assertEquals("ldap-anonymous", updated.userId());
    assertEquals("LdapRealm", updated.realmName());
    assertEquals(true, record.enabled());
    assertEquals("Local", record.userSource());
    assertEquals("ldap-anonymous", record.userId());
    assertEquals("LdapRealm", record.realmName());
  }

  @Test
  void internalRealmUpdateKeepsLocalRealmEnabled() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0,
        Map.of("source", "Local", "nexusRealm", "NexusAuthenticatingRealm")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", true, 10,
        Map.of("source", "LDAP", "nexusRealm", "LdapRealm")));
    SecurityManagementController controller = new SecurityManagementController(new SecurityManagementService(dao));

    controller.updateRealms(List.of(
        new RealmCommand("local", "LOCAL", "Local", false, 0, Map.of("source", "Local")),
        new RealmCommand("ldap", "LDAP", "LDAP", false, 10, Map.of("source", "LDAP"))));

    assertEquals(true, dao.findRealm("local").orElseThrow().enabled());
    assertEquals(false, dao.findRealm("ldap").orElseThrow().enabled());
  }

  @Test
  void nexusAnonymousSettingsRejectsUnsupportedRealmNames() {
    FakeSecurityDao dao = new FakeSecurityDao();
    NexusSecurityRestController controller = controller(dao);

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        controller.updateAnonymousSettings(new NexusAnonymousSettings(true, "anonymous", "CustomRealm")));

    assertEquals("Realm does not exist: CustomRealm", error.getMessage());
    assertEquals(Optional.empty(), dao.findAnonymousConfig());
  }

  @Test
  void internalUiAnonymousSettingsStillUsesSameNexusFields() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(
        true,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    NexusAnonymousSettingsController controller = anonymousController(dao);

    NexusAnonymousSettings settings = controller.read();

    assertEquals(true, settings.enabled());
    assertEquals("anonymous", settings.userId());
    assertEquals("NexusAuthorizingRealm", settings.realmName());
  }

  @Test
  void currentApiKeyRestEndpointsStayBoundToAuthenticatedOwner() {
    FakeSecurityDao dao = new FakeSecurityDao();
    NexusSecurityRestController controller = controller(dao);
    HttpServletRequest request = request(subject("alice"));

    NexusCreatedApiKey created = controller.createCurrentApiKey(
        request,
        new NexusApiKeyCommand(
            "NpmToken",
            "LDAP",
            "mallory",
            "npm token",
            "DISABLED",
            Set.of("publish"),
            null,
            null,
            null));

    assertEquals("NpmToken", created.apiKey().domain());
    assertEquals("Local", created.apiKey().ownerSource());
    assertEquals("alice", created.apiKey().ownerUserId());
    assertEquals("ACTIVE", created.apiKey().status());
    assertTrue(created.token().startsWith("NpmToken."));
    assertEquals(1, controller.currentApiKeys(request).size());
    assertEquals(1, controller.apiKeys("NpmToken", "Local", "alice").size());
    assertEquals(0, controller.apiKeys("NpmToken", "LDAP", "mallory").size());

    NexusCreatedApiKey reset = controller.resetCurrentApiKey(request, created.apiKey().id());
    assertEquals(created.apiKey().id(), reset.apiKey().id());
    assertTrue(reset.token().startsWith("NpmToken."));
    assertNotEquals(created.token(), reset.token());

    controller.deleteCurrentApiKey(request, created.apiKey().id());

    assertEquals(0, controller.currentApiKeys(request).size());
  }

  @Test
  void adminApiKeyResetRotatesEvenWhenCreateReusesExistingToken() {
    FakeSecurityDao dao = new FakeSecurityDao();
    NexusSecurityRestController controller = controller(dao);
    NexusApiKeyCommand command = new NexusApiKeyCommand(
        "NpmToken",
        "Local",
        "alice",
        "npm token",
        "ACTIVE",
        Set.of("publish"),
        null,
        null,
        null);

    NexusCreatedApiKey created = controller.createApiKey(command);
    NexusCreatedApiKey repeatedCreate = controller.createApiKey(command);
    NexusCreatedApiKey reset = controller.resetApiKey(created.apiKey().id());
    NexusCreatedApiKey afterResetCreate = controller.createApiKey(command);

    assertEquals(created.token(), repeatedCreate.token());
    assertEquals(created.apiKey().id(), reset.apiKey().id());
    assertNotEquals(created.token(), reset.token());
    assertEquals(reset.token(), afterResetCreate.token());
  }

  @Test
  void internalUiUsersDefaultToLocalSourceAndExposeCoreUiShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0, Map.of("source", "Local")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", false, 10, Map.of("source", "LDAP")));
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        "User",
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.user(new SecurityUserRecord(
        2L,
        "LDAP",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        null,
        "ACTIVE",
        "alice",
        Map.of()));
    NexusSecurityUiController controller = uiController(dao);

    var users = controller.users(null, null, null);
    var sources = controller.userSources();

    assertEquals(1, users.size());
    assertEquals("admin", users.get(0).userId());
    assertEquals("Local", users.get(0).realm());
    assertEquals("active", users.get(0).status());
    assertEquals("#~NEXUS~PLACEHOLDER~PASSWORD~#", users.get(0).password());
    assertEquals(false, users.get(0).external());
    assertEquals("Local", sources.get(0).id());
    assertEquals("LDAP", sources.get(1).id());
  }

  @Test
  void internalUiExternalUsersExposeExternalRolesButPersistOnlyLocalMappings() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.upsertRole(new SecurityRoleRecord("nx-deploy", "Local", "Deploy", null, false, Map.of()));
    dao.user(new SecurityUserRecord(
        2L,
        "LDAP",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        null,
        "ACTIVE",
        "alice",
        Map.of("groups", List.of("ldap-team", "ldap-admin"))));
    dao.roles(2L, "nx-admin");
    NexusSecurityUiController controller = uiController(dao);

    var user = controller.getUser("LDAP", "alice");

    assertEquals(true, user.external());
    assertEquals(Set.of("ldap-team", "ldap-admin"), user.externalRoles());
    assertEquals(Set.of("nx-admin", "ldap-team", "ldap-admin"), user.roles());

    var updated = controller.updateUserRoleMappings(
        "LDAP",
        "alice",
        new NexusUiUserRoleMappings(
            "alice",
            "LDAP",
            Set.of("ldap-team", "ldap-admin", "nx-deploy")));

    assertEquals(List.of("nx-deploy"), dao.listUserRoleIds("LDAP", "alice"));
    assertEquals(Set.of("ldap-team", "ldap-admin"), updated.externalRoles());
    assertEquals(Set.of("nx-deploy", "ldap-team", "ldap-admin"), updated.roles());
  }

  @Test
  void internalUiRolesMapNexusSourceToDefaultSource() {
    FakeSecurityDao dao = new FakeSecurityDao();
    NexusSecurityUiController controller = uiController(dao);

    var created = controller.createRole(new NexusUiRole(
        "team-admin",
        null,
        "Nexus",
        "Team Admin",
        "Team role",
        false,
        null,
        null));

    SecurityRoleRecord stored = dao.findRole("team-admin").orElseThrow();
    assertEquals("Local", stored.source());
    assertEquals("Nexus", created.source());
    assertEquals(List.of("team-admin"), controller.roles("LDAP").stream()
        .map(NexusUiRole::id)
        .toList());
    assertEquals(List.of("team-admin"), controller.roleReferences("OIDC").stream()
        .map(NexusUiReference::id)
        .toList());
  }

  @Test
  void internalUiPrivilegesReturnNexusPagedShapeAndTypes() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-repository-view-maven2-releases-read",
        "Maven releases read",
        "Read Maven releases",
        "repository-view",
        false,
        Map.of("format", "maven2", "repository", "releases", "actions", List.of("read"))));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-users-read",
        "Users read",
        "Read users",
        "wildcard",
        false,
        Map.of("pattern", "nexus:users:read")));
    NexusSecurityUiController controller = uiController(dao);

    var page = controller.privileges("Maven", 0, 10, "name", "ASC");
    List<NexusUiPrivilege> data = page.data();
    var types = controller.privilegeTypes();

    assertEquals(1, page.total());
    assertEquals("nx-repository-view-maven2-releases-read", data.get(0).id());
    assertEquals("repository-view", data.get(0).type());
    var contentSelectorType = types.stream()
        .filter(type -> "repository-content-selector".equals(type.id()))
        .findFirst()
        .orElseThrow();
    assertEquals(List.of("contentSelector", "repository", "actions"), contentSelectorType.formFields().stream()
        .map(NexusSecurityPayloads.NexusUiFormField::id)
        .toList());
    assertFalse(types.stream().anyMatch(type -> "repository-target".equals(type.id())));
  }

  @Test
  void internalUiRealmSettingsExposeNexusRealmNamesAndTypes() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0,
        Map.of("source", "Local", "nexusRealm", "NexusAuthenticatingRealm")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", true, 10,
        Map.of("source", "LDAP", "nexusRealm", "LdapRealm")));
    dao.realm(new SecurityRealmRecord(3L, "oidc", "OIDC", "OIDC", false, 20,
        Map.of("source", "OIDC", "nexusRealm", "OidcRealm")));
    NexusSecurityUiController controller = uiController(dao);

    var settings = controller.realmSettings();
    var types = controller.realmTypes();

    assertEquals(List.of("NexusAuthenticatingRealm", "NexusAuthorizingRealm", "LdapRealm"), settings.realms());
    assertTrue(types.stream().anyMatch(type -> "NexusAuthenticatingRealm".equals(type.id())));
    assertTrue(types.stream().anyMatch(type -> "NexusAuthorizingRealm".equals(type.id())));
    assertTrue(types.stream().anyMatch(type -> "LdapRealm".equals(type.id())));
    assertTrue(types.stream().anyMatch(type -> "OidcRealm".equals(type.id())));
  }

  @Test
  void internalUiRealmSettingsUpdateMapsNexusRealmNamesBackToInternalRealms() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0,
        Map.of("source", "Local", "nexusRealm", "NexusAuthenticatingRealm")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", true, 10,
        Map.of("source", "LDAP", "nexusRealm", "LdapRealm")));
    dao.realm(new SecurityRealmRecord(3L, "oidc", "OIDC", "OIDC", false, 20,
        Map.of("source", "OIDC", "nexusRealm", "OidcRealm")));
    NexusSecurityUiController controller = uiController(dao);

    var settings = controller.updateRealmSettings(new NexusRealmSettings(List.of(
        "OidcRealm",
        "NexusAuthorizingRealm",
        "NexusAuthenticatingRealm")));

    assertEquals(List.of("OidcRealm", "NexusAuthenticatingRealm", "NexusAuthorizingRealm"), settings.realms());
    assertEquals(true, dao.findRealm("oidc").orElseThrow().enabled());
    assertEquals(0, dao.findRealm("oidc").orElseThrow().priority());
    assertEquals(true, dao.findRealm("local").orElseThrow().enabled());
    assertEquals(10, dao.findRealm("local").orElseThrow().priority());
    assertEquals(false, dao.findRealm("ldap").orElseThrow().enabled());
  }

  @Test
  void internalOidcSettingsPersistRuntimeAliases() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "oidc", "OIDC", "OIDC", false, 20,
        Map.of("source", "OIDC", "nexusRealm", "OidcRealm", "existing", "kept")));
    SecurityManagementController controller = new SecurityManagementController(new SecurityManagementService(dao));

    var settings = controller.updateOidcSettings(new OidcSettingsCommand(
        true,
        5,
        "OIDC",
        "https://issuer.example.com",
        null,
	        "https://issuer.example.com/.well-known/jwks.json",
	        "kkrepo",
	        null,
	        "secret",
	        "https://issuer.example.com/oauth2/authorize",
	        "https://issuer.example.com/oauth2/token",
	        "http://nexus.example.com/internal/security/oidc/callback",
	        "openid profile email groups",
	        "preferred_username",
	        "given_name",
        "family_name",
        "email",
        "groups",
        "roles",
        90,
        600,
        Map.of("extra", "kept")));

    Map<String, Object> attributes = dao.findRealm("oidc").orElseThrow().attributes();
    assertEquals(true, settings.enabled());
    assertEquals(5, settings.priority());
    assertEquals("OIDC", settings.source());
    assertEquals("https://issuer.example.com", settings.issuer());
    assertEquals("https://issuer.example.com", settings.issuerUri());
    assertEquals("https://issuer.example.com/.well-known/jwks.json", settings.jwksUri());
	    assertEquals("kkrepo", settings.audience());
	    assertEquals("kkrepo", settings.clientId());
	    assertEquals("********", settings.clientSecret());
	    assertFalse(settings.attributes().containsKey("clientSecret"));
	    assertEquals("https://issuer.example.com/oauth2/authorize", settings.authorizationEndpoint());
	    assertEquals("https://issuer.example.com/oauth2/token", settings.tokenEndpoint());
	    assertEquals("http://nexus.example.com/internal/security/oidc/callback", settings.redirectUri());
	    assertEquals("openid profile email groups", settings.scopes());
	    assertEquals("preferred_username", settings.userIdClaim());
    assertEquals("groups", settings.groupsClaim());
    assertEquals("roles", settings.rolesClaim());
    assertEquals(90, settings.clockSkewSeconds());
    assertEquals(600, settings.jwksCacheSeconds());
    assertEquals("OidcRealm", attributes.get("nexusRealm"));
    assertEquals("https://issuer.example.com/.well-known/jwks.json", attributes.get("jwksUri"));
    assertEquals("https://issuer.example.com/.well-known/jwks.json", attributes.get("jwkSetUri"));
	    assertEquals("kkrepo", attributes.get("audience"));
	    assertEquals("kkrepo", attributes.get("clientId"));
	    assertEquals("secret", attributes.get("clientSecret"));
	    assertEquals("https://issuer.example.com/oauth2/authorize", attributes.get("authorizationEndpoint"));
	    assertEquals("https://issuer.example.com/oauth2/token", attributes.get("tokenEndpoint"));
	    assertEquals("http://nexus.example.com/internal/security/oidc/callback", attributes.get("redirectUri"));
	    assertEquals("openid profile email groups", attributes.get("scopes"));
	    assertEquals("kept", attributes.get("existing"));
    assertEquals("kept", attributes.get("extra"));
  }

  @Test
  void internalOidcSettingsRequireJwksWhenEnabled() {
    FakeSecurityDao dao = new FakeSecurityDao();
    SecurityManagementController controller = new SecurityManagementController(new SecurityManagementService(dao));

    SecurityValidationException error = assertThrows(SecurityValidationException.class, () ->
        controller.updateOidcSettings(new OidcSettingsCommand(
            true,
            null,
            null,
            "https://issuer.example.com",
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
            null)));

    assertEquals("jwksUri is required when OIDC realm is enabled", error.getMessage());
    assertEquals(Optional.empty(), dao.findRealm("oidc"));
  }

  @Test
  void internalUiPermissionsExposeCurrentUserEffectivePermissionCatalog() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-users-read",
        "Users read",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:users:read")));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-repository-admin-maven2-all-add",
        "Maven admin add",
        null,
        "repository-admin",
        false,
        Map.of("format", "maven2", "repository", "*", "actions", "add")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    NexusSecurityUiController controller = uiController(dao);

    var permissions = controller.permissions(request(subject("admin")));

    assertTrue(permissions.stream().anyMatch(permission -> "nexus:users:read".equals(permission.id())));
    assertTrue(permissions.stream().anyMatch(permission -> "nexus:repository-admin:maven2:*:add".equals(permission.id())));
    assertTrue(permissions.stream().allMatch(permission -> Boolean.TRUE.equals(permission.permitted())));
  }

  @Test
  void extDirectGetUserReturnsRaptureCurrentUserShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    NexusExtDirectController controller = new NexusExtDirectController(
        new SecurityManagementService(dao),
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();
    var requestBody = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 6)
        .put("action", "rapture_Security")
        .put("method", "getUser");

    var response = mapper.convertValue(controller.invoke(requestBody, request(null)), Map.class);
    var result = (Map<?, ?>) response.get("result");
    var data = (Map<?, ?>) result.get("data");

    assertEquals("rpc", response.get("type"));
    assertEquals(6, response.get("tid"));
    assertEquals(true, result.get("success"));
    assertEquals("admin", data.get("id"));
    assertEquals(true, data.get("authenticated"));
    assertEquals(true, data.get("administrator"));
    assertEquals(List.of("NexusAuthenticatingRealm"), data.get("authenticatedRealms"));
  }

  @Test
  void raptureStateGetReturnsNexusPollingShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.anonymous(new SecurityAnonymousConfigRecord(true, "Local", "anonymous", "NexusAuthorizingRealm"));
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    ObjectMapper mapper = new ObjectMapper();
    NexusRaptureStateController controller = new NexusRaptureStateController(
        new SecurityManagementService(dao),
        new StubAuthenticationService(Optional.of(subject("admin"))),
        mapper);

    var response = mapper.convertValue(controller.getState(Map.of(), request(null)).getBody(), Map.class);
    var result = (Map<?, ?>) response.get("data");
    var state = (Map<?, ?>) result.get("data");
    var anonymous = (Map<?, ?>) state.get("anonymousUsername");
    var user = (Map<?, ?>) state.get("user");
    var userValue = (Map<?, ?>) user.get("value");
    var activeBundles = (Map<?, ?>) state.get("activeBundles");
    var browseableFormats = (Map<?, ?>) state.get("browseableformats");

    assertEquals("event", response.get("type"));
    assertEquals("rapture_State_get", response.get("name"));
    assertEquals(true, result.get("success"));
    assertEquals("anonymous", anonymous.get("value"));
    assertEquals("admin", userValue.get("id"));
    assertEquals(true, userValue.get("authenticated"));
    assertEquals(true, userValue.get("administrator"));
    assertEquals(List.of("NexusAuthenticatingRealm"), userValue.get("authenticatedRealms"));
    assertTrue(state.containsKey("serverId"));
    assertTrue(state.containsKey("uiSettings"));
    assertTrue(state.containsKey("status"));
    assertTrue(((List<?>) activeBundles.get("value"))
        .contains("org.sonatype.nexus.plugins.nexus-repository-nuget"));
    assertTrue(String.valueOf(browseableFormats.get("value")).contains("nuget"));
    assertTrue(String.valueOf(browseableFormats.get("value")).contains("rubygems"));
    assertTrue(String.valueOf(browseableFormats.get("value")).contains("yum"));

    var unchangedResponse = mapper.convertValue(controller.getState(
        Map.of("anonymousUsername", String.valueOf(anonymous.get("hash")), "user", String.valueOf(user.get("hash"))),
        request(null)).getBody(), Map.class);
    var unchangedState = (Map<?, ?>) ((Map<?, ?>) unchangedResponse.get("data")).get("data");

    assertFalse(unchangedState.containsKey("anonymousUsername"));
    assertFalse(unchangedState.containsKey("user"));
  }

  @Test
  void extDirectAuthenticateReturnsUserAndStoresSessionSubject() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        SecurityHashing.hashPassword("old-password"),
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    AuthenticatedSubject admin = subject("admin");
    StubAuthenticationService authentication = new StubAuthenticationService(
        Optional.empty(),
        Map.of(credentialsKey("admin", "old-password"), admin));
    NexusExtDirectController controller = new NexusExtDirectController(
        new SecurityManagementService(dao),
        authentication);
    ObjectMapper mapper = new ObjectMapper();
    HttpServletRequest request = request(null);
    var requestBody = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 7)
        .put("action", "rapture_Security")
        .put("method", "authenticate");
    requestBody.putArray("data")
        .add(base64("admin"))
        .add(base64("old-password"));

    var response = mapper.convertValue(controller.invoke(requestBody, request), Map.class);
    var result = (Map<?, ?>) response.get("result");
    var data = (Map<?, ?>) result.get("data");

    assertEquals(true, result.get("success"));
    assertEquals("admin", data.get("id"));
    assertEquals(true, data.get("authenticated"));
    assertEquals(true, data.get("administrator"));
    assertEquals(admin, authentication.storedSession);
    assertEquals(admin, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
  }

  @Test
  void raptureSessionCreateAuthenticatesFormParamsAndStoresSessionSubject() {
    AuthenticatedSubject admin = subject("admin");
    StubAuthenticationService authentication = new StubAuthenticationService(
        Optional.empty(),
        Map.of(credentialsKey("admin", "old-password"), admin));
    NexusRaptureSessionController controller = new NexusRaptureSessionController(authentication);
    HttpServletRequest request = request(null);

    var response = controller.create(base64("admin"), base64("old-password"), request);

    assertEquals(204, response.getStatusCode().value());
    assertEquals("DENY", response.getHeaders().getFirst("X-Frame-Options"));
    assertEquals(admin, authentication.storedSession);
    assertEquals(admin, request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE));
  }

  @Test
  void raptureSessionCreateRejectsInvalidFormParams() {
    StubAuthenticationService authentication = new StubAuthenticationService(
        Optional.empty(),
        Map.of());
    NexusRaptureSessionController controller = new NexusRaptureSessionController(authentication);

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
        controller.create(base64("admin"), base64("bad-password"), request(null)));

    assertEquals(403, error.getStatusCode().value());
  }

  @Test
  void extDirectGetPermissionsReturnsRaptureResponseShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-roles-read",
        "Roles read",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:roles:read")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();
    var requestBody = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 7)
        .put("action", "rapture_Security")
        .put("method", "getPermissions");

    var response = mapper.convertValue(controller.invoke(requestBody, request(null)), Map.class);
    var result = (Map<?, ?>) response.get("result");
    var data = (List<?>) result.get("data");

    assertEquals("rpc", response.get("type"));
    assertEquals(7, response.get("tid"));
    assertEquals(true, result.get("success"));
    assertTrue(data.stream().anyMatch(item -> ((Map<?, ?>) item).get("id").equals("nexus:roles:read")));
  }

  @Test
  void extDirectAuthenticationTokenIsRedeemedForPasswordChange() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        SecurityHashing.hashPassword("old-password"),
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-change-password",
        "Change password",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:userschangepw:create")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-change-password"));
    AuthenticatedSubject admin = subject("admin");
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(
            Optional.of(admin),
            Map.of(credentialsKey("admin", "old-password"), admin)),
        NexusAuthenticationTicketService.inMemory(300));
    ObjectMapper mapper = new ObjectMapper();

    var tokenCall = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 8)
        .put("action", "rapture_Security")
        .put("method", "authenticationToken");
    tokenCall.putArray("data")
        .add(base64("admin"))
        .add(base64("old-password"));
    var tokenResponse = mapper.convertValue(controller.invoke(tokenCall, request(null)), Map.class);
    var tokenResult = (Map<?, ?>) tokenResponse.get("result");
    String token = (String) tokenResult.get("data");

    assertEquals(true, tokenResult.get("success"));
    assertFalse(token.isBlank());

    var changeCall = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 9)
        .put("action", "coreui_User")
        .put("method", "changePassword");
    changeCall.putArray("data")
        .add(token)
        .add("admin")
        .add("new-password");
    var changeResponse = mapper.convertValue(controller.invoke(changeCall, request(null)), Map.class);
    var changeResult = (Map<?, ?>) changeResponse.get("result");

    assertEquals(true, changeResult.get("success"));
    assertTrue(SecurityHashing.verifyPassword(
        dao.findUser("Local", "admin").orElseThrow().passwordHash(),
        "new-password"));
  }

  @Test
  void currentUserAccountRestReadsAndUpdatesCurrentUser() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.upsertRole(new SecurityRoleRecord("nx-reader", "Local", "Reader", null, false, Map.of()));
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "alice",
        "Alice",
        "Original",
        "alice@example.com",
        SecurityHashing.hashPassword("old-password"),
        "ACTIVE",
        null,
        Map.of("kept", "yes")));
    dao.roles(1L, "nx-reader");
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusUserAccountController controller = new NexusUserAccountController(
        service,
        new StubAuthenticationService(Optional.empty()),
        NexusAuthenticationTicketService.inMemory(300));
    HttpServletRequest request = request(subject("alice"));

    var current = controller.current(request);
    var updated = controller.update(
        new NexusUserAccount("ignored", "Alice", "Updated", "alice.updated@example.com", true),
        request);

    assertEquals("alice", current.userId());
    assertEquals("Alice", current.firstName());
    assertEquals("Original", current.lastName());
    assertEquals(false, current.external());
    assertEquals("alice", updated.userId());
    assertEquals("Updated", updated.lastName());
    assertEquals("alice.updated@example.com", updated.email());
    assertEquals(false, updated.external());
    assertEquals(List.of("nx-reader"), dao.listUserRoleIds("Local", "alice"));
    assertEquals("yes", dao.findUser("Local", "alice").orElseThrow().attributes().get("kept"));
    assertTrue(SecurityHashing.verifyPassword(
        dao.findUser("Local", "alice").orElseThrow().passwordHash(),
        "old-password"));
  }

  @Test
  void currentUserAccountPasswordRedeemsSharedRaptureTicket() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        SecurityHashing.hashPassword("old-password"),
        "ACTIVE",
        null,
        Map.of()));
    AuthenticatedSubject admin = subject("admin");
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusAuthenticationTicketService tickets = NexusAuthenticationTicketService.inMemory(300);
    StubAuthenticationService authentication = new StubAuthenticationService(
        Optional.of(admin),
        Map.of(credentialsKey("admin", "old-password"), admin));
    NexusExtDirectController extDirect = new NexusExtDirectController(service, authentication, tickets);
    NexusUserAccountController accountController = new NexusUserAccountController(service, authentication, tickets);
    ObjectMapper mapper = new ObjectMapper();
    var tokenCall = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 10)
        .put("action", "rapture_Security")
        .put("method", "authenticationToken");
    tokenCall.putArray("data")
        .add(base64("admin"))
        .add(base64("old-password"));

    var tokenResponse = mapper.convertValue(extDirect.invoke(tokenCall, request(null)), Map.class);
    var tokenResult = (Map<?, ?>) tokenResponse.get("result");
    String token = (String) tokenResult.get("data");

    var response = accountController.changePassword(
        "admin",
        new NexusUserAccountPassword(token, "new-password"),
        request(admin));

    assertEquals(204, response.getStatusCode().value());
    assertTrue(SecurityHashing.verifyPassword(
        dao.findUser("Local", "admin").orElseThrow().passwordHash(),
        "new-password"));
  }

  @Test
  void wonderlandAuthenticateReturnsTicketRedeemableByPasswordChange() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        SecurityHashing.hashPassword("old-password"),
        "ACTIVE",
        null,
        Map.of()));
    AuthenticatedSubject admin = subject("admin");
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusAuthenticationTicketService tickets = NexusAuthenticationTicketService.inMemory(300);
    StubAuthenticationService authentication = new StubAuthenticationService(
        Optional.of(admin),
        Map.of(credentialsKey("admin", "old-password"), admin));
    NexusWonderlandAuthenticateController wonderland = new NexusWonderlandAuthenticateController(
        authentication,
        tickets);
    NexusUserAccountController accountController = new NexusUserAccountController(service, authentication, tickets);

    var ticket = wonderland.authenticate(
        new NexusAuthToken(base64("admin"), base64("old-password")),
        request(admin));
    var response = accountController.changePassword(
        "admin",
        new NexusUserAccountPassword(ticket.t(), "new-password"),
        request(admin));

    assertFalse(ticket.t().isBlank());
    assertEquals(204, response.getStatusCode().value());
    assertTrue(SecurityHashing.verifyPassword(
        dao.findUser("Local", "admin").orElseThrow().passwordHash(),
        "new-password"));
  }

  @Test
  void extDirectExternalUserRoleMappingsDoNotStoreExternalRoles() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.user(new SecurityUserRecord(
        2L,
        "LDAP",
        "alice",
        "Alice",
        null,
        "alice@example.com",
        null,
        "ACTIVE",
        "alice",
        Map.of("groups", List.of("ldap-team"))));
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.upsertRole(new SecurityRoleRecord("nx-deploy", "Local", "Deploy", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();

    var getCall = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 10)
        .put("action", "coreui_User")
        .put("method", "get");
    getCall.putArray("data")
        .add("alice")
        .add("LDAP");
    var getResponse = mapper.convertValue(controller.invoke(getCall, request(null)), Map.class);
    var getResult = (Map<?, ?>) getResponse.get("result");
    var getData = (Map<?, ?>) getResult.get("data");
    assertEquals(true, getResult.get("success"));
    assertEquals(List.of("ldap-team"), getData.get("externalRoles"));
    assertEquals(List.of("ldap-team"), getData.get("roles"));

    var updateCall = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 11)
        .put("action", "coreui_User")
        .put("method", "updateRoleMappings");
    var mappings = mapper.createObjectNode()
        .put("userId", "alice")
        .put("realm", "LDAP");
    mappings.putArray("roles")
        .add("ldap-team")
        .add("nx-deploy");
    updateCall.putArray("data").add(mappings);
    var updateResponse = mapper.convertValue(controller.invoke(updateCall, request(null)), Map.class);
    var updateResult = (Map<?, ?>) updateResponse.get("result");
    var updateData = (Map<?, ?>) updateResult.get("data");

    assertEquals(true, updateResult.get("success"));
    assertEquals(List.of("nx-deploy"), dao.listUserRoleIds("LDAP", "alice"));
    assertEquals(List.of("ldap-team"), updateData.get("externalRoles"));
    assertEquals(List.of("nx-deploy", "ldap-team"), updateData.get("roles"));

    var externalUpdateCall = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 12)
        .put("action", "coreui_User")
        .put("method", "update");
    var externalUpdate = mapper.createObjectNode()
        .put("userId", "alice")
        .put("realm", "LDAP")
        .put("firstName", "Mallory")
        .put("lastName", "Changed")
        .put("email", "mallory@example.com")
        .put("status", "disabled")
        .put("password", "new-password");
    externalUpdate.putArray("roles")
        .add("ldap-team")
        .add("nx-admin");
    externalUpdateCall.putArray("data").add(externalUpdate);
    var externalUpdateResponse = mapper.convertValue(controller.invoke(externalUpdateCall, request(null)), Map.class);
    var externalUpdateResult = (Map<?, ?>) externalUpdateResponse.get("result");
    var externalUpdateData = (Map<?, ?>) externalUpdateResult.get("data");
    var storedExternalUser = dao.findUser("LDAP", "alice").orElseThrow();

    assertEquals(true, externalUpdateResult.get("success"));
    assertEquals("Alice", storedExternalUser.firstName());
    assertEquals("alice@example.com", storedExternalUser.email());
    assertEquals("ACTIVE", storedExternalUser.status());
    assertEquals(List.of("nx-admin"), dao.listUserRoleIds("LDAP", "alice"));
    assertEquals(List.of("ldap-team"), externalUpdateData.get("externalRoles"));
    assertEquals(List.of("nx-admin", "ldap-team"), externalUpdateData.get("roles"));
  }

  @Test
  void extDirectRemoveRejectsAnonymousAndCurrentUserLikeNexusCoreUi() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.user(new SecurityUserRecord(
        2L,
        "Local",
        "anonymous",
        "Anonymous",
        null,
        "anonymous@example.invalid",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.anonymous(new SecurityAnonymousConfigRecord(
        true,
        "Local",
        "anonymous",
        "NexusAuthorizingRealm"));
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    NexusExtDirectController controller = new NexusExtDirectController(
        new SecurityManagementService(dao),
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();

    var currentRemove = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 12)
        .put("action", "coreui_User")
        .put("method", "remove");
    currentRemove.putArray("data")
        .add("admin")
        .add("Local");
    var currentResponse = mapper.convertValue(controller.invoke(currentRemove, request(null)), Map.class);
    var currentResult = (Map<?, ?>) currentResponse.get("result");

    var anonymousRemove = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 13)
        .put("action", "coreui_User")
        .put("method", "remove");
    anonymousRemove.putArray("data")
        .add("anonymous")
        .add("Local");
    var anonymousResponse = mapper.convertValue(controller.invoke(anonymousRemove, request(null)), Map.class);
    var anonymousResult = (Map<?, ?>) anonymousResponse.get("result");

    assertEquals(false, currentResult.get("success"));
    assertEquals(
        "User admin cannot be deleted, since is the user currently logged into the application",
        currentResult.get("message"));
    assertEquals(false, anonymousResult.get("success"));
    assertEquals(
        "User anonymous cannot be deleted, since is marked as the Anonymous user",
        anonymousResult.get("message"));
    assertEquals(true, dao.findUser("Local", "admin").isPresent());
    assertEquals(true, dao.findUser("Local", "anonymous").isPresent());
  }

  @Test
  void extDirectRealmSettingsReadAndUpdateUseCoreUiShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0,
        Map.of("source", "Local", "nexusRealm", "NexusAuthenticatingRealm")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", false, 10,
        Map.of("source", "LDAP", "nexusRealm", "LdapRealm")));
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();
    var requestBody = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 8)
        .put("action", "coreui_RealmSettings")
        .put("method", "update");
    var payload = mapper.createObjectNode();
    payload.putArray("realms").add("LdapRealm").add("NexusAuthenticatingRealm");
    requestBody.putArray("data").add(payload);

    var response = mapper.convertValue(controller.invoke(requestBody, request(null)), Map.class);
    var result = (Map<?, ?>) response.get("result");
    var data = (Map<?, ?>) result.get("data");

    assertEquals("rpc", response.get("type"));
    assertEquals(true, result.get("success"));
    assertEquals(List.of("LdapRealm", "NexusAuthenticatingRealm", "NexusAuthorizingRealm"), data.get("realms"));
    assertEquals(true, dao.findRealm("ldap").orElseThrow().enabled());
    assertEquals(0, dao.findRealm("ldap").orElseThrow().priority());
  }

  @Test
  void extDirectLdapServerMethodsUseNexusCoreUiShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.realm(new SecurityRealmRecord(1L, "local", "LOCAL", "Local", true, 0,
        Map.of("source", "Local", "nexusRealm", "NexusAuthenticatingRealm")));
    dao.realm(new SecurityRealmRecord(2L, "ldap", "LDAP", "LDAP", false, 10,
        Map.of("source", "LDAP", "nexusRealm", "LdapRealm")));
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();

    var create = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 30)
        .put("action", "ldap_LdapServer")
        .put("method", "create");
    create.putArray("data").add(mapper.createObjectNode()
        .put("name", "corp")
        .put("protocol", "ldap")
        .put("host", "ldap.example.com")
        .put("port", 389)
        .put("searchBase", "dc=example,dc=com")
        .put("authScheme", "simple")
        .put("authUsername", "cn=manager,dc=example,dc=com")
        .put("authPassword", "secret")
        .put("connectionTimeout", 30)
        .put("userBaseDn", "ou=People")
        .put("userSubtree", true)
        .put("userObjectClass", "inetOrgPerson")
        .put("userIdAttribute", "uid")
        .put("userRealNameAttribute", "cn")
        .put("userEmailAddressAttribute", "mail")
        .put("ldapGroupsAsRoles", true)
        .put("groupType", "static")
        .put("groupBaseDn", "ou=Groups")
        .put("groupSubtree", true)
        .put("groupObjectClass", "groupOfNames")
        .put("groupIdAttribute", "cn")
        .put("groupMemberAttribute", "member"));
    var createResponse = mapper.convertValue(controller.invoke(create, request(null)), Map.class);
    var createResult = (Map<?, ?>) createResponse.get("result");
    var created = (Map<?, ?>) createResult.get("data");
    assertEquals(true, createResult.get("success"));
    assertEquals("corp", created.get("name"));
    assertEquals("ldap://ldap.example.com:389/dc=example,dc=com", created.get("url"));
    Map<String, Object> ldapAttributes = dao.findRealm("ldap").orElseThrow().attributes();
    assertEquals("LDAP", ldapAttributes.get("source"));
    assertEquals("LdapRealm", ldapAttributes.get("nexusRealm"));
    assertEquals("ldap://ldap.example.com:389/dc=example,dc=com", ldapAttributes.get("url"));
    assertEquals("cn=manager,dc=example,dc=com", ldapAttributes.get("managerDn"));
    assertEquals("ou=People", ldapAttributes.get("userSearchBase"));
    assertEquals("(&(objectClass=inetOrgPerson)(uid={0}))", ldapAttributes.get("userSearchFilter"));
    assertEquals("(&(objectClass=groupOfNames)(member={1}))", ldapAttributes.get("groupSearchFilter"));
    assertEquals(30000, ldapAttributes.get("timeoutMs"));

    var read = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 31)
        .put("action", "ldap_LdapServer")
        .put("method", "read");
    read.putArray("data");
    var readResponse = mapper.convertValue(controller.invoke(read, request(null)), Map.class);
    var readResult = (Map<?, ?>) readResponse.get("result");
    var servers = (List<?>) readResult.get("data");
    assertEquals(true, readResult.get("success"));
    assertEquals(1, servers.size());
    assertEquals("ldap.example.com", ((Map<?, ?>) servers.get(0)).get("host"));

    var templates = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 32)
        .put("action", "ldap_LdapServer")
        .put("method", "readTemplates");
    templates.putArray("data");
    var templatesResponse = mapper.convertValue(controller.invoke(templates, request(null)), Map.class);
    var templatesResult = (Map<?, ?>) templatesResponse.get("result");
    var templateData = (List<?>) templatesResult.get("data");
    assertEquals(true, templatesResult.get("success"));
    assertTrue(templateData.stream().anyMatch(template -> "Generic LDAP".equals(((Map<?, ?>) template).get("name"))));

    var update = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 33)
        .put("action", "ldap_LdapServer")
        .put("method", "update");
    update.putArray("data").add(mapper.createObjectNode()
        .put("id", "ldap")
        .put("name", "corp-updated")
        .put("protocol", "ldaps")
        .put("host", "ldap2.example.com")
        .put("port", 636)
        .put("searchBase", "dc=example,dc=com")
        .put("authScheme", "simple")
        .put("authUsername", "cn=manager,dc=example,dc=com")
        .put("connectionTimeout", 45)
        .put("userBaseDn", "ou=People")
        .put("userObjectClass", "person")
        .put("userIdAttribute", "uid")
        .put("ldapGroupsAsRoles", false));
    var updateResponse = mapper.convertValue(controller.invoke(update, request(null)), Map.class);
    var updateResult = (Map<?, ?>) updateResponse.get("result");
    var updated = (Map<?, ?>) updateResult.get("data");
    assertEquals(true, updateResult.get("success"));
    assertEquals("corp-updated", updated.get("name"));
    assertEquals("ldaps://ldap2.example.com:636/dc=example,dc=com", updated.get("url"));
    assertEquals(45000, dao.findRealm("ldap").orElseThrow().attributes().get("timeoutMs"));

    var changeOrder = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 34)
        .put("action", "ldap_LdapServer")
        .put("method", "changeOrder");
    changeOrder.putArray("data").addArray().add("ldap");
    var orderResponse = mapper.convertValue(controller.invoke(changeOrder, request(null)), Map.class);
    assertEquals(true, ((Map<?, ?>) orderResponse.get("result")).get("success"));
    assertEquals(1, dao.findRealm("ldap").orElseThrow().attributes().get("order"));

    var verify = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 35)
        .put("action", "ldap_LdapServer")
        .put("method", "verifyConnection");
    verify.putArray("data").add(mapper.createObjectNode()
        .put("protocol", "ldap")
        .put("host", "ldap.example.com")
        .put("port", 389));
    var verifyResponse = mapper.convertValue(controller.invoke(verify, request(null)), Map.class);
    assertEquals(true, ((Map<?, ?>) verifyResponse.get("result")).get("success"));

    var remove = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 36)
        .put("action", "ldap_LdapServer")
        .put("method", "remove");
    remove.putArray("data").add("ldap");
    var removeResponse = mapper.convertValue(controller.invoke(remove, request(null)), Map.class);
    assertEquals(true, ((Map<?, ?>) removeResponse.get("result")).get("success"));
    assertEquals(false, dao.findRealm("ldap").orElseThrow().enabled());

    var afterRemove = mapper.convertValue(controller.invoke(read, request(null)), Map.class);
    var afterRemoveResult = (Map<?, ?>) afterRemove.get("result");
    assertEquals(List.of(), afterRemoveResult.get("data"));
  }

  @Test
  void extDirectAnonymousSettingsAndSelectorsUseCoreUiShape() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.privilege(new SecurityPrivilegeRecord(
        "team-a-read",
        "Team A read",
        null,
        "repository-content-selector",
        false,
        Map.of("contentSelector", "team-a", "format", "maven2", "repository", "*", "actions", List.of("read"))));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        1L,
        "team-a",
        "team-a",
        "*",
        "path =~ \"^/team-a/.*\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector", "type", "csel", "description", "Team A")));
    dao.repositoryTarget(new SecurityRepositoryTargetRecord(
        2L,
        "maven-target",
        "Maven target",
        "maven2",
        null,
        Map.of("patterns", List.of("/org/example/.*")),
        Map.of("source", "nexus-repository-target")));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();

    var anonymousUpdate = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 20)
        .put("action", "coreui_AnonymousSettings")
        .put("method", "update");
    anonymousUpdate.putArray("data").add(mapper.createObjectNode()
        .put("enabled", true)
        .put("userId", "anonymous")
        .put("realmName", "NexusAuthorizingRealm"));
    var anonymousResponse = mapper.convertValue(controller.invoke(anonymousUpdate, request(null)), Map.class);
    var anonymousResult = (Map<?, ?>) anonymousResponse.get("result");
    var anonymousData = (Map<?, ?>) anonymousResult.get("data");
    assertEquals(true, anonymousResult.get("success"));
    assertEquals(true, anonymousData.get("enabled"));
    assertEquals("anonymous", anonymousData.get("userId"));

    var selectorRead = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 21)
        .put("action", "coreui_Selector")
        .put("method", "read");
    selectorRead.putArray("data");
    var readResponse = mapper.convertValue(controller.invoke(selectorRead, request(null)), Map.class);
    var readResult = (Map<?, ?>) readResponse.get("result");
    var selectors = (List<?>) readResult.get("data");
    assertEquals(true, readResult.get("success"));
    assertEquals(1, selectors.size());
    var teamSelector = (Map<?, ?>) selectors.get(0);
    assertEquals("team-a", teamSelector.get("id"));
    assertEquals(1, teamSelector.get("usedByCount"));
    assertEquals(List.of("Team A read"), teamSelector.get("usedBy"));

    var selectorCreate = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 22)
        .put("action", "coreui_Selector")
        .put("method", "create");
    selectorCreate.putArray("data").add(mapper.createObjectNode()
        .put("name", "team-b")
        .put("type", "csel")
        .put("description", "Team B")
        .put("expression", "path =~ \"^/team-b/.*\""));
    var createResponse = mapper.convertValue(controller.invoke(selectorCreate, request(null)), Map.class);
    var createResult = (Map<?, ?>) createResponse.get("result");
    var createdSelector = (Map<?, ?>) createResult.get("data");
    assertEquals(true, createResult.get("success"));
    assertEquals("team-b", createdSelector.get("id"));
    assertEquals("Team B", createdSelector.get("description"));

    var selectorUpdate = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 23)
        .put("action", "coreui_Selector")
        .put("method", "update");
    selectorUpdate.putArray("data").add(mapper.createObjectNode()
        .put("name", "team-b")
        .put("type", "csel")
        .put("description", "Team B updated")
        .put("expression", "path =~ \"^/team-b/v2/.*\""));
    var updateResponse = mapper.convertValue(controller.invoke(selectorUpdate, request(null)), Map.class);
    var updateResult = (Map<?, ?>) updateResponse.get("result");
    var updatedSelector = (Map<?, ?>) updateResult.get("data");
    assertEquals(true, updateResult.get("success"));
    assertEquals("Team B updated", updatedSelector.get("description"));
    assertEquals("path =~ \"^/team-b/v2/.*\"", updatedSelector.get("expression"));

    var selectorReferences = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 24)
        .put("action", "coreui_Selector")
        .put("method", "readReferences");
    selectorReferences.putArray("data");
    var referencesResponse = mapper.convertValue(controller.invoke(selectorReferences, request(null)), Map.class);
    var referencesResult = (Map<?, ?>) referencesResponse.get("result");
    var references = (List<?>) referencesResult.get("data");
    assertEquals(true, referencesResult.get("success"));
    assertTrue(references.stream().anyMatch(reference -> "team-b".equals(((Map<?, ?>) reference).get("id"))));
    assertFalse(references.stream().anyMatch(reference -> "maven-target".equals(((Map<?, ?>) reference).get("id"))));

    var selectorRemove = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 28)
        .put("action", "coreui_Selector")
        .put("method", "remove");
    selectorRemove.putArray("data").add("team-b");
    var removeResponse = mapper.convertValue(controller.invoke(selectorRemove, request(null)), Map.class);
    var removeResult = (Map<?, ?>) removeResponse.get("result");
    assertEquals(true, removeResult.get("success"));
    assertEquals(Optional.empty(), dao.findRepositoryTarget("team-b"));

    var removeUsedSelector = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 29)
        .put("action", "coreui_Selector")
        .put("method", "remove");
    removeUsedSelector.putArray("data").add("team-a");
    var removeUsedResponse = mapper.convertValue(controller.invoke(removeUsedSelector, request(null)), Map.class);
    var removeUsedResult = (Map<?, ?>) removeUsedResponse.get("result");
    assertEquals(false, removeUsedResult.get("success"));
    assertTrue(String.valueOf(removeUsedResult.get("message")).contains("Team A read"));
  }

  @Test
  void extDirectCoreUiSecurityManagementMethodsUseNexusShapes() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-users-read",
        "Users read",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:users:read")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))));
    ObjectMapper mapper = new ObjectMapper();

    var roleCreate = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 9)
        .put("action", "coreui_Role")
        .put("method", "create");
    var role = mapper.createObjectNode()
        .put("id", "team-role")
        .put("source", "Nexus")
        .put("name", "Team Role")
        .put("description", "Team role");
    role.putArray("privileges").add("nx-users-read");
    role.putArray("roles");
    roleCreate.putArray("data").add(role);
    var roleResponse = mapper.convertValue(controller.invoke(roleCreate, request(null)), Map.class);
    var roleResult = (Map<?, ?>) roleResponse.get("result");
    var roleData = (Map<?, ?>) roleResult.get("data");
    assertEquals(true, roleResult.get("success"));
    assertEquals("Nexus", roleData.get("source"));
    assertEquals("team-role", roleData.get("id"));

    var roleReadFromSource = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 30)
        .put("action", "coreui_Role")
        .put("method", "readFromSource");
    roleReadFromSource.putArray("data").add("LDAP");
    var roleReadFromSourceResponse = mapper.convertValue(controller.invoke(roleReadFromSource, request(null)), Map.class);
    var roleReadFromSourceResult = (Map<?, ?>) roleReadFromSourceResponse.get("result");
    var rolesFromLdapSource = (List<?>) roleReadFromSourceResult.get("data");
    assertEquals(true, roleReadFromSourceResult.get("success"));
    assertTrue(rolesFromLdapSource.stream()
        .anyMatch(row -> "team-role".equals(((Map<?, ?>) row).get("id"))));

    var userCreate = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 10)
        .put("action", "coreui_User")
        .put("method", "create");
    var user = mapper.createObjectNode()
        .put("userId", "bob")
        .put("firstName", "Bob")
        .put("email", "bob@example.com")
        .put("status", "active")
        .put("password", "secret");
    user.putArray("roles").add("team-role");
    userCreate.putArray("data").add(user);
    var userResponse = mapper.convertValue(controller.invoke(userCreate, request(null)), Map.class);
    var userResult = (Map<?, ?>) userResponse.get("result");
    var userData = (Map<?, ?>) userResult.get("data");
    assertEquals(true, userResult.get("success"));
    assertEquals("bob", userData.get("userId"));
    assertEquals(List.of("team-role"), userData.get("roles"));

    var privilegeCreate = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 11)
        .put("action", "coreui_Privilege")
        .put("method", "create");
    var privilege = mapper.createObjectNode()
        .put("name", "nx-test-read")
        .put("type", "wildcard")
        .put("description", "Test read");
    privilege.putObject("properties").put("pattern", "nexus:test:read");
    privilegeCreate.putArray("data").add(privilege);
    var privilegeResponse = mapper.convertValue(controller.invoke(privilegeCreate, request(null)), Map.class);
    var privilegeResult = (Map<?, ?>) privilegeResponse.get("result");
    var privilegeData = (Map<?, ?>) privilegeResult.get("data");
    assertEquals(true, privilegeResult.get("success"));
    assertEquals("nx-test-read", privilegeData.get("id"));
    assertEquals("nexus:test:read", privilegeData.get("permission"));

    var privilegeRead = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 12)
        .put("action", "coreui_Privilege")
        .put("method", "read");
    var parameters = mapper.createObjectNode()
        .put("start", 0)
        .put("limit", 10);
    parameters.putArray("filter").add(mapper.createObjectNode().put("property", "filter").put("value", "test"));
    privilegeRead.putArray("data").add(parameters);
    var readResponse = mapper.convertValue(controller.invoke(privilegeRead, request(null)), Map.class);
    var readResult = (Map<?, ?>) readResponse.get("result");
    var readData = (List<?>) readResult.get("data");
    assertEquals(true, readResult.get("success"));
    assertEquals(1, readResult.get("total"));
    assertEquals("nx-test-read", ((Map<?, ?>) readData.get(0)).get("id"));

    var privilegeTypes = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 13)
        .put("action", "coreui_Privilege")
        .put("method", "readTypes");
    privilegeTypes.putArray("data");
    var typesResponse = mapper.convertValue(controller.invoke(privilegeTypes, request(null)), Map.class);
    var typesResult = (Map<?, ?>) typesResponse.get("result");
    var typesData = (List<?>) typesResult.get("data");
    assertEquals(true, typesResult.get("success"));
    var repositoryViewType = typesData.stream()
        .map(Map.class::cast)
        .filter(type -> "repository-view".equals(type.get("id")))
        .findFirst()
        .orElseThrow();
    assertFalse(((List<?>) repositoryViewType.get("formFields")).isEmpty());
    var contentSelectorType = typesData.stream()
        .map(Map.class::cast)
        .filter(type -> "repository-content-selector".equals(type.get("id")))
        .findFirst()
        .orElseThrow();
    assertEquals(List.of("contentSelector", "repository", "actions"),
        ((List<?>) contentSelectorType.get("formFields")).stream()
            .map(Map.class::cast)
            .map(field -> field.get("id"))
            .toList());
    var contentSelectorRepositoryField = ((List<?>) contentSelectorType.get("formFields")).stream()
        .map(Map.class::cast)
        .filter(field -> "repository".equals(field.get("id")))
        .findFirst()
        .orElseThrow();
    assertEquals("combobox", contentSelectorRepositoryField.get("type"));
    assertEquals(
        "coreui_Repository.readReferencesAddingEntriesForAllFormats",
        contentSelectorRepositoryField.get("storeApi"));
  }

  @Test
  void extDirectRepositoryReferencesUseNexusStoreShapesForPrivilegeForms() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.user(new SecurityUserRecord(
        1L,
        "Local",
        "admin",
        "Admin",
        null,
        "admin@example.com",
        "$shiro1$hash",
        "ACTIVE",
        null,
        Map.of()));
    dao.roles(1L, "nx-admin");
    dao.upsertRole(new SecurityRoleRecord("nx-admin", "Local", "Admin", null, false, Map.of()));
    dao.privilege(new SecurityPrivilegeRecord(
        "nx-all",
        "All",
        null,
        "wildcard",
        false,
        Map.of("pattern", "nexus:*")));
    dao.replaceRolePrivileges("nx-admin", List.of("nx-all"));
    SecurityManagementService service = new SecurityManagementService(dao);
    NexusExtDirectController controller = new NexusExtDirectController(
        service,
        new StubAuthenticationService(Optional.of(subject("admin"))),
        new FakeRepositoryService(List.of(
            new RepositoryView(
                1L,
                "maven-releases",
                "maven2-hosted",
                RepositoryFormat.MAVEN2,
                RepositoryType.HOSTED,
                true,
                "Local",
                true,
                "/repository/maven-releases/",
                new HostedSettings("ALLOW_ONCE", "RELEASE", "STRICT"),
                null,
                null,
                null,
                null,
                null),
            new RepositoryView(
                2L,
                "npm-group",
                "npm-group",
                RepositoryFormat.NPM,
                RepositoryType.GROUP,
                true,
                "Local",
                true,
                "/repository/npm-group/",
                null,
                null,
                null,
                null,
                null,
                null))),
        NexusAuthenticationTicketService.inMemory(300));
    ObjectMapper mapper = new ObjectMapper();

    var allRepositories = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 31)
        .put("action", "coreui_Repository")
        .put("method", "readReferencesAddingEntryForAll");
    allRepositories.putArray("data").add(mapper.createObjectNode());
    var allRepositoriesResponse = mapper.convertValue(controller.invoke(allRepositories, request(null)), Map.class);
    var allRepositoriesResult = (Map<?, ?>) allRepositoriesResponse.get("result");
    var allRepositoriesData = (List<?>) allRepositoriesResult.get("data");
    assertEquals(true, allRepositoriesResult.get("success"));
    assertTrue(allRepositoriesData.stream()
        .anyMatch(row -> "maven-releases".equals(((Map<?, ?>) row).get("id"))));
    assertTrue(allRepositoriesData.stream()
        .anyMatch(row -> "*".equals(((Map<?, ?>) row).get("id"))
            && "(All Repositories)".equals(((Map<?, ?>) row).get("name"))));
    Map<?, ?> mavenReleases = allRepositoriesData.stream()
        .map(Map.class::cast)
        .filter(row -> "maven-releases".equals(row.get("id")))
        .findFirst()
        .orElseThrow();
    assertEquals("hosted", mavenReleases.get("type"));
    assertEquals("maven2", mavenReleases.get("format"));
    assertEquals("RELEASE", mavenReleases.get("versionPolicy"));

    var allFormatRepositories = mapper.createObjectNode()
        .put("type", "rpc")
        .put("tid", 32)
        .put("action", "coreui_Repository")
        .put("method", "readReferencesAddingEntriesForAllFormats");
    allFormatRepositories.putArray("data").add(mapper.createObjectNode());
    var allFormatResponse = mapper.convertValue(controller.invoke(allFormatRepositories, request(null)), Map.class);
    var allFormatResult = (Map<?, ?>) allFormatResponse.get("result");
    var allFormatData = (List<?>) allFormatResult.get("data");
    assertEquals(true, allFormatResult.get("success"));
    assertTrue(allFormatData.stream()
        .anyMatch(row -> "*-maven2".equals(((Map<?, ?>) row).get("id"))
            && "(All maven2 Repositories)".equals(((Map<?, ?>) row).get("name"))));
    assertTrue(allFormatData.stream()
        .anyMatch(row -> "*-npm".equals(((Map<?, ?>) row).get("id"))
            && "(All npm Repositories)".equals(((Map<?, ?>) row).get("name"))));
  }

  private static NexusSecurityRestController controller(FakeSecurityDao dao) {
    return new NexusSecurityRestController(new SecurityManagementService(dao));
  }

  private static NexusAnonymousSettingsController anonymousController(FakeSecurityDao dao) {
    return new NexusAnonymousSettingsController(new SecurityManagementService(dao));
  }

  private static NexusSecurityUiController uiController(FakeSecurityDao dao) {
    return new NexusSecurityUiController(new SecurityManagementService(dao));
  }

  private static AuthenticatedSubject subject(String userId) {
    return new AuthenticatedSubject(
        "Local",
        userId,
        "local",
        null,
        new PermissionSubject("Local", userId, Set.of(), null));
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String credentialsKey(String username, String password) {
    return username + "\0" + password;
  }

  private static HttpServletRequest request(AuthenticatedSubject subject) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (subject != null) {
      attributes.put(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    }
    return (HttpServletRequest) Proxy.newProxyInstance(
        NexusSecurityRestControllerTest.class.getClassLoader(),
        new Class<?>[] {HttpServletRequest.class},
        (proxy, invoked, args) -> switch (invoked.getName()) {
          case "getDispatcherType" -> DispatcherType.REQUEST;
          case "getAttribute" -> attributes.get(String.valueOf(args[0]));
          case "setAttribute" -> {
            attributes.put(String.valueOf(args[0]), args[1]);
            yield null;
          }
          case "removeAttribute" -> {
            attributes.remove(String.valueOf(args[0]));
            yield null;
          }
          case "toString" -> "GET /service/extdirect";
          default -> primitiveDefault(invoked.getReturnType());
        });
  }

  private static Object primitiveDefault(Class<?> type) {
    if (boolean.class.equals(type)) {
      return false;
    }
    if (int.class.equals(type) || long.class.equals(type) || short.class.equals(type) || byte.class.equals(type)) {
      return 0;
    }
    if (char.class.equals(type)) {
      return '\0';
    }
    return null;
  }

  private static class FakeRepositoryService extends RepositoryService {
    private final List<RepositoryView> repositories;

    private FakeRepositoryService(List<RepositoryView> repositories) {
      super(null, null, null, null, "/repository");
      this.repositories = repositories;
    }

    @Override
    public List<RepositoryView> list() {
      return repositories;
    }
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final Optional<AuthenticatedSubject> subject;
    private final Map<String, AuthenticatedSubject> credentials;
    private AuthenticatedSubject storedSession;

    private StubAuthenticationService(Optional<AuthenticatedSubject> subject) {
      this(subject, Map.of());
    }

    private StubAuthenticationService(
        Optional<AuthenticatedSubject> subject,
        Map<String, AuthenticatedSubject> credentials) {
      super(new SecurityDao(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
      this.credentials = credentials;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(HttpServletRequest request) {
      return subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticateCredentials(String username, String password) {
      return Optional.ofNullable(credentials.get(credentialsKey(username, password)));
    }

    @Override
    public void storeSessionSubject(HttpServletRequest request, AuthenticatedSubject subject) {
      storedSession = subject;
    }
  }

  private static class FakeSecurityDao extends SecurityDao {
    private final List<SecurityRealmRecord> realms = new ArrayList<>();
    private final Map<String, SecurityUserRecord> users = new LinkedHashMap<>();
    private final Map<String, SecurityRoleRecord> roles = new LinkedHashMap<>();
    private final Map<String, SecurityPrivilegeRecord> privileges = new LinkedHashMap<>();
    private final Map<Long, List<String>> userRoles = new LinkedHashMap<>();
    private final Map<String, List<String>> rolePrivileges = new LinkedHashMap<>();
    private final Map<String, List<String>> roleChildren = new LinkedHashMap<>();
    private final Map<String, SecurityRepositoryTargetRecord> repositoryTargets = new LinkedHashMap<>();
    private final Map<Long, ApiKeyRecord> apiKeys = new LinkedHashMap<>();
    private SecurityAnonymousConfigRecord anonymousConfig;
    private long nextUserId = 100;
    private long nextApiKeyId = 1;
    private int deletedUsers;

    private FakeSecurityDao() {
      super(null, null);
    }

    private void realm(SecurityRealmRecord realm) {
      realms.add(realm);
    }

    private void user(SecurityUserRecord user) {
      users.put(user.source() + "/" + user.userId(), user);
      nextUserId = Math.max(nextUserId, user.id() + 1);
    }

    private void roles(long userId, String... roleIds) {
      userRoles.put(userId, List.of(roleIds));
    }

    private void privilege(SecurityPrivilegeRecord privilege) {
      privileges.put(privilege.privilegeId(), privilege);
    }

    private void repositoryTarget(SecurityRepositoryTargetRecord target) {
      repositoryTargets.put(target.targetId(), target);
    }

    private void anonymous(SecurityAnonymousConfigRecord config) {
      anonymousConfig = config;
    }

    private void apiKey(ApiKeyRecord record) {
      apiKeys.put(record.id(), record);
      nextApiKeyId = Math.max(nextApiKeyId, record.id() + 1);
    }

    @Override
    public List<SecurityRealmRecord> listRealms() {
      return realms;
    }

    @Override
    public Optional<SecurityRealmRecord> findRealm(String realmId) {
      return realms.stream()
          .filter(realm -> realm.realmId().equals(realmId))
          .findFirst();
    }

    @Override
    public void upsertRealm(SecurityRealmRecord record) {
      for (int i = 0; i < realms.size(); i++) {
        SecurityRealmRecord existing = realms.get(i);
        if (existing.realmId().equals(record.realmId())) {
          realms.set(i, new SecurityRealmRecord(
              existing.id(),
              record.realmId(),
              record.type(),
              record.name(),
              record.enabled(),
              record.priority(),
              record.attributes()));
          return;
        }
      }
      realms.add(new SecurityRealmRecord(
          (long) (realms.size() + 1),
          record.realmId(),
          record.type(),
          record.name(),
          record.enabled(),
          record.priority(),
          record.attributes()));
    }

    @Override
    public void updateRealmConfig(List<String> activeRealmIds) {
      // no-op for controller tests; enabled flags and priorities are stored on realm rows.
    }

    @Override
    public Optional<SecurityUserRecord> findUser(String source, String userId) {
      return Optional.ofNullable(users.get(source + "/" + userId));
    }

    @Override
    public long insertUser(SecurityUserRecord record) {
      long id = nextUserId++;
      user(new SecurityUserRecord(
          id,
          record.source(),
          record.userId(),
          record.firstName(),
          record.lastName(),
          record.email(),
          record.passwordHash(),
          record.status(),
          record.externalId(),
          record.attributes()));
      return id;
    }

    @Override
    public void updateUser(SecurityUserRecord record) {
      user(record);
    }

    @Override
    public List<SecurityUserRecord> listUsers() {
      return new ArrayList<>(users.values());
    }

    @Override
    public List<String> listUserRoleIds(long userNumericId) {
      return userRoles.getOrDefault(userNumericId, List.of());
    }

    @Override
    public void replaceUserRoles(long userNumericId, List<String> roleIds) {
      userRoles.put(userNumericId, roleIds == null ? List.of() : List.copyOf(roleIds));
    }

    @Override
    public List<String> listUserRoleIds(String source, String userId) {
      return findUser(source, userId)
          .map(user -> userRoles.getOrDefault(user.id(), List.of()))
          .orElse(List.of());
    }

    @Override
    public Optional<SecurityRoleRecord> findRole(String roleId) {
      return Optional.ofNullable(roles.get(roleId));
    }

    @Override
    public List<SecurityRoleRecord> listRoles() {
      return new ArrayList<>(roles.values());
    }

    @Override
    public void upsertRole(SecurityRoleRecord record) {
      roles.put(record.roleId(), record);
    }

    @Override
    public int deleteRole(String roleId) {
      return roles.remove(roleId) == null ? 0 : 1;
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
    public List<SecurityPrivilegeRecord> listPrivilegesForRoles(List<String> roleIds) {
      if (roleIds == null) {
        return List.of();
      }
      return roleIds.stream()
          .flatMap(roleId -> rolePrivileges.getOrDefault(roleId, List.of()).stream())
          .map(privileges::get)
          .filter(privilege -> privilege != null)
          .distinct()
          .toList();
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
    public Optional<SecurityPrivilegeRecord> findPrivilege(String privilegeId) {
      return Optional.ofNullable(privileges.get(privilegeId));
    }

    @Override
    public void upsertPrivilege(SecurityPrivilegeRecord record) {
      privileges.put(record.privilegeId(), record);
    }

    @Override
    public List<SecurityPrivilegeRecord> listPrivileges() {
      return new ArrayList<>(privileges.values());
    }

    @Override
    public int deletePrivilege(String privilegeId) {
      return privileges.remove(privilegeId) == null ? 0 : 1;
    }

    @Override
    public List<ApiKeyRecord> listApiKeys() {
      return new ArrayList<>(apiKeys.values());
    }

    @Override
    public List<ApiKeyRecord> listApiKeysForOwner(String ownerSource, String ownerUserId) {
      return apiKeys.values().stream()
          .filter(apiKey -> ownerSource.equals(apiKey.ownerSource()))
          .filter(apiKey -> ownerUserId.equals(apiKey.ownerUserId()))
          .toList();
    }

    @Override
    public Optional<ApiKeyRecord> findApiKey(long id) {
      return Optional.ofNullable(apiKeys.get(id));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKeyForOwner(long id, String ownerSource, String ownerUserId) {
      return findApiKey(id)
          .filter(apiKey -> ownerSource.equals(apiKey.ownerSource()))
          .filter(apiKey -> ownerUserId.equals(apiKey.ownerUserId()));
    }

    @Override
    public Optional<ApiKeyRecord> findApiKey(String domain, String ownerSource, String ownerUserId) {
      return apiKeys.values().stream()
          .filter(apiKey -> domain.equals(apiKey.domain()))
          .filter(apiKey -> ownerSource.equals(apiKey.ownerSource()))
          .filter(apiKey -> ownerUserId.equals(apiKey.ownerUserId()))
          .findFirst();
    }

    @Override
    public void upsertApiKey(ApiKeyRecord record) {
      ApiKeyRecord existing = findApiKey(record.domain(), record.ownerSource(), record.ownerUserId()).orElse(null);
      Long id = existing == null ? nextApiKeyId++ : existing.id();
      apiKey(new ApiKeyRecord(
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
          existing == null ? record.createdAt() : existing.createdAt(),
          record.updatedAt(),
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

    @Override
    public Optional<SecurityAnonymousConfigRecord> findAnonymousConfig() {
      return Optional.ofNullable(anonymousConfig);
    }

    @Override
    public void upsertAnonymousConfig(SecurityAnonymousConfigRecord record) {
      anonymousConfig = record;
    }

    @Override
    public List<SecurityRepositoryTargetRecord> listRepositoryTargets() {
      return new ArrayList<>(repositoryTargets.values());
    }

    @Override
    public Optional<SecurityRepositoryTargetRecord> findRepositoryTarget(String targetId) {
      return Optional.ofNullable(repositoryTargets.get(targetId));
    }

    @Override
    public void upsertRepositoryTarget(SecurityRepositoryTargetRecord record) {
      SecurityRepositoryTargetRecord existing = repositoryTargets.get(record.targetId());
      repositoryTargets.put(record.targetId(), new SecurityRepositoryTargetRecord(
          existing == null ? (long) (repositoryTargets.size() + 1) : existing.id(),
          record.targetId(),
          record.name(),
          record.format(),
          record.contentExpression(),
          record.pathPatterns(),
          record.attributes()));
    }

    @Override
    public int deleteRepositoryTarget(String targetId) {
      return repositoryTargets.remove(targetId) == null ? 0 : 1;
    }

    @Override
    public int deleteUser(String source, String userId) {
      SecurityUserRecord removed = users.remove(source + "/" + userId);
      if (removed != null) {
        deletedUsers++;
        return 1;
      }
      return 0;
    }
  }
}
