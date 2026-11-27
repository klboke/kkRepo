package com.github.klboke.kkrepo.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.BlobStoresController;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.ApiKeyCommand;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two running replicas share one database. After a key reset on A, B still holds the old secret in
 * its node-local auth cache and issues a Docker bearer from it; that bearer must work nowhere.
 */
class ApiKeyRotationRuntimeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String REPOSITORY = "docker-rotation";
  private static final String SCOPE = "repository:" + REPOSITORY + "/app:pull,push";
  @TempDir Path directory;

  @Test
  void mysqlRejectsBearersIssuedFromStaleReplicaCache() throws Exception {
    try (var database = new MySQLContainer("mysql:8.0")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      verify("mysql", database.getJdbcUrl()
          + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC");
    }
  }

  @Test
  void postgresRejectsBearersIssuedFromStaleReplicaCache() throws Exception {
    try (var database = new PostgreSQLContainer("postgres:12")
        .withDatabaseName("kkrepo").withUsername("kkrepo").withPassword("kkrepo")) {
      database.start();
      verify("postgresql", database.getJdbcUrl());
    }
  }

  private void verify(String databaseType, String jdbcUrl) throws Exception {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.port", "0");
    properties.put("management.server.port", "0");
    properties.put("spring.datasource.url", jdbcUrl);
    properties.put("spring.datasource.username", "kkrepo");
    properties.put("spring.datasource.password", "kkrepo");
    properties.put("spring.datasource.hikari.maximum-pool-size", "8");
    properties.put("kkrepo.database.type", databaseType);
    properties.put("kkrepo.storage.file.base-dir", directory.toString());
    properties.put("kkrepo.security.encryption.credential-secret", "rotation-runtime-credential-secret");
    properties.put("kkrepo.security.encryption.api-key-payload-secret", "rotation-runtime-api-key-secret");
    properties.put("kkrepo.catalog-cache.refresh-interval-ms", "3600000");
    properties.put("kkrepo.catalog-cache.initial-delay-ms", "3600000");
    properties.put("kkrepo.catalog-cache.jdbc.initial-delay-ms", "3600000");
    properties.put("kkrepo.blob-gc.enabled", "false");
    properties.put("kkrepo.repository-index-rebuild.enabled", "false");
    properties.put("kkrepo.maven.metadata-rebuild.enabled", "false");
    properties.put("kkrepo.storage.file.temp-cleanup-enabled", "false");
    properties.put("kkrepo.security.default-authenticated-role-id", "");
    // The production default: the stale window below lies well within it.
    properties.put("kkrepo.cache.api-key.ttl-seconds", "60");
    try (var a = start(properties); var b = start(properties); var client = HttpClient.newHttpClient()) {
      var security = a.getBean(SecurityManagementService.class);
      security.initializeAdmin(new AdminBootstrapCommand("12345678", "12345678", false));
      a.getBean(BlobStoresController.class).create(new BlobStoresController.BlobStoreRequest(
          "default", "file", "file", null, null, null, null, directory.resolve("blobs").toString(),
          null, null, null));
      a.getBean(RepositoryService.class).create(new CreateCommand(
          REPOSITORY, "docker-hosted", true, "default", false,
          new HostedSettings("ALLOW", null, null), null, null, null, null, null));
      b.getBean(BlobStorageRegistry.class).refreshAll();

      verifyRotation(client, security, a, b, UserTokens.DOMAIN, ApiKeyRotationRuntimeTest::userToken);
      verifyRotation(client, security, a, b, "nexus", token -> Map.of("X-Nexus-Plus-Token", token));
    }
  }

  private static void verifyRotation(HttpClient client, SecurityManagementService security,
      ConfigurableApplicationContext a, ConfigurableApplicationContext b, String domain,
      java.util.function.Function<String, Map<String, String>> credential) throws Exception {
    var created = security.createApiKeyForOwner("Local", "admin",
        new ApiKeyCommand(domain, null, null, "ci", null, List.of(), null, null, null));
    var oldPair = credential.apply(created.token());
    // Warm B's node-local cache with the old secret.
    assertWorksEverywhere(client, a, b, bearer(client, b, oldPair, 200), domain + " before reset");

    var reset = security.resetApiKeyForOwner(created.apiKey().id(), "Local", "admin");
    assertEquals(created.apiKey().id(), reset.apiKey().id());
    assertNotEquals(created.token(), reset.token());

    // A reads the reset row; B still answers from its cache and issues a bearer from the old secret.
    bearer(client, a, oldPair, 401);
    String stale = bearer(client, b, oldPair, 200);
    for (var replica : List.of(a, b)) {
      assertEquals(401, upload(client, replica, stale).statusCode(),
          domain + ": a bearer issued from the old secret must be rejected on " + url(replica));
    }

    var newPair = credential.apply(reset.token());
    assertWorksEverywhere(client, a, b, bearer(client, b, newPair, 200), domain + " new secret via B");
    assertWorksEverywhere(client, a, b, bearer(client, a, newPair, 200), domain + " new secret via A");
  }

  private static void assertWorksEverywhere(HttpClient client, ConfigurableApplicationContext a,
      ConfigurableApplicationContext b, String bearer, String label) throws Exception {
    for (var replica : List.of(a, b)) {
      var response = upload(client, replica, bearer);
      assertEquals(202, response.statusCode(), label + " on " + url(replica) + ": " + response.body());
    }
  }

  private static String bearer(HttpClient client, ConfigurableApplicationContext replica,
      Map<String, String> credential, int expectedStatus) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url(replica) + "/service/rest/v1/docker/token?service="
        + URLEncoder.encode(URI.create(url(replica)).getAuthority(), StandardCharsets.UTF_8)
        + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(30)).GET();
    credential.forEach(builder::header);
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), "token endpoint on " + url(replica) + ": " + response.body());
    return expectedStatus == 200 ? JSON.readTree(response.body()).path("token").asText() : null;
  }

  private static HttpResponse<String> upload(HttpClient client, ConfigurableApplicationContext replica,
      String bearer) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url(replica) + "/v2/" + REPOSITORY + "/app/blobs/uploads/"))
        .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + bearer)
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, String> userToken(String token) {
    return Map.of("Authorization", "Basic "
        + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
  }

  private static ConfigurableApplicationContext start(Map<String, Object> properties) {
    return new SpringApplicationBuilder(KkRepoApplication.class).profiles("test").run(properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
  }

  private static String url(ConfigurableApplicationContext context) {
    return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
  }
}
