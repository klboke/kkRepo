package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.spi.ComponentPersistenceDialect.ComponentSearchDocument;
import com.github.klboke.kkrepo.persistence.jdbc.spi.ComponentPersistenceDialect.ComponentUpsert;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SecurityPersistenceDialect.PrivilegeInsert;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class MySqlDatabaseDialectTest {
  private final MySqlDatabaseDialect dialect = new MySqlDatabaseDialect();

  @Test
  void identifiesMySqlAndParsesStableDatabaseTypeIds() {
    assertEquals(DatabaseType.MYSQL, dialect.type());
    assertEquals(DatabaseType.MYSQL, DatabaseType.fromId(" MySQL "));
    assertEquals(DatabaseType.POSTGRESQL, DatabaseType.fromId("postgresql"));
    assertThrows(IllegalArgumentException.class, () -> DatabaseType.fromId("oracle"));
  }

  @Test
  void exposesMySqlJsonAndSearchSemantics() {
    assertEquals(
        "JSON_UNQUOTE(JSON_EXTRACT(c.attributes_json, '$.distPath'))",
        dialect.json().extractText("c.attributes_json", "distPath"));
    assertEquals(
        "JSON_SET(options_json, '$.packageMigrationEnabled', true)",
        dialect.json().setBoolean("options_json", true, "packageMigrationEnabled"));
    assertEquals(
        "MATCH(s.namespace, s.name, s.version, s.keywords) AGAINST (? IN BOOLEAN MODE)",
        dialect.search().componentSearchPredicate("s"));
    assertEquals(
        "+com* +example* +artifact* +1* +0*",
        dialect.search().prepareComponentQuery("\"".repeat(4096) + "Com.Example artifact-1.0"));
    assertThrows(IllegalArgumentException.class,
        () -> dialect.json().extractText("attributes_json; DROP TABLE asset", "key"));
    assertThrows(IllegalArgumentException.class,
        () -> dialect.search().componentSearchPredicate("s JOIN component"));
  }

  @Test
  void keepsMySqlCoordinationAndInsertIgnoreStatementsInsideBackend() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

    assertEquals(
        "COALESCE(TIMESTAMPDIFF(SECOND, MIN(enqueued_at), NOW(3)), 0)",
        dialect.coordination().oldestBacklogAgeSecondsExpression("enqueued_at"));

    dialect.security().insertPrivilegeIfAbsent(jdbc,
        new PrivilegeInsert("read", "Read", "Read", "repository-view", false, "{}"));
    dialect.security().assignRoleIfAbsent(jdbc, 1L, "developers");
    dialect.security().grantPrivilegeIfAbsent(jdbc, "developers", "read");
    dialect.security().inheritRoleIfAbsent(jdbc, "developers", "readers");

    assertTrue(jdbc.sql.stream().allMatch(sql -> sql.contains("INSERT IGNORE")));
  }

  @Test
  void componentAndCacheOperationsKeepAtomicLastInsertIdSqlOnOneConnection() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

    long componentId = dialect.components().upsertAndReturnId(jdbc, new ComponentUpsert(
        11L,
        "maven2",
        "com.acme",
        "library",
        "1.0.0",
        "release",
        new byte[32],
        "{}",
        Timestamp.valueOf("2026-07-13 12:00:00")));
    dialect.components().upsertSearchDocument(jdbc, new ComponentSearchDocument(
        componentId, 11L, "maven2", "com.acme", "library", "1.0.0", "telemetry"));
    long cacheVersion = dialect.coordination().bumpCacheVersion(jdbc, "security");

    assertEquals(7L, componentId);
    assertEquals(7L, cacheVersion);
    assertTrue(jdbc.sql.get(0).contains("ON DUPLICATE KEY UPDATE"));
    assertTrue(jdbc.sql.get(0).contains("id = LAST_INSERT_ID(id)"));
    assertEquals("SELECT LAST_INSERT_ID()", jdbc.sql.get(1));
    assertTrue(jdbc.sql.get(2).contains("INSERT INTO component_search"));
    assertTrue(jdbc.sql.get(2).contains("ON DUPLICATE KEY UPDATE"));
    assertTrue(jdbc.sql.get(3).contains("version = LAST_INSERT_ID(version + 1)"));
    assertEquals("SELECT LAST_INSERT_ID()", jdbc.sql.get(4));
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<String> sql = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      this.sql.add(sql);
      return 1;
    }

    @Override
    public <T> T execute(ConnectionCallback<T> action) {
      try {
        return action.doInConnection(connection(sql));
      } catch (java.sql.SQLException e) {
        throw new AssertionError(e);
      }
    }
  }

  private static Connection connection(List<String> sql) {
    return (Connection) Proxy.newProxyInstance(
        MySqlDatabaseDialectTest.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        (proxy, method, args) -> {
          if ("prepareStatement".equals(method.getName())) {
            sql.add((String) args[0]);
            return preparedStatement();
          }
          return defaultValue(method.getReturnType());
        });
  }

  private static PreparedStatement preparedStatement() {
    return (PreparedStatement) Proxy.newProxyInstance(
        MySqlDatabaseDialectTest.class.getClassLoader(),
        new Class<?>[]{PreparedStatement.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "executeUpdate" -> 1;
          case "executeQuery" -> resultSet();
          default -> defaultValue(method.getReturnType());
        });
  }

  private static ResultSet resultSet() {
    AtomicInteger next = new AtomicInteger();
    return (ResultSet) Proxy.newProxyInstance(
        MySqlDatabaseDialectTest.class.getClassLoader(),
        new Class<?>[]{ResultSet.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "next" -> next.getAndIncrement() == 0;
          case "getLong" -> 7L;
          default -> defaultValue(method.getReturnType());
        });
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == char.class) {
      return '\0';
    }
    return 0;
  }
}
