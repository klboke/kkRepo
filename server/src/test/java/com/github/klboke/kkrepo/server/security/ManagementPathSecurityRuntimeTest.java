package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.server.BlobStoresController;
import com.github.klboke.kkrepo.server.BlobStoresController.BlobStoreRequest;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.RoleCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.UserCommand;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises the real Tomcat, filter chain, MVC mappings, and persisted security state. */
class ManagementPathSecurityRuntimeTest {
  private static final String PASSWORD = "PathSecurityTest123!";
  private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16")
      .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo");
  @TempDir
  static Path storage;
  private static ConfigurableApplicationContext context;
  private static HttpClient client;
  private static SecurityManagementService security;
  private static String baseUrl;

  @BeforeAll
  static void start() {
    DATABASE.start();
    context = new SpringApplicationBuilder(KkRepoApplication.class).profiles("test").run(
        "--server.port=0", "--management.server.port=0", "--server.servlet.context-path=/kkrepo",
        "--spring.datasource.url=" + DATABASE.getJdbcUrl(),
        "--spring.datasource.username=" + DATABASE.getUsername(),
        "--spring.datasource.password=" + DATABASE.getPassword(),
        "--spring.datasource.hikari.maximum-pool-size=4",
        "--kkrepo.database.type=postgresql",
        "--kkrepo.storage.file.base-dir=" + storage,
        "--kkrepo.security.encryption.credential-secret=path-test-credential-secret-0123456789",
        "--kkrepo.security.encryption.api-key-payload-secret=path-test-api-key-secret-0123456789",
        "--kkrepo.security.rate-limit.login-per-minute=0",
        "--kkrepo.blob-gc.enabled=false",
        "--kkrepo.repository-index-rebuild.enabled=false",
        "--kkrepo.maven.metadata-rebuild.enabled=false",
        "--kkrepo.storage.file.temp-cleanup-enabled=false");
    security = context.getBean(SecurityManagementService.class);
    security.initializeAdmin(new AdminBootstrapCommand(PASSWORD, PASSWORD, false));
    security.saveUser(new UserCommand("Local", "reader", "Read", "Only", "reader@example.com",
        PASSWORD, null, "active", null, List.of(), Map.of()));
    security.saveUser(new UserCommand("Local", "password-target", "Password", "Target", "target@example.com",
        PASSWORD, null, "active", null, List.of("nx-admin"), Map.of()));
    baseUrl = "http://127.0.0.1:"
        + ((WebServerApplicationContext) context).getWebServer().getPort() + "/kkrepo";
    client = HttpClient.newHttpClient();
  }

  @AfterAll
  static void stop() {
    if (client != null) {
      client.close();
    }
    if (context != null) {
      context.close();
    }
    DATABASE.stop();
  }

