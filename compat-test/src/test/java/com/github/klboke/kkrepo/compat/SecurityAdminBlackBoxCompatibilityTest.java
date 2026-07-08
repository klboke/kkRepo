package com.github.klboke.kkrepo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityAdminBlackBoxCompatibilityTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final String NEXUS_PASSWORD_PLACEHOLDER = "#~NEXUS~PLACEHOLDER~PASSWORD~#";

  @Test
  void privilegeFormRepositoryFieldsMatchNexusStoreContracts() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    JsonNode reference = config.reference().extDirect("coreui_Privilege", "readTypes");
    JsonNode candidate = config.candidate().extDirect("coreui_Privilege", "readTypes");

    assertRepositoryField(reference, "repository-view", "coreui_Repository.readReferencesAddingEntryForAll");
    assertRepositoryField(candidate, "repository-view", "coreui_Repository.readReferencesAddingEntryForAll");
    assertRepositoryField(reference, "repository-admin", "coreui_Repository.readReferencesAddingEntryForAll");
    assertRepositoryField(candidate, "repository-admin", "coreui_Repository.readReferencesAddingEntryForAll");
    assertRepositoryField(reference, "repository-content-selector",
        "coreui_Repository.readReferencesAddingEntriesForAllFormats");
    assertRepositoryField(candidate, "repository-content-selector",
        "coreui_Repository.readReferencesAddingEntriesForAllFormats");
    assertEquals(
        "coreui_Selector.readReferences",
        field(candidate, "repository-content-selector", "contentSelector").path("storeApi").asText());
  }

  @Test
  void repositoryReferenceStoreIncludesAllRepositorySelectors() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    JsonNode reference = config.reference()
        .extDirect("coreui_Repository", "readReferencesAddingEntriesForAllFormats", "{}");
    JsonNode candidate = config.candidate()
        .extDirect("coreui_Repository", "readReferencesAddingEntriesForAllFormats", "{}");

    assertReferenceRow(reference, "*", "(All Repositories)");
    assertReferenceRow(candidate, "*", "(All Repositories)");
    assertReferenceRow(reference, "*-maven2", "(All maven2 Repositories)");
    assertReferenceRow(candidate, "*-maven2", "(All maven2 Repositories)");
    JsonNode mavenPublic = referenceRow(candidate, "maven-public");
    assertEquals("maven2", mavenPublic.path("format").asText());
    assertEquals("group", mavenPublic.path("type").asText());
    assertEquals("MIXED", mavenPublic.path("versionPolicy").asText());
    assertTrue(mavenPublic.path("status").path("online").asBoolean());
  }

  @Test
  void realmTypesExposeSupportedNexusRealmNames() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    JsonNode reference = config.reference().extDirect("coreui_RealmSettings", "readRealmTypes");
    JsonNode candidate = config.candidate().extDirect("coreui_RealmSettings", "readRealmTypes");

    assertReferenceRow(reference, "NexusAuthenticatingRealm", "Local Authenticating Realm");
    assertReferenceRow(reference, "NexusAuthorizingRealm", "Local Authorizing Realm");
    assertReferenceRow(reference, "LdapRealm", "LDAP Realm");
    assertReferenceRow(candidate, "NexusAuthenticatingRealm", "Local Authenticating Realm");
    assertReferenceRow(candidate, "NexusAuthorizingRealm", "Local Authorizing Realm");
    assertReferenceRow(candidate, "LdapRealm", "LDAP Realm");
    assertReferenceRow(candidate, "OidcRealm", "OIDC Realm");
  }

  @Test
  void userReadContractMatchesNexusForBuiltInUsers() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    JsonNode reference = config.reference().extDirect(
        "coreui_User", "read", "{\"page\":1,\"start\":0,\"limit\":25}");
    JsonNode candidate = config.candidate().extDirect(
        "coreui_User", "read", "{\"page\":1,\"start\":0,\"limit\":25}");

    assertUserRow(reference, "admin", "default", NEXUS_PASSWORD_PLACEHOLDER, false);
    assertUserRow(reference, "anonymous", "default", NEXUS_PASSWORD_PLACEHOLDER, false);
    assertUserRow(candidate, "admin", "default", NEXUS_PASSWORD_PLACEHOLDER, false);
    assertUserRow(candidate, "anonymous", "default", NEXUS_PASSWORD_PLACEHOLDER, false);
    assertArrayContains(candidateRow(reference, "admin", "userId").path("roles"), "nx-admin");
    assertArrayContains(candidateRow(candidate, "admin", "userId").path("roles"), "nx-admin");
  }

  @Test
  void roleAndPrivilegeReadContractsIncludeCoreBuiltIns() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    JsonNode referenceRoles = config.reference().extDirect("coreui_Role", "read");
    JsonNode candidateRoles = config.candidate().extDirect("coreui_Role", "read");
    JsonNode referencePrivileges = config.reference().extDirect(
        "coreui_Privilege", "read", "{\"page\":1,\"start\":0,\"limit\":1000}");
    JsonNode candidatePrivileges = config.candidate().extDirect(
        "coreui_Privilege", "read", "{\"page\":1,\"start\":0,\"limit\":1000}");

    assertRoleRow(referenceRoles, "nx-admin", "Nexus", true);
    assertRoleRow(candidateRoles, "nx-admin", "Nexus", true);
    assertRoleRow(referenceRoles, "nx-anonymous", "Nexus", true);
    assertRoleRow(candidateRoles, "nx-anonymous", "Nexus", true);
    assertPrivilegeRow(referencePrivileges, "nx-all", "wildcard", "nexus:*");
    assertPrivilegeRow(candidatePrivileges, "nx-all", "wildcard", "nexus:*");
    assertPrivilegeRow(referencePrivileges, "nx-apikey-all", "application", "nexus:apikey:*");
    assertPrivilegeRow(candidatePrivileges, "nx-apikey-all", "application", "nexus:apikey:*");
  }

  @Test
  void nonAdminRepositoryRoleCanBrowseButCannotUseSecurityAdministration() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    String suffix = Long.toString(System.currentTimeMillis());
    NonAdminFixture fixture = new NonAdminFixture(
        "nxp-compat-read-" + suffix,
        "nxp-compat-role-" + suffix,
        "nxp-compat-user-" + suffix,
        "NxpCompat123!");

    try {
      installNonAdminFixture(config.reference(), fixture);
      installNonAdminFixture(config.candidate(), fixture);

      Endpoint referenceUser = config.reference().as(fixture.userId(), fixture.password());
      Endpoint candidateUser = config.candidate().as(fixture.userId(), fixture.password());

      assertSameStatus("non-admin maven-public repository root",
          referenceUser.get("/repository/maven-public/"),
          candidateUser.get("/repository/maven-public/"));
      assertSameStatus("non-admin maven-public browse root",
          referenceUser.get("/service/rest/repository/browse/maven-public/"),
          candidateUser.get("/service/rest/repository/browse/maven-public/"));

      Exchange referenceUsers = referenceUser.get("/service/rest/v1/security/users");
      Exchange candidateUsers = candidateUser.get("/service/rest/v1/security/users");
      assertSameStatus("non-admin security users denied", referenceUsers, candidateUsers);
      assertEquals(403, candidateUsers.status(), "kkrepo should deny non-admin security user listing");

      Exchange uploadable = candidateUser.get("/internal/repositories/uploadable");
      assertEquals(200, uploadable.status(), "kkrepo uploadable repository list status");
      assertEquals("[]", uploadable.bodyText().trim(),
          "repository upload dropdown data should be empty without nexus:component:create");
    } finally {
      cleanupNonAdminFixture(config.candidate(), fixture);
      cleanupNonAdminFixture(config.reference(), fixture);
    }
  }

  @Test
  void nonAdminContentSelectorRoleAllowsMatchingPathAndAccountsForDefaultReadGrant() throws Exception {
    Config config = Config.load();
    assumeTrue(config.enabled(), "Set COMPAT_SECURITY_ENABLED=true to run security admin compatibility checks");

    String suffix = Long.toString(System.currentTimeMillis());
    ContentSelectorFixture fixture = new ContentSelectorFixture(
        "nxp-compat-selector-" + suffix,
        "nxp-compat-csel-read-" + suffix,
        "nxp-compat-csel-role-" + suffix,
        "nxp-compat-csel-user-" + suffix,
        "NxpCompat123!");

    try {
      installContentSelectorFixture(config.reference(), fixture);
      installContentSelectorFixture(config.candidate(), fixture);

      Endpoint referenceUser = config.reference().as(fixture.userId(), fixture.password());
      Endpoint candidateUser = config.candidate().as(fixture.userId(), fixture.password());

      Exchange referenceAllowed = referenceUser.get(
          "/repository/maven-public/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.pom");
      Exchange candidateAllowed = candidateUser.get(
          "/repository/maven-public/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.pom");
      assertSameStatus("content-selector allowed Maven path", referenceAllowed, candidateAllowed);
      assertEquals(200, candidateAllowed.status(), "content selector should allow the matching Maven path");

      Exchange referenceDenied = referenceUser.get(
          "/repository/maven-public/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.pom");
      Exchange candidateDenied = candidateUser.get(
          "/repository/maven-public/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.pom");
      if (referenceDenied.status() != candidateDenied.status()) {
        assertEquals(403, referenceDenied.status(), "Nexus without Default Role realm denies the non-matching path");
        assertEquals(200, candidateDenied.status(), "kkrepo default authenticated role grants repository read");
        assertEffectivePermission(candidateUser, "nexus:repository-view:*:*:read");
        assertEffectivePermission(candidateUser,
            "nexus:repository-content-selector:" + fixture.selectorName() + ":maven2:maven-public:read");
      } else {
        assertSameStatus("content-selector denied Maven path", referenceDenied, candidateDenied);
      }
    } finally {
      cleanupContentSelectorFixture(config.candidate(), fixture);
      cleanupContentSelectorFixture(config.reference(), fixture);
    }
  }

  private static void assertRepositoryField(JsonNode response, String privilegeType, String storeApi) {
    JsonNode repository = field(response, privilegeType, "repository");
    assertEquals("combobox", repository.path("type").asText());
    assertEquals(storeApi, repository.path("storeApi").asText());
    assertTrue(repository.path("allowAutocomplete").asBoolean());
  }

  private static void assertUserRow(
      JsonNode response,
      String userId,
      String realm,
      String password,
      boolean external) {
    JsonNode row = candidateRow(response, userId, "userId");
    assertEquals(realm, row.path("realm").asText());
    assertEquals(password, row.path("password").asText());
    assertEquals(external, row.path("external").asBoolean());
  }

  private static void assertRoleRow(JsonNode response, String roleId, String source, boolean readOnly) {
    JsonNode row = candidateRow(response, roleId, "id");
    assertEquals(source, row.path("source").asText());
    assertEquals(readOnly, row.path("readOnly").asBoolean());
  }

  private static void assertPrivilegeRow(JsonNode response, String privilegeId, String type, String permission) {
    JsonNode row = candidateRow(response, privilegeId, "id");
    assertEquals(type, row.path("type").asText());
    assertEquals(permission, row.path("permission").asText());
  }

  private static JsonNode field(JsonNode response, String privilegeType, String fieldId) {
    JsonNode type = referenceRow(response, privilegeType);
    for (JsonNode field : type.path("formFields")) {
      if (fieldId.equals(field.path("id").asText())) {
        return field;
      }
    }
    throw new AssertionError("Missing field " + fieldId + " for privilege type " + privilegeType);
  }

  private static void assertReferenceRow(JsonNode response, String id, String name) {
    JsonNode row = referenceRow(response, id);
    assertEquals(name, row.path("name").asText());
  }

  private static void assertArrayContains(JsonNode values, String expected) {
    for (JsonNode value : values) {
      if (expected.equals(value.asText())) {
        return;
      }
    }
    throw new AssertionError("Missing array value " + expected);
  }

  private static void assertEffectivePermission(Endpoint endpoint, String expected) throws Exception {
    Exchange exchange = endpoint.get("/service/rest/internal/ui/security/permissions");
    exchange.assertStatus(200, "read effective permissions");
    JsonNode permissions = MAPPER.readTree(exchange.bodyText());
    assertTrue(permissions.isArray(), "effective permissions should be an array");
    for (JsonNode permission : permissions) {
      if (expected.equals(permission.path("id").asText())) {
        assertTrue(permission.path("permitted").asBoolean(), "permission should be permitted: " + expected);
        return;
      }
    }
    throw new AssertionError("Missing effective permission " + expected);
  }

  private static JsonNode referenceRow(JsonNode response, String id) {
    return candidateRow(response, id, "id");
  }

  private static JsonNode candidateRow(JsonNode response, String id, String idField) {
    for (JsonNode row : data(response)) {
      if (id.equals(row.path(idField).asText())) {
        return row;
      }
    }
    throw new AssertionError("Missing reference row " + id);
  }

  private static Iterable<JsonNode> data(JsonNode response) {
    JsonNode data = response.path("result").path("data");
    assertTrue(data.isArray(), "ExtDirect result.data should be an array");
    return data::elements;
  }

  private static void installNonAdminFixture(Endpoint endpoint, NonAdminFixture fixture) throws Exception {
    endpoint.postJson("/service/rest/v1/security/privileges/repository-view", Map.of(
        "name", fixture.privilegeId(),
        "description", "kkrepo compatibility repository read fixture",
        "format", "maven2",
        "repository", "maven-public",
        "actions", List.of("browse", "read"))).assertStatus(201, "create repository read privilege");
    endpoint.postJson("/service/rest/v1/security/roles", Map.of(
        "id", fixture.roleId(),
        "name", fixture.roleId(),
        "description", "kkrepo compatibility non-admin role",
        "privileges", List.of(fixture.privilegeId()),
        "roles", List.of())).assertStatus(200, "create non-admin role");
    endpoint.postJson("/service/rest/v1/security/users", Map.of(
        "userId", fixture.userId(),
        "firstName", "KkRepo",
        "lastName", "Compatibility",
        "emailAddress", fixture.userId() + "@example.invalid",
        "password", fixture.password(),
        "status", "active",
        "roles", List.of(fixture.roleId()))).assertStatus(200, "create non-admin user");
  }

  private static void cleanupNonAdminFixture(Endpoint endpoint, NonAdminFixture fixture) throws Exception {
    endpoint.delete("/service/rest/v1/security/users/" + fixture.userId()).assertDeleted("delete non-admin user");
    endpoint.delete("/service/rest/v1/security/roles/" + fixture.roleId()).assertDeleted("delete non-admin role");
    endpoint.delete("/service/rest/v1/security/privileges/" + fixture.privilegeId())
        .assertDeleted("delete repository read privilege");
  }

  private static void installContentSelectorFixture(Endpoint endpoint, ContentSelectorFixture fixture) throws Exception {
    endpoint.postJson("/service/rest/v1/security/content-selectors", Map.of(
        "name", fixture.selectorName(),
        "type", "csel",
        "description", "kkrepo compatibility content selector fixture",
        "expression", "path =~ \"^/org/hamcrest/hamcrest-core/.*\"")).assertStatus(204, "create content selector");
    endpoint.postJson("/service/rest/v1/security/privileges/repository-content-selector", Map.of(
        "name", fixture.privilegeId(),
        "description", "kkrepo compatibility content selector read fixture",
        "contentSelector", fixture.selectorName(),
        "format", "maven2",
        "repository", "maven-public",
        "actions", List.of("read"))).assertStatus(201, "create content selector read privilege");
    endpoint.postJson("/service/rest/v1/security/roles", Map.of(
        "id", fixture.roleId(),
        "name", fixture.roleId(),
        "description", "kkrepo compatibility content selector role",
        "privileges", List.of(fixture.privilegeId()),
        "roles", List.of())).assertStatus(200, "create content selector role");
    endpoint.postJson("/service/rest/v1/security/users", Map.of(
        "userId", fixture.userId(),
        "firstName", "KkRepo",
        "lastName", "ContentSelector",
        "emailAddress", fixture.userId() + "@example.invalid",
        "password", fixture.password(),
        "status", "active",
        "roles", List.of(fixture.roleId()))).assertStatus(200, "create content selector user");
  }

  private static void cleanupContentSelectorFixture(Endpoint endpoint, ContentSelectorFixture fixture) throws Exception {
    endpoint.delete("/service/rest/v1/security/users/" + fixture.userId()).assertDeleted("delete content selector user");
    endpoint.delete("/service/rest/v1/security/roles/" + fixture.roleId()).assertDeleted("delete content selector role");
    endpoint.delete("/service/rest/v1/security/privileges/" + fixture.privilegeId())
        .assertDeleted("delete content selector read privilege");
    endpoint.delete("/service/rest/v1/security/content-selectors/" + fixture.selectorName())
        .assertDeleted("delete content selector");
  }

  private static void assertSameStatus(String label, Exchange reference, Exchange candidate) {
    assertEquals(reference.status(), candidate.status(), label + " status");
  }

  private record Config(
      boolean enabled,
      Endpoint reference,
      Endpoint candidate) {

    static Config load() {
      boolean enabled = Boolean.parseBoolean(setting("compat.security.enabled", "COMPAT_SECURITY_ENABLED")
          .orElse("false"));
      Endpoint reference = new Endpoint(
          CompatDefaults.nexusBaseUrl().orElseThrow(),
          CompatDefaults.nexusUsername().orElseThrow(),
          CompatDefaults.nexusPassword().orElseThrow());
      Endpoint candidate = new Endpoint(
          CompatDefaults.nexusPlusBaseUrl().orElseThrow(),
          CompatDefaults.nexusPlusUsername().orElseThrow(),
          CompatDefaults.nexusPlusPassword().orElseThrow());
      return new Config(enabled, reference, candidate);
    }
  }

  private record Endpoint(
      String baseUrl,
      String username,
      String password) {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    Endpoint as(String username, String password) {
      return new Endpoint(baseUrl, username, password);
    }

    JsonNode extDirect(String action, String method, String... data) throws Exception {
      String payload = "[{\"action\":\"" + action + "\",\"method\":\"" + method
          + "\",\"type\":\"rpc\",\"tid\":1,\"data\":[" + String.join(",", data) + "]}]";
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/service/extdirect"))
          .timeout(TIMEOUT)
          .header("Authorization", basic(username, password))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      assertEquals(200, response.statusCode(), baseUrl + " ExtDirect HTTP status");
      JsonNode root = MAPPER.readTree(response.body());
      JsonNode call = root.isArray() ? root.path(0) : root;
      assertNotNull(call.path("result"), baseUrl + " ExtDirect result");
      assertTrue(call.path("result").path("success").asBoolean(), baseUrl + " ExtDirect success: " + response.body());
      return call;
    }

    Exchange get(String path) throws Exception {
      return send("GET", path, HttpRequest.BodyPublishers.noBody(), null);
    }

    Exchange delete(String path) throws Exception {
      return send("DELETE", path, HttpRequest.BodyPublishers.noBody(), null);
    }

    Exchange postJson(String path, Object body) throws Exception {
      return send("POST", path, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8),
          "application/json");
    }

    private Exchange send(
        String method,
        String path,
        HttpRequest.BodyPublisher body,
        String contentType) throws Exception {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
          .timeout(TIMEOUT)
          .header("Authorization", basic(username, password))
          .header("Accept", "*/*");
      if (contentType != null) {
        builder.header("Content-Type", contentType);
      }
      HttpRequest request = builder.method(method, body).build();
      HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Exchange(response.statusCode(), response.body());
    }

    private static String basic(String username, String password) {
      String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
      return "Basic " + token;
    }
  }

  private record Exchange(int status, String bodyText) {
    void assertStatus(int expected, String label) {
      assertEquals(expected, status, label + ": " + bodyText);
    }

    void assertDeleted(String label) {
      assertTrue(status == 204 || status == 404, label + " status: " + status + " body: " + bodyText);
    }
  }

  private record NonAdminFixture(String privilegeId, String roleId, String userId, String password) {
  }

  private record ContentSelectorFixture(
      String selectorName,
      String privilegeId,
      String roleId,
      String userId,
      String password) {
  }

  private static Optional<String> setting(String property, String env) {
    String propertyValue = System.getProperty(property);
    if (propertyValue != null && !propertyValue.isBlank()) {
      return Optional.of(propertyValue.trim());
    }
    String envValue = System.getenv(env);
    return envValue == null || envValue.isBlank() ? Optional.empty() : Optional.of(envValue.trim());
  }

  private static String stripTrailingSlash(String value) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
