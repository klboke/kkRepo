package com.github.klboke.kkrepo.migration.nexus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusApiKey;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusAnonymousConfig;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusContentSelector;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusPrivilege;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusRole;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUser;
import com.github.klboke.kkrepo.migration.nexus.security.NexusSecurityRecordMapper.NexusUserRoleMapping;
import com.github.klboke.kkrepo.persistence.mysql.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityAnonymousConfigRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityPrivilegeRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRealmRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRepositoryTargetRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityRoleRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.SecurityUserRecord;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class NexusSecurityMigrationServiceTest {

  @Test
  void writesSecurityObjectsInDependencyOrder() {
    RecordingWriter writer = new RecordingWriter();
    NexusSecurityMigrationService service =
        new NexusSecurityMigrationService(new NexusSecurityRecordMapper(), writer);

    NexusSecurityMigrationResult result = service.migrate(new NexusSecurityMigrationBatch(
        List.of(new NexusContentSelector(
            "team-a",
            "csel",
            "Team A",
            "path =~ \"^/team-a/.*\"",
            "*",
            Map.of())),
        List.of(new NexusPrivilege(
            "p-team-a",
            "Team A privilege",
            null,
            "repository-content-selector",
            false,
            Map.of("contentSelector", "team-a", "format", "maven2", "repository", "*", "actions", "read,browse"))),
        List.of(new NexusRole(
            "r-team-a",
            "default",
            "Team A role",
            null,
            false,
            List.of("p-team-a"),
            List.of("nx-anonymous"),
            Map.of())),
        List.of(new NexusUser(
            "alice",
            "default",
            "Alice",
            null,
            "alice@example.com",
            "$shiro1$hash",
            "active",
            Map.of())),
        List.of(new NexusUserRoleMapping("alice", "default", List.of("r-team-a"))),
        List.of("NexusAuthenticatingRealm", "LdapRealm"),
        List.of(new NexusApiKey(
            "NpmToken",
            "default",
            "alice",
            "raw-secret",
            "npm",
            "ACTIVE")),
        new NexusAnonymousConfig(
            true,
            null,
            "anonymous",
            "NexusAuthorizingRealm")));

    assertEquals(new NexusSecurityMigrationResult(1, 1, 1, 1, 1, 2, 1, 1), result);
    assertEquals(List.of(
        "target:team-a",
        "privilege:p-team-a",
        "role:r-team-a",
        "role-privileges:r-team-a:p-team-a",
        "role-children:r-team-a:nx-anonymous",
        "user:Local/alice",
        "user-roles:Local/alice:r-team-a",
        "realm:local:0",
        "realm:ldap:10",
        "realm-config:local,ldap",
        "anonymous:Local/anonymous:true",
        "api-key:NpmToken/alice"),
        writer.operations);
  }

  @Test
  void skipsRealmConfigWhenSourceDidNotProvideRealmOrder() {
    RecordingWriter writer = new RecordingWriter();
    NexusSecurityMigrationService service =
        new NexusSecurityMigrationService(new NexusSecurityRecordMapper(), writer);

    NexusSecurityMigrationResult result = service.migrate(NexusSecurityMigrationBatch.empty());

    assertEquals(new NexusSecurityMigrationResult(0, 0, 0, 0, 0, 0, 0, 0), result);
    assertEquals(List.of(), writer.operations);
  }

  @Test
  void writesDisabledAnonymousStateFromApiExport() {
    NexusSecurityMigrationBatch batch = new NexusSecurityExportReader().read(new NexusSecurityExport(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(
            "enabled", false,
            "userId", "anonymous",
            "realmName", "NexusAuthorizingRealm")));
    RecordingWriter writer = new RecordingWriter();
    NexusSecurityMigrationService service =
        new NexusSecurityMigrationService(new NexusSecurityRecordMapper(), writer);

    NexusSecurityMigrationResult result = service.migrate(batch);

    assertEquals(new NexusSecurityMigrationResult(0, 0, 0, 0, 0, 0, 0, 1), result);
    assertEquals(List.of("anonymous:Local/anonymous:false"), writer.operations);
  }

  @Test
  void migratesRealisticFullSecurityExportFixture() throws IOException {
    NexusSecurityMigrationBatch batch = new NexusSecurityExportReader().read(readFixture());
    RecordingWriter writer = new RecordingWriter();
    NexusSecurityMigrationService service =
        new NexusSecurityMigrationService(new NexusSecurityRecordMapper(), writer);

    NexusSecurityMigrationResult result = service.migrate(batch);

    assertEquals(new NexusSecurityMigrationResult(1, 4, 3, 3, 3, 3, 2, 1), result);
    assertEquals(List.of(
        "target:team-csel",
        "privilege:nx-repository-view-maven2-releases-read",
        "privilege:nx-repository-content-selector-team-read",
        "privilege:nx-security-read",
        "privilege:nx-all",
        "role:nx-team-reader",
        "role:nx-security-viewer",
        "role:nx-admin",
        "role-privileges:nx-team-reader:nx-repository-view-maven2-releases-read,nx-repository-content-selector-team-read",
        "role-children:nx-team-reader:nx-security-viewer",
        "role-privileges:nx-security-viewer:nx-security-read",
        "role-children:nx-security-viewer:",
        "role-privileges:nx-admin:nx-all",
        "role-children:nx-admin:",
        "user:Local/alice",
        "user:LDAP/bob",
        "user:OIDC/carol",
        "user-roles:Local/alice:nx-team-reader",
        "user-roles:LDAP/bob:nx-team-reader",
        "user-roles:OIDC/carol:nx-security-viewer",
        "realm:ldap:0",
        "realm:local:10",
        "realm:oidc:20",
        "realm-config:ldap,local,oidc",
        "anonymous:Local/anonymous:true",
        "api-key:NpmToken/alice",
        "api-key:NpmToken/bob"),
        writer.operations);
  }

  private static NexusSecurityExport readFixture() throws IOException {
    try (InputStream stream = NexusSecurityMigrationServiceTest.class.getResourceAsStream(
        "/nexus-security/full-security-export.json")) {
      return new ObjectMapper().readValue(Objects.requireNonNull(stream), NexusSecurityExport.class);
    }
  }

  private static class RecordingWriter implements NexusSecurityMigrationWriter {
    private final List<String> operations = new ArrayList<>();

    @Override
    public void upsertRepositoryTarget(SecurityRepositoryTargetRecord record) {
      operations.add("target:" + record.targetId());
    }

    @Override
    public void upsertPrivilege(SecurityPrivilegeRecord record) {
      operations.add("privilege:" + record.privilegeId());
    }

    @Override
    public void upsertRole(SecurityRoleRecord record) {
      operations.add("role:" + record.roleId());
    }

    @Override
    public void replaceRolePrivileges(String roleId, List<String> privilegeIds) {
      operations.add("role-privileges:" + roleId + ":" + String.join(",", privilegeIds));
    }

    @Override
    public void replaceRoleInheritance(String roleId, List<String> childRoleIds) {
      operations.add("role-children:" + roleId + ":" + String.join(",", childRoleIds));
    }

    @Override
    public void upsertUser(SecurityUserRecord record) {
      operations.add("user:" + record.source() + "/" + record.userId());
    }

    @Override
    public void replaceUserRoles(String source, String userId, List<String> roleIds) {
      operations.add("user-roles:" + source + "/" + userId + ":" + String.join(",", roleIds));
    }

    @Override
    public void upsertRealm(SecurityRealmRecord record) {
      operations.add("realm:" + record.realmId() + ":" + record.priority());
    }

    @Override
    public void updateRealmConfig(List<String> activeRealmIds) {
      operations.add("realm-config:" + String.join(",", activeRealmIds));
    }

    @Override
    public void upsertAnonymousConfig(SecurityAnonymousConfigRecord record) {
      operations.add("anonymous:" + record.userSource() + "/" + record.userId() + ":" + record.enabled());
    }

    @Override
    public void upsertApiKey(ApiKeyRecord record) {
      operations.add("api-key:" + record.domain() + "/" + record.ownerUserId());
    }
  }
}