  @Test
  void componentUploadPersistsContentOnlyForAnAuthorizedWriter() throws Exception {
    context.getBean(BlobStoresController.class).create(new BlobStoreRequest(
        "upload-files", "file", "file", null, null, null, null, storage.resolve("uploads").toString(),
        null, null, null));
    var repository = context.getBean(RepositoryService.class).create(new CreateCommand(
        "upload-permissions", "raw-hosted", true, "upload-files", true,
        new HostedSettings("ALLOW_ONCE", null, null), null, null, null, null, null));
    var assets = context.getBean(AssetDao.class);
    String path = "/service/rest/v1/components?repository=" + repository.name();
    String type = "multipart/form-data; boundary=upload-check";
    String body = "--upload-check\r\n"
        + "Content-Disposition: form-data; name=\"raw.asset1\"; filename=\"sample.txt\"\r\n"
        + "Content-Type: text/plain\r\n\r\nsample content\r\n--upload-check--\r\n";

    assertEquals(401, send("POST", path, null, body, type).statusCode());
    assertEquals(403, send("POST", path, "reader", body, type).statusCode());
    assertTrue(assets.findAssetByPath(repository.id(), "sample.txt").isEmpty());
    assertEquals(204, send("POST", path, "admin", body, type).statusCode());
    assertEquals("sample content", send("GET", "/repository/" + repository.name() + "/sample.txt",
        "admin", "", null).body());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/service/rest/v1/security/users",
      "/service/rest/v1/%73ecurity/users",
      "/service/rest/v1/security;x/users",
      "/%73ervice/rest/v1/security/users",
      "/service;x/rest;y/v1;z/security;w/users;q",
      "/service/rest/v1/security/%75sers"
  })
  void anonymousCannotCreateAdministrator(String path) throws Exception {
    String userId = "probe" + Long.toUnsignedString(System.nanoTime());
    var response = send("POST", path, null, userJson(userId), "application/json");
    assertFalse(security.findUser("Local", userId).isPresent(),
        () -> "Unauthenticated administrator persisted through " + path + ": " + response.statusCode());
    assertEquals(403, response.statusCode(), path);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/service/rest/v1/security/users",
      "/service/rest/v1/%73ecurity/users",
      "/service/rest/v1/security;x/users",
      "/internal/security/ldap",
      "/internal/%73ecurity/ldap",
      "/%69nternal/security/ldap",
      "/internal;x/security;y/ldap;z"
  })
  void equivalentRoutesKeepAuthenticationAndPermissions(String path) throws Exception {
    assertEquals(401, send("GET", path, null, "", null).statusCode(), path);
    assertEquals(403, send("GET", path, "reader", "", null).statusCode(), path);
    assertEquals(200, send("GET", path, "admin", "", null).statusCode(), path);
  }

  @ParameterizedTest
  @ValueSource(strings = {"%73ecurity", "security;x"})
  void passwordChangeCannotBypassAuthenticationOrAdministratorPermission(String segment) throws Exception {
    security.changePassword("password-target", PASSWORD);
    String path = "/service/rest/v1/" + segment + "/users/password-target/change-password";
    assertEquals(403, send("PUT", path, null, "ChangedPassword123!", "text/plain").statusCode());
    assertEquals(403, send("PUT", path, "reader", "ChangedPassword123!", "text/plain").statusCode());
    assertEquals(200, send("GET", "/service/rest/v1/security/users", "password-target", "", null).statusCode());
  }

  @Test
  void realmListReturnsPublicMetadataToSettingsReadersAndAdministrators() throws Exception {
    security.saveRole(new RoleCommand("realm-reader", "Local", "Realm reader", null, false,
        List.of("nx-settings-read"), List.of(), Map.of()));
    security.saveUser(new UserCommand("Local", "settings-reader", "Settings", "Reader", "settings@example.com",
        PASSWORD, null, "active", null, List.of("realm-reader"), Map.of()));
    var mapper = new ObjectMapper();
    String marker = UUID.randomUUID().toString();
    var configured = send("PUT", "/internal/security/ldap", "admin",
        mapper.writeValueAsString(Map.of("enabled", false, "authPassword", marker)), "application/json");
    assertEquals(200, configured.statusCode());
    assertEquals("********", mapper.readTree(configured.body()).path("authPassword").asText());
    assertEquals(403, send("GET", "/internal/security/ldap", "settings-reader", "", null).statusCode());

    for (String user : List.of("settings-reader", "admin")) {
      var realms = send("GET", "/internal/security/realms", user, "", null);
      assertEquals(200, realms.statusCode());
      assertFalse(realms.body().contains(marker));
      var ldap = mapper.readTree(realms.body()).valueStream()
          .filter(realm -> realm.path("realmId").asText().equals("ldap")).findFirst().orElseThrow();
      assertEquals(mapper.valueToTree(Map.of("source", "LDAP", "nexusRealm", "LdapRealm")), ldap.path("attributes"));
    }
  }

  @Test
  void roleMappingUpdatesRequireRolePermissionAndPersistByProvider() throws Exception {
    String id = "external-mapping-reader";
    security.saveRole(new RoleCommand(id, "Local", "Mapped reader", null, false,
        List.of("nx-users-read"), List.of(), Map.of()));
    String rolePath = "/internal/security/roles/" + id;
    String role = """
        {"roleId":"ignored","name":"Mapped reader","privileges":["nx-users-read"],"roles":[],
         "externalMappings":[{"realmId":"oidc","externalName":"engineering"}]}
        """;
    assertEquals(200, send("PUT", rolePath, "admin", role, "application/json").statusCode());
    assertTrue(security.findRole("ignored").isEmpty());
    var initial = security.findRole(id).orElseThrow();
    assertEquals("oidc", initial.externalMappings().getFirst().realmId());

    String ldap = """
        [{"realmId":"ldap","externalName":"engineering"}]
        """;
    assertEquals(403, send("PUT", rolePath + "/external-mappings", "reader", ldap, "application/json").statusCode());
    assertEquals(initial.externalMappings(), security.findRole(id).orElseThrow().externalMappings());
    assertEquals(200, send("PUT", rolePath + "/external-mappings", "admin", ldap, "application/json").statusCode());
    var updated = security.findRole(id).orElseThrow();
    assertEquals(1, updated.externalMappings().size());
    assertEquals("ldap", updated.externalMappings().getFirst().realmId());
    assertEquals("engineering", updated.externalMappings().getFirst().externalName());
    assertEquals(initial.privileges(), updated.privileges());
  }

  @Test
  void authorizedEncodedMutationIsAuditedWithOriginalPath() throws Exception {
    String path = "/service/rest/v1/%73ecurity;x/users";
    assertEquals(200, send("POST", path, "admin", userJson("audited-user"), "application/json").statusCode());
    assertTrue(security.findUser("Local", "audited-user").isPresent());
    assertTrue(context.getBean(SecurityAuditDao.class).search(null).items().stream()
        .anyMatch(entry -> path.equals(entry.path()) && "admin".equals(entry.actorUserId())
            && "nexus:users:create".equals(entry.permission()) && entry.status() == 200));
  }

  private static String userJson(String id) {
    return """
        {"userId":"%s","firstName":"Path","lastName":"Test","emailAddress":"path@example.com",
         "password":"%s","status":"active","roles":["nx-admin"]}
        """.formatted(id, PASSWORD);
  }

  private static HttpResponse<String> send(
      String method, String path, String user, String body, String contentType) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20))
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (user != null) {
      request.header("Authorization", "Basic " + Base64.getEncoder()
          .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
    }
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
