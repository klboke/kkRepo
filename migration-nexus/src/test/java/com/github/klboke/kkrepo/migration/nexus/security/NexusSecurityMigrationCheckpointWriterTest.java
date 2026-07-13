package com.github.klboke.kkrepo.migration.nexus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NexusSecurityMigrationCheckpointWriterTest {

  @Test
  void writesDeterministicCheckpointsForSecurityExportDocuments() {
    List<MigrationCheckpointRecord> checkpoints = new ArrayList<>();
    NexusSecurityMigrationCheckpointWriter writer =
        new NexusSecurityMigrationCheckpointWriter(checkpoints::add, new ObjectMapper());

    int count = writer.write(42, new NexusSecurityExport(
        List.of(Map.of("@rid", "#12:0", "id", "admin", "status", "active")),
        List.of(Map.of("id", "nx-deploy")),
        List.of(Map.of("id", "nx-repository-view-maven2-releases-read")),
        List.of(Map.of("userId", "admin", "source", "default", "roles", List.of("nx-admin"))),
        List.of(Map.of("domain", "NpmToken", "api_key", "raw", "primary_principal", "admin")),
        List.of(Map.of("name", "team-a", "attributes", Map.of("expression", "path == '/team-a/a.jar'"))),
        List.of(Map.of("targetId", "maven-target")),
        List.of("NexusAuthenticatingRealm"),
        Map.of("enabled", true, "user_id", "anonymous", "realm_name", "NexusAuthorizingRealm")));

    assertEquals(8, count);
    assertEquals(8, checkpoints.size());
    assertCheckpoint(checkpoints.get(0), "user", "#12:0", "security_user", "Local/admin");
    assertCheckpoint(checkpoints.get(1), "role", "nx-deploy", "security_role", "nx-deploy");
    assertCheckpoint(
        checkpoints.get(2),
        "privilege",
        "nx-repository-view-maven2-releases-read",
        "security_privilege",
        "nx-repository-view-maven2-releases-read");
    assertCheckpoint(checkpoints.get(3), "user_role_mapping", "Local/admin", "security_user_role", "Local/admin");
    assertCheckpoint(checkpoints.get(4), "api_key", "NpmToken/Local/admin", "api_key", "NpmToken/Local/admin");
    assertCheckpoint(checkpoints.get(5), "selector", "team-a", "security_repository_target", "team-a");
    assertCheckpoint(
        checkpoints.get(6),
        "realm_configuration",
        "realm_names",
        "security_realm_config",
        "1");
    assertCheckpoint(
        checkpoints.get(7),
        "anonymous",
        "anonymous",
        "security_anonymous_config",
        "1");
  }

  @Test
  void apiKeyCheckpointUsesExpandedPrincipalCollectionOwner() {
    List<MigrationCheckpointRecord> checkpoints = new ArrayList<>();
    NexusSecurityMigrationCheckpointWriter writer =
        new NexusSecurityMigrationCheckpointWriter(checkpoints::add, new ObjectMapper());

    int count = writer.write(42, new NexusSecurityExport(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(Map.of(
            "domain", "NpmToken",
            "api_key", "raw",
            "principals", Map.of(
                "primaryPrincipal", "ldap-user",
                "realmNames", List.of("LdapRealm")))),
        List.of(),
        List.of(),
        List.of(),
        Map.of()));

    assertEquals(1, count);
    assertEquals(1, checkpoints.size());
    assertCheckpoint(checkpoints.get(0), "api_key", "NpmToken/LDAP/ldap-user", "api_key", "NpmToken/LDAP/ldap-user");
  }

  @Test
  void checkpointTargetIdsNormalizeNexusSourceAliases() {
    List<MigrationCheckpointRecord> checkpoints = new ArrayList<>();
    NexusSecurityMigrationCheckpointWriter writer =
        new NexusSecurityMigrationCheckpointWriter(checkpoints::add, new ObjectMapper());

    int count = writer.write(42, new NexusSecurityExport(
        List.of(Map.of("id", "admin", "source", "Nexus")),
        List.of(),
        List.of(),
        List.of(Map.of("userId", "admin", "source", "NexusAuthorizingRealm", "roles", List.of("nx-admin"))),
        List.of(Map.of(
            "domain", "NpmToken",
            "source", "local",
            "api_key", "raw",
            "primary_principal", "admin")),
        List.of(),
        List.of(),
        List.of(),
        Map.of()));

    assertEquals(3, count);
    assertEquals(3, checkpoints.size());
    assertCheckpoint(checkpoints.get(0), "user", "Local/admin", "security_user", "Local/admin");
    assertCheckpoint(checkpoints.get(1), "user_role_mapping", "Local/admin", "security_user_role", "Local/admin");
    assertCheckpoint(checkpoints.get(2), "api_key", "NpmToken/Local/admin", "api_key", "NpmToken/Local/admin");
  }

  private static void assertCheckpoint(
      MigrationCheckpointRecord record,
      String sourceClass,
      String sourceRid,
      String targetTable,
      String targetId) {
    assertEquals(42, record.jobId());
    assertEquals("security", record.sourceDatabase());
    assertEquals(sourceClass, record.sourceClass());
    assertEquals(sourceRid, record.sourceRid());
    assertEquals(targetTable, record.targetTable());
    assertEquals(targetId, record.targetId());
    assertTrue(record.sourceChecksum().matches("[0-9a-f]{64}"));
  }
}
