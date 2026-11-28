package com.github.klboke.kkrepo.migration.nexus.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.DatabaseConnectionSettings;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationCheckpointDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationJobRecord;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NexusSecurityJsonMigrationCliTest {
  @TempDir
  Path tempDir;

  @Test
  void parsesRequiredDatabaseAndExportArguments() {
    NexusSecurityJsonMigrationCli.Options options = NexusSecurityJsonMigrationCli.Options.parse(new String[] {
        "--export", "/tmp/security-export.json",
        "--jdbc-url", "jdbc:mysql://127.0.0.1:3306/kkrepo",
        "--username", "kkrepo",
        "--password", "secret",
        "--source-nexus-version", "3.29.2-02",
        "--source-data-path", "/nexus-data"
    });

    assertEquals(Path.of("/tmp/security-export.json"), options.exportPath());
    assertEquals("jdbc:mysql://127.0.0.1:3306/kkrepo", options.jdbcUrl());
    assertEquals("kkrepo", options.username());
    assertEquals("secret", options.password());
    assertEquals("3.29.2-02", options.sourceNexusVersion());
    assertEquals("/nexus-data", options.sourceDataPath());
  }

  @Test
  void printsUsageWithoutOpeningDatabaseWhenHelpIsRequested() {
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    int exitCode = new NexusSecurityJsonMigrationCli().run(
        new String[] {"--help"},
        new PrintStream(outBytes),
        new PrintStream(errBytes));

    assertEquals(0, exitCode);
    assertTrue(outBytes.toString().contains("--export /path/to/security-export.json"));
    assertTrue(outBytes.toString().contains("3  migration incomplete"));
    assertEquals("", errBytes.toString());
  }

  @Test
  void rejectsInvalidArgumentsBeforeOpeningTheDatabase() {
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    int exitCode = new NexusSecurityJsonMigrationCli(settings -> {
      throw new AssertionError("database must not be opened");
    }).run(new String[] {"--export"}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errBytes));

    assertEquals(2, exitCode);
    assertTrue(errBytes.toString().contains("Missing value for --export"));
    assertTrue(errBytes.toString().contains("Usage:"));
  }

  @Test
  void migratesAnEmptyExportAndFinishesTheJob() throws Exception {
    Path export = tempDir.resolve("empty-security-export.json");
    Files.writeString(export, "{}");
    StoresFixture fixture = new StoresFixture();
    AtomicReference<DatabaseConnectionSettings> openedWith = new AtomicReference<>();
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    int exitCode = new NexusSecurityJsonMigrationCli(settings -> {
      openedWith.set(settings);
      return fixture.stores();
    }).run(arguments(export), new PrintStream(outBytes), new PrintStream(errBytes));

    assertEquals(0, exitCode);
    assertEquals("jdbc:mysql://db:3306/kkrepo", openedWith.get().url());
    assertEquals("finished", fixture.finishedStatus);
    assertEquals(41L, fixture.finishedSummary.get("jobId"));
    assertEquals(0, fixture.finishedSummary.get("checkpoints"));
    assertTrue(outBytes.toString().contains("\"status\":\"finished\""));
    assertEquals("", errBytes.toString());
    assertTrue(fixture.closed);
  }

  @Test
  void reportsNexusProUserTokensAsNotMigratedWithoutSecrets() throws Exception {
    String pair = "AliceName1:" + "p".repeat(44);
    Path export = tempDir.resolve("pro-security-export.json");
    Files.writeString(export, """
        {"apiKeys": [
          {"domain": "NpmToken", "api_key": "npm-secret", "primary_principal": "alice"},
          {"domain": "User-Token-Realm", "api_key": "%s", "primary_principal": "alice"},
          {"domain": "User-Token-Realm", "api_key": "BobNameCd2:x", "primary_principal": "bob",
           "principals": {"primaryPrincipal": "bob", "realmNames": ["LdapRealm"]}}
        ]}
        """.formatted(pair));
    StoresFixture fixture = new StoresFixture();
    fixture.allowWrites = true;
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    int exitCode = new NexusSecurityJsonMigrationCli(settings -> fixture.stores())
        .run(arguments(export), new PrintStream(outBytes), new PrintStream(new ByteArrayOutputStream()));

    // Incomplete: nonzero so automation stops before cutover, with the JSON report still printed.
    assertEquals(NexusSecurityJsonMigrationCli.EXIT_INCOMPLETE, exitCode);
    assertTrue(outBytes.toString().contains("\"status\":\"finished_with_validation_failures\""), outBytes.toString());
    assertEquals(java.util.List.of("NpmToken"), fixture.apiKeyDomains);
    assertEquals("finished_with_validation_failures", fixture.finishedStatus);
    NexusUserTokenReport userTokens = (NexusUserTokenReport) fixture.finishedSummary.get("userTokens");
    assertEquals(2, userTokens.tokens());
    assertEquals(java.util.List.of("LDAP/bob", "Local/alice"), userTokens.owners());
    String output = outBytes.toString();
    assertTrue(output.contains("\"owners\":[\"LDAP/bob\",\"Local/alice\"]"), output);
    for (String secret : java.util.List.of(pair, "AliceName1", "BobNameCd2")) {
      assertTrue(!output.contains(secret) && !String.valueOf(fixture.finishedSummary).contains(secret), secret);
    }
  }

  @Test
  void marksTheJobFailedWhenTheExportCannotBeRead() {
    StoresFixture fixture = new StoresFixture();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    int exitCode = new NexusSecurityJsonMigrationCli(settings -> fixture.stores()).run(
        arguments(tempDir.resolve("missing.json")),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(errBytes));

    assertEquals(1, exitCode);
    assertEquals("failed", fixture.finishedStatus);
    assertEquals(41L, fixture.finishedSummary.get("jobId"));
    assertEquals("failed", fixture.finishedSummary.get("status"));
    assertTrue(errBytes.toString().contains("Nexus security migration failed:"));
    assertTrue(fixture.closed);
  }

  private static String[] arguments(Path export) {
    return new String[] {
        "--export", export.toString(),
        "--jdbc-url", "jdbc:mysql://db:3306/kkrepo",
        "--username", "kkrepo",
        "--password", "secret"
    };
  }

  private static final class StoresFixture {
    private String finishedStatus;
    private Map<String, Object> finishedSummary;
    private boolean closed;
    private boolean allowWrites;
    private final java.util.List<String> apiKeyDomains = new java.util.ArrayList<>();

    private final MigrationJobDao migrationJobs = new MigrationJobDao() {
      @Override
      public long create(String sourceNexusVersion, String sourceDataPath, Map<String, Object> options) {
        return 41L;
      }

      @Override
      public Optional<MigrationJobRecord> findById(long id) {
        return Optional.empty();
      }

      @Override
      public void markFinished(long id, String status, Map<String, Object> summary) {
        finishedStatus = status;
        finishedSummary = summary;
      }
    };

    private final MigrationCheckpointDao migrationCheckpoints = new MigrationCheckpointDao() {
      @Override
      public void upsert(MigrationCheckpointRecord record) {
        if (allowWrites) {
          return;
        }
        throw new AssertionError("empty exports must not write checkpoints");
      }

      @Override
      public Optional<MigrationCheckpointRecord> find(
          long jobId, String sourceDatabase, String sourceClass, String sourceRid) {
        return Optional.empty();
      }
    };

    private final SecurityDao security = (SecurityDao) Proxy.newProxyInstance(
        SecurityDao.class.getClassLoader(),
        new Class<?>[] {SecurityDao.class},
        (proxy, method, args) -> {
          if (!allowWrites) {
            throw new AssertionError("empty exports must not call SecurityDao." + method.getName());
          }
          if ("upsertApiKey".equals(method.getName())) {
            apiKeyDomains.add(((com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord) args[0]).domain());
          }
          return method.getName().startsWith("find") ? Optional.of(new Object()) : null;
        });

    private PersistenceStores stores() {
      return (PersistenceStores) Proxy.newProxyInstance(
          PersistenceStores.class.getClassLoader(),
          new Class<?>[] {PersistenceStores.class},
          (proxy, method, args) -> switch (method.getName()) {
            case "migrationJobs" -> migrationJobs;
            case "migrationCheckpoints" -> migrationCheckpoints;
            case "security" -> security;
            case "close" -> {
              closed = true;
              yield null;
            }
            default -> throw new AssertionError("Unexpected store access: " + method.getName());
          });
    }
  }
}
