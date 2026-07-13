package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import com.github.klboke.kkrepo.server.BlobStoresController;
import com.github.klboke.kkrepo.server.BlobStoresController.BlobStoreRequest;
import com.github.klboke.kkrepo.server.KkRepoApplication;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenHostedService;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.CreateCommand;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.GroupSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.HostedSettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryCommands.ProxySettings;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.security.SecurityPayloads.AdminBootstrapCommand;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Full runtime smoke matrix for fresh migration, repeat startup, health, and cross-node sessions. */
class DatabaseServerSmokeTest {
  private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
      .withDatabaseName("kkrepo")
      .withUsername("kkrepo")
      .withPassword("kkrepo");
  private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer(
      System.getProperty("kkrepo.test.postgresql.image", "postgres:12"))
      .withDatabaseName("kkrepo")
      .withUsername("kkrepo")
      .withPassword("kkrepo");

  @AfterAll
  static void stopDatabases() {
    if (MYSQL.isRunning()) {
      MYSQL.stop();
    }
    if (POSTGRESQL.isRunning()) {
      POSTGRESQL.stop();
    }
  }

  @Test
  void mysqlSupportsFreshAndRepeatStartupWithCrossNodeSession() throws Exception {
    MYSQL.start();
    smoke(
        DatabaseType.MYSQL,
        MYSQL.getJdbcUrl() + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&allowPublicKeyRetrieval=true&connectionTimeZone=UTC",
        MYSQL.getUsername(),
        MYSQL.getPassword());
  }

