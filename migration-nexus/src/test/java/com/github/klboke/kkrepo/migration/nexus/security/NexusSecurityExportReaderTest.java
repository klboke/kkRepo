package com.github.klboke.kkrepo.migration.nexus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NexusSecurityExportReaderTest {
  private final NexusSecurityExportReader reader = new NexusSecurityExportReader();

  @Test
  void readsNexus329SecurityAdapterFieldNames() {
    NexusSecurityMigrationBatch batch = reader.read(new NexusSecurityExport(
        List.of(Map.of(
            "@rid", "#12:0",
            "id", "admin",
            "firstName", "Admin",
            "lastName", "User",
            "email", "admin@example.com",
            "password", "$shiro1$hash",
            "status", "active")),
        List.of(Map.of(
            "id", "nx-deploy",
            "name", "Deploy",
            "description", "Deploy role",
            "privileges", List.of("nx-repository-view-maven2-releases-add"),
            "roles", List.of("nx-anonymous"))),
        List.of(Map.of(
            "id", "nx-repository-view-maven2-releases-add",
            "name", "add releases",
            "type", "repository-view",
            "properties", Map.of("format", "maven2", "repository", "releases", "actions", List.of("add", "edit")))),
        List.of(Map.of(
            "userId", "admin",
            "source", "default",
            "roles", List.of("nx-admin"))),
        List.of(Map.of(
            "domain", "NpmToken",
            "api_key", "raw-secret",
            "primary_principal", "admin")),
        List.of(Map.of(
            "name", "team-a",
            "type", "csel",
            "description", "Team A",
            "attributes", Map.of("expression", "path =~ \"^/team-a/.*\""))),
        List.of(Map.of(
            "id", "maven-target",
            "name", "Maven target",
            "format", "maven2",
            "patterns", List.of("/com/acme/.*"))),
        List.of("NexusAuthenticatingRealm"),
        Map.of(
            "enabled", true,
            "user_id", "anonymous",
            "realm_name", "NexusAuthorizingRealm")));

    assertEquals("admin", batch.users().get(0).id());
    assertEquals("$shiro1$hash", batch.users().get(0).passwordHash());
    assertEquals("nx-deploy", batch.roles().get(0).id());
    assertEquals(List.of("nx-repository-view-maven2-releases-add"), batch.roles().get(0).privileges());
    assertEquals("repository-view", batch.privileges().get(0).type());
    assertEquals("add,edit", batch.privileges().get(0).properties().get("actions"));
    assertEquals("nx-admin", batch.userRoleMappings().get(0).roles().get(0));
    assertEquals("NpmToken", batch.apiKeys().get(0).domain());
    assertEquals("raw-secret", batch.apiKeys().get(0).rawApiKey());
    assertEquals("team-a", batch.contentSelectors().get(0).name());
    assertEquals("path =~ \"^/team-a/.*\"", batch.contentSelectors().get(0).expression());
    assertEquals(List.of("NexusAuthenticatingRealm"), batch.realmOrder());
    assertEquals(true, batch.anonymousConfig().enabled());
    assertEquals("anonymous", batch.anonymousConfig().userId());
    assertEquals("NexusAuthorizingRealm", batch.anonymousConfig().realmName());
  }

  @Test
  void readsApiKeyOwnerFromExpandedPrincipalCollection() {
    NexusSecurityMigrationBatch batch = reader.read(new NexusSecurityExport(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(Map.of(
            "domain", "NpmToken",
            "api_key", "raw-secret",
            "principals", Map.of(
                "primaryPrincipal", "ldap-user",
                "realmNames", List.of("LdapRealm")))),
        List.of(),
        List.of(),
        List.of(),
        Map.of()));

    assertEquals("NpmToken", batch.apiKeys().get(0).domain());
    assertEquals("LDAP", batch.apiKeys().get(0).ownerSource());
    assertEquals("ldap-user", batch.apiKeys().get(0).ownerUserId());
    assertEquals("raw-secret", batch.apiKeys().get(0).rawApiKey());
  }

  @Test
  void preservesDisabledAnonymousConfigFromRestExport() {
    NexusSecurityMigrationBatch batch = reader.read(new NexusSecurityExport(
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

    assertEquals(false, batch.anonymousConfig().enabled());
    assertEquals("anonymous", batch.anonymousConfig().userId());
  }

  @Test
  void normalizesNexusSourceAliasesWhileReadingExport() {
    NexusSecurityMigrationBatch batch = reader.read(new NexusSecurityExport(
        List.of(Map.of("id", "admin", "source", "Nexus")),
        List.of(Map.of("id", "nx-admin", "source", "NexusAuthenticatingRealm")),
        List.of(),
        List.of(Map.of("userId", "admin", "source", "NexusAuthorizingRealm", "roles", List.of("nx-admin"))),
        List.of(Map.of(
            "domain", "NpmToken",
            "source", "local",
            "api_key", "raw-secret",
            "primary_principal", "admin")),
        List.of(),
        List.of(),
        List.of(),
        Map.of()));

    assertEquals("Local", batch.users().get(0).source());
    assertEquals("Local", batch.roles().get(0).source());
    assertEquals("Local", batch.userRoleMappings().get(0).source());
    assertEquals("Local", batch.apiKeys().get(0).ownerSource());
  }
}
