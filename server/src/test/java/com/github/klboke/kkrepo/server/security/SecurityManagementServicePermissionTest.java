package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.SecurityDao;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRepositoryTargetRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityManagementServicePermissionTest {

  @Test
  void repositoryViewPrivilegeAllowsMatchingRepositoryActionAndFormat() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-reader");
    dao.grant("nx-reader", privilege(
        "nx-repository-view-maven2-releases-read",
        "repository-view",
        Map.of("format", "maven2", "repository", "releases", "actions", "read,browse")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "junit/junit/4.13.2/junit-4.13.2.pom", PermissionAction.READ))
        .allowed());
    assertFalse(service.decide(subject("alice"), repositoryPermission("snapshots", "junit/junit/4.13.2/junit-4.13.2.pom", PermissionAction.READ))
        .allowed());
  }

  @Test
  void repositoryViewPrivilegeAcceptsMigratedRepositoryAliases() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-reader");
    dao.grant("nx-reader", privilege(
        "nx-repository-view-maven2-releases-read",
        "repository-view",
        Map.of("repositoryFormat", "maven2", "repositoryId", "releases", "action", "read")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "junit/junit/4.13.2/junit-4.13.2.pom", PermissionAction.READ))
        .allowed());
    assertFalse(service.decide(subject("alice"), repositoryPermission("snapshots", "junit/junit/4.13.2/junit-4.13.2.pom", PermissionAction.READ))
        .allowed());
  }

  @Test
  void repositoryViewPrivilegeTreatsNexusAllActionAsWildcard() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-repository-writer");
    dao.grant("nx-repository-writer", privilege(
        "nx-repository-view-maven2-releases-all",
        "repository-view",
        Map.of("format", "maven2", "repository", "releases", "actions", List.of("ALL"))));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.ADD))
        .allowed());
    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.EDIT))
        .allowed());
    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.DELETE))
        .allowed());
    assertFalse(service.decide(subject("alice"), repositoryPermission("snapshots", "com/acme/app/1.0/app-1.0.pom", PermissionAction.ADD))
        .allowed());
  }

  @Test
  void applicationPrivilegeAcceptsNexusActionAliases() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-role-manager");
    dao.grant("nx-role-manager", privilege(
        "nx-roles-create",
        "application",
        Map.of("domain", "roles", "actions", List.of("ADD"))));
    dao.grant("nx-role-manager", privilege(
        "nx-privileges-all",
        "application",
        Map.of("domain", "privileges", "actions", List.of("ALL"))));
    dao.grant("nx-role-manager", privilege(
        "nx-settings-update",
        "application",
        Map.of("domain", "settings", "actions", List.of("EDIT"))));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), "nexus:roles:create").allowed());
    assertTrue(service.decide(subject("alice"), "nexus:privileges:delete").allowed());
    assertTrue(service.decide(subject("alice"), "nexus:settings:update").allowed());
    assertFalse(service.decide(subject("alice"), "nexus:users:create").allowed());
  }

  @Test
  void nexusAllActionDoesNotSatisfyRequestedWildcardActions() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-scoped-all");
    dao.grant("nx-scoped-all", privilege(
        "nx-privileges-all",
        "application",
        Map.of("domain", "privileges", "actions", List.of("ALL"))));
    dao.grant("nx-scoped-all", privilege(
        "nx-repository-view-maven2-releases-all",
        "repository-view",
        Map.of("format", "maven2", "repository", "releases", "actions", List.of("ALL"))));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), "nexus:privileges:delete").allowed());
    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.ADD))
        .allowed());
    assertFalse(service.decide(subject("alice"), "nexus:privileges:*").allowed());
    assertFalse(service.decide(subject("alice"), "nexus:repository-view:maven2:releases:*").allowed());
    assertFalse(service.decide(subject("alice"), "nexus:*").allowed());
  }

  @Test
  void repositoryTargetPrivilegesDoNotGrantRepositoryAccess() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-target-reader");
    dao.target(new SecurityRepositoryTargetRecord(
        1L,
        "team-target",
        "Team target",
        "maven2",
        null,
        Map.of("patterns", List.of("/com/acme/.*\\.jar")),
        Map.of()));
    dao.grant("nx-target-reader", privilege(
        "nx-repository-target-team-read",
        "repository-target",
        Map.of("targetId", "team-target", "format", "maven2", "repository", "releases", "actions", "read")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertFalse(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.jar", PermissionAction.READ))
        .allowed());
  }

  @Test
  void repositoryContentSelectorPrivilegeUsesNexusRepositorySelectorFormat() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-selector-reader");
    dao.target(new SecurityRepositoryTargetRecord(
        1L,
        "team-selector",
        "Team selector",
        "*",
        "path =~ \"^/com/acme/.*\\\\.pom$\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector")));
    dao.grant("nx-selector-reader", privilege(
        "nx-repository-content-selector-team-read",
        "repository-content-selector",
        Map.of("contentSelector", "team-selector", "repository", "maven2/*", "actions", "read")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.READ))
        .allowed());
  }

  @Test
  void repositoryContentSelectorPrivilegeAcceptsMigratedSelectorAliases() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-selector-reader");
    dao.target(new SecurityRepositoryTargetRecord(
        1L,
        "team-selector",
        "Team selector",
        "*",
        "path =~ \"^/com/acme/.*\\\\.pom$\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector")));
    dao.grant("nx-selector-reader", privilege(
        "nx-repository-content-selector-team-read",
        "repository-content-selector",
        Map.of("selectorName", "team-selector", "repositoryFormat", "maven2", "repositoryName", "releases", "action", "read")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.READ))
        .allowed());
    assertFalse(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.jar", PermissionAction.READ))
        .allowed());
  }

  @Test
  void repositoryContentSelectorPrivilegeEvaluatesSelectorExpression() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-selector-reader");
    dao.target(new SecurityRepositoryTargetRecord(
        1L,
        "team-selector",
        "Team selector",
        "*",
        "path =~ \"^/com/acme/.*\\\\.pom$\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector")));
    dao.grant("nx-selector-reader", privilege(
        "nx-repository-content-selector-team-read",
        "repository-content-selector",
        Map.of("contentSelector", "team-selector", "format", "maven2", "repository", "*", "actions", "read,browse")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.pom", PermissionAction.READ))
        .allowed());
    assertFalse(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.jar", PermissionAction.READ))
        .allowed());
  }

  @Test
  void repositoryContentSelectorPrivilegeEvaluatesNexusMavenCoordinateForms() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-selector-reader");
    dao.target(new SecurityRepositoryTargetRecord(
        1L,
        "main-jar-selector",
        "Main jar selector",
        "*",
        "coordinate.classifier == \"\" && coordinate.extension == \".jar\"",
        Map.of("patterns", List.of()),
        Map.of("source", "nexus-content-selector")));
    dao.grant("nx-selector-reader", privilege(
        "nx-repository-content-selector-main-jar-read",
        "repository-content-selector",
        Map.of("contentSelector", "main-jar-selector", "format", "maven2", "repository", "*", "actions", "read")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0.jar", PermissionAction.READ))
        .allowed());
    assertFalse(service.decide(subject("alice"), repositoryPermission("releases", "com/acme/app/1.0/app-1.0-sources.jar", PermissionAction.READ))
        .allowed());
  }

  @Test
  void inheritedRolesContributeRepositoryPrivileges() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "alice", "nx-parent");
    dao.inherit("nx-parent", "nx-child");
    dao.grant("nx-child", privilege(
        "nx-repository-view-maven2-releases-browse",
        "repository-view",
        Map.of("format", "maven2", "repository", "releases", "actions", "browse")));
    SecurityManagementService service = new SecurityManagementService(dao);

    assertTrue(service.decide(subject("alice"), repositoryPermission("releases", "", PermissionAction.BROWSE)).allowed());
  }

  @Test
  void effectivePermissionsExpandWildcardAgainstKnownPrivilegeCatalog() {
    FakeSecurityDao dao = new FakeSecurityDao();
    dao.assign("Local", "admin", "nx-admin");
    dao.grant("nx-admin", privilege("nx-all", "wildcard", Map.of("pattern", "nexus:*")));
    dao.privilege(privilege("nx-users-read", "wildcard", Map.of("pattern", "nexus:users:read")));
    dao.privilege(privilege(
        "nx-repository-admin-maven2-all-add",
        "repository-admin",
        Map.of("format", "maven2", "repository", "*", "actions", "add")));
    SecurityManagementService service = new SecurityManagementService(dao);

    List<String> permissions = service.listEffectivePermissions(subject("admin"));

    assertTrue(permissions.contains("nexus:*"));
    assertTrue(permissions.contains("nexus:users:read"));
    assertTrue(permissions.contains("nexus:repository-admin:maven2:*:add"));
  }

  private static PermissionSubject subject(String userId) {
    return new PermissionSubject("Local", userId, Set.of(), null);
  }

  private static RepositoryPermission repositoryPermission(
      String repository,
      String path,
      PermissionAction action) {
    return new RepositoryPermission(repository, RepositoryFormat.MAVEN2, path, action);
  }

  private static SecurityPrivilegeRecord privilege(
      String privilegeId,
      String type,
      Map<String, Object> properties) {
    return new SecurityPrivilegeRecord(privilegeId, privilegeId, null, type, false, properties);
  }

  private static class FakeSecurityDao extends SecurityDao {
    private final Map<String, List<String>> userRoles = new LinkedHashMap<>();
    private final Map<String, List<String>> childRoles = new LinkedHashMap<>();
    private final Map<String, List<SecurityPrivilegeRecord>> rolePrivileges = new LinkedHashMap<>();
    private final Map<String, SecurityPrivilegeRecord> privileges = new LinkedHashMap<>();
    private final Map<String, SecurityRepositoryTargetRecord> targets = new LinkedHashMap<>();

    private FakeSecurityDao() {
      super(null, null);
    }

    private void assign(String source, String userId, String roleId) {
      userRoles.computeIfAbsent(source + "/" + userId, ignored -> new ArrayList<>()).add(roleId);
    }

    private void inherit(String roleId, String childRoleId) {
      childRoles.computeIfAbsent(roleId, ignored -> new ArrayList<>()).add(childRoleId);
    }

    private void grant(String roleId, SecurityPrivilegeRecord privilege) {
      privilege(privilege);
      rolePrivileges.computeIfAbsent(roleId, ignored -> new ArrayList<>()).add(privilege);
    }

    private void privilege(SecurityPrivilegeRecord privilege) {
      privileges.put(privilege.privilegeId(), privilege);
    }

    private void target(SecurityRepositoryTargetRecord target) {
      targets.put(target.targetId(), target);
    }

    @Override
    public List<String> listUserRoleIds(String source, String userId) {
      return userRoles.getOrDefault(source + "/" + userId, List.of());
    }

    @Override
    public List<String> listRoleChildIds(String roleId) {
      return childRoles.getOrDefault(roleId, List.of());
    }

    @Override
    public List<SecurityPrivilegeRecord> listPrivilegesForRoles(List<String> roleIds) {
      return roleIds.stream()
          .flatMap(roleId -> rolePrivileges.getOrDefault(roleId, List.of()).stream())
          .toList();
    }

    @Override
    public List<SecurityPrivilegeRecord> listPrivileges() {
      return new ArrayList<>(privileges.values());
    }

    @Override
    public List<SecurityRepositoryTargetRecord> listRepositoryTargets() {
      return new ArrayList<>(targets.values());
    }

    @Override
    public Optional<SecurityRepositoryTargetRecord> findRepositoryTarget(String targetId) {
      return Optional.ofNullable(targets.get(targetId));
    }
  }
}