  @Test
  void postgresqlSupportsFreshAndRepeatStartupWithCrossNodeSession() throws Exception {
    POSTGRESQL.start();
    smoke(DatabaseType.POSTGRESQL, POSTGRESQL.getJdbcUrl(),
        POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
  }

  private static void smoke(
      DatabaseType type,
      String url,
      String username,
      String password) throws Exception {
    Map<String, Object> properties = properties(type, url, username, password);
    try (ConfigurableApplicationContext first = start(properties);
         ConfigurableApplicationContext second = start(properties)) {
      assertEquals(type, first.getBean(DatabaseDialect.class).type());
      assertEquals(type, second.getBean(DatabaseDialect.class).type());
      assertEquals(0, first.getBean(Flyway.class).info().pending().length);
      assertEquals(0, second.getBean(Flyway.class).info().pending().length);
      assertTrue(((WebServerApplicationContext) first).getWebServer().getPort() > 0);
      assertEquals("UP", first.getBean(HealthEndpoint.class).health().getStatus().getCode());
      assertEquals("UP", second.getBean(HealthEndpoint.class).health().getStatus().getCode());

      exerciseSharedApplicationFlows(first, second);

      @SuppressWarnings("unchecked")
      FindByIndexNameSessionRepository<Session> firstSessions =
          (FindByIndexNameSessionRepository<Session>) first.getBean(
              FindByIndexNameSessionRepository.class);
      @SuppressWarnings("unchecked")
      FindByIndexNameSessionRepository<Session> secondSessions =
          (FindByIndexNameSessionRepository<Session>) second.getBean(
              FindByIndexNameSessionRepository.class);
      Session created = firstSessions.createSession();
      created.setAttribute("replica", "node-one");
      firstSessions.save(created);
      Session loaded = secondSessions.findById(created.getId());
      assertNotNull(loaded);
      assertEquals("node-one", loaded.getAttribute("replica"));
    }
  }

  private static void exerciseSharedApplicationFlows(
      ConfigurableApplicationContext first,
      ConfigurableApplicationContext second) throws Exception {
    SecurityManagementService firstSecurity = first.getBean(SecurityManagementService.class);
    SecurityManagementService secondSecurity = second.getBean(SecurityManagementService.class);
    assertTrue(firstSecurity.adminBootstrapStatus().required());
    firstSecurity.initializeAdmin(new AdminBootstrapCommand(
        "SmokeAdmin1234!", "SmokeAdmin1234!", false));
    assertFalse(secondSecurity.adminBootstrapStatus().required());
    assertEquals("admin", secondSecurity.findUser("Local", "admin").orElseThrow().userId());

    String blobPath = Files.createTempDirectory("kkrepo-smoke-blob-").toString();
    first.getBean(BlobStoresController.class).create(new BlobStoreRequest(
        "smoke-file", "file", "file", null, null, null, null, blobPath,
        null, null, null));

    RepositoryService firstRepositories = first.getBean(RepositoryService.class);
    var hosted = firstRepositories.create(new CreateCommand(
        "smoke-hosted", "maven2-hosted", true, "smoke-file", true,
        new HostedSettings("ALLOW", "RELEASE", "STRICT"),
        null, null, null, null, null));
    var proxy = firstRepositories.create(new CreateCommand(
        "smoke-proxy", "maven2-proxy", true, "smoke-file", true,
        null, new ProxySettings("http://127.0.0.1:9/maven2", 1440, 1440, true),
        null, null, null, null));
    var group = firstRepositories.create(new CreateCommand(
        "smoke-group", "maven2-group", true, "smoke-file", true,
        null, null, null, null, null,
        new GroupSettings(List.of("smoke-hosted", "smoke-proxy"))));
    assertEquals(RepositoryType.HOSTED, hosted.type());
    assertEquals(RepositoryType.PROXY, proxy.type());
    assertEquals(RepositoryType.GROUP, group.type());
    assertEquals(List.of("smoke-hosted", "smoke-proxy"), group.group().memberNames());
    RepositoryService secondRepositories = second.getBean(RepositoryService.class);
    assertEquals(RepositoryType.HOSTED, secondRepositories.get("smoke-hosted").type());
    assertEquals(RepositoryType.PROXY, secondRepositories.get("smoke-proxy").type());
    assertEquals(RepositoryType.GROUP, secondRepositories.get("smoke-group").type());

    RepositoryRuntimeRegistry firstRuntimes = first.getBean(RepositoryRuntimeRegistry.class);
    MavenHostedService firstMaven = first.getBean(MavenHostedService.class);
    byte[] artifact = "dual-database-smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var path = firstMaven.parser().parsePath("/com/acme/smoke/1.0/smoke-1.0.jar");
    assertEquals(201, firstMaven.put(
        firstRuntimes.resolve("smoke-hosted").orElseThrow(), path,
        new ByteArrayInputStream(artifact), "application/java-archive", "admin", "127.0.0.1")
        .status());

    second.getBean(BlobStorageRegistry.class).refreshAll();
    RepositoryRuntimeRegistry secondRuntimes = second.getBean(RepositoryRuntimeRegistry.class);
    var downloaded = second.getBean(MavenHostedService.class).get(
        secondRuntimes.resolve("smoke-hosted").orElseThrow(), path, false);
    assertEquals(200, downloaded.status());
    try (var body = downloaded.body()) {
      assertEquals("dual-database-smoke",
          new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    MetadataRebuildDao firstMarkers = first.getBean(MetadataRebuildDao.class);
    MetadataRebuildDao secondMarkers = second.getBean(MetadataRebuildDao.class);
    long markerCount = secondMarkers.countBacklog();
    firstMarkers.enqueue(hosted.id(), "server-smoke-explicit");
    assertEquals(markerCount + 1, secondMarkers.countBacklog());

    CacheVersionDao firstVersions = first.getBean(CacheVersionDao.class);
    CacheVersionDao secondVersions = second.getBean(CacheVersionDao.class);
    assertEquals(firstVersions.bump("server-smoke"), secondVersions.current("server-smoke"));

    first.getBean(SecurityAuditDao.class).insert(new SecurityAuditDao.AuditLogRecord(
        LocalDateTime.of(2026, 7, 13, 18, 0), "Local", "admin", "Local", null,
        "127.0.0.1", "PUT", "/repository/smoke-hosted/com/acme/smoke/1.0/smoke-1.0.jar",
        "repository-view", 201, "SUCCESS", Map.of("node", "one")));
    var audit = second.getBean(SecurityAuditDao.class).search(new SecurityAuditDao.AuditLogQuery(
        "smoke-hosted", null, null, null, null, null, null, null, null,
        null, null, 0, 10));
    assertEquals(1, audit.total());
    assertEquals("admin", audit.items().getFirst().actorUserId());
  }

  private static ConfigurableApplicationContext start(Map<String, Object> properties) {
    String[] arguments = properties.entrySet().stream()
        .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
        .toArray(String[]::new);
    return new SpringApplicationBuilder(KkRepoApplication.class)
        .profiles("test")
        .run(arguments);
  }

  private static Map<String, Object> properties(
      DatabaseType type,
      String url,
      String username,
      String password) throws Exception {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("server.port", "0");
    values.put("management.server.port", "0");
    values.put("spring.datasource.url", url);
    values.put("spring.datasource.username", username);
    values.put("spring.datasource.password", password);
    values.put("spring.datasource.hikari.maximum-pool-size", "4");
    values.put("spring.datasource.hikari.minimum-idle", "0");
    values.put("kkrepo.database.type", type.id());
    values.put("kkrepo.storage.file.base-dir", Files.createTempDirectory("kkrepo-smoke-").toString());
    values.put("kkrepo.security.encryption.credential-secret",
        "smoke-credential-secret-0123456789abcdef");
    values.put("kkrepo.security.encryption.api-key-payload-secret",
        "smoke-api-key-secret-0123456789abcdef");
    values.put("kkrepo.catalog-cache.refresh-interval-ms", "3600000");
    values.put("kkrepo.catalog-cache.initial-delay-ms", "3600000");
    values.put("kkrepo.catalog-cache.jdbc.initial-delay-ms", "3600000");
    values.put("kkrepo.blob-gc.enabled", "false");
    values.put("kkrepo.repository-index-rebuild.enabled", "false");
    values.put("kkrepo.maven.metadata-rebuild.enabled", "false");
    values.put("kkrepo.storage.file.temp-cleanup-enabled", "false");
    values.put("kkrepo.security.outbound.allow-private-addresses", "true");
    return values;
  }
}
