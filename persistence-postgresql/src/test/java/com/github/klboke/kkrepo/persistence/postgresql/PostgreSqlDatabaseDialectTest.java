package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgreSqlDatabaseDialectTest {
  private final PostgreSqlDatabaseDialect dialect = new PostgreSqlDatabaseDialect();

  @Test
  void identifiesPostgreSqlAndBindsJsonbValues() throws Exception {
    assertEquals(DatabaseType.POSTGRESQL, dialect.type());

    PGobject value = assertInstanceOf(PGobject.class, dialect.json().jdbcValue("{\"active\":true}"));
    assertEquals("jsonb", value.getType());
    assertEquals("{\"active\":true}", value.getValue());
  }

  @Test
  void exposesPostgreSqlJsonSearchAndCoordinationSemantics() {
    assertEquals(
        "c.attributes_json #>> '{distPath}'",
        dialect.json().extractText("c.attributes_json", "distPath"));
    assertEquals(
        "jsonb_set(options_json, '{packageMigrationEnabled}', 'true'::jsonb, true)",
        dialect.json().setBoolean("options_json", true, "packageMigrationEnabled"));
    assertEquals(
        "s.search_vector @@ to_tsquery('simple', ?)",
        dialect.search().componentSearchPredicate("s"));
    assertEquals(
        "com:* & example:* & artifact:* & 1:* & 0:*",
        dialect.search().prepareComponentQuery("Com.Example artifact-1.0"));
    assertEquals("", dialect.search().prepareComponentQuery("  --  "));
    assertEquals(
        "COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(enqueued_at))), 0)",
        dialect.coordination().oldestBacklogAgeSecondsExpression("enqueued_at"));

    assertThrows(IllegalArgumentException.class,
        () -> dialect.json().extractText("attributes_json; DROP TABLE asset", "key"));
    assertThrows(IllegalArgumentException.class,
        () -> dialect.json().extractText("attributes_json", "unsafe-key"));
    assertThrows(IllegalArgumentException.class,
        () -> dialect.json().extractText("attributes_json"));
    assertThrows(IllegalArgumentException.class,
        () -> dialect.search().componentSearchPredicate("s JOIN component"));
    assertThrows(IllegalArgumentException.class,
        () -> dialect.coordination().oldestBacklogAgeSecondsExpression("created_at) FROM asset"));
  }

  @Test
  void keepsPostgreSqlConflictHandlingInsideBackend() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();

    dialect.security().insertPrivilegeIfAbsent(jdbc,
        new PrivilegeInsert("read", "Read", "Read", "repository-view", false, "{}"));
    dialect.security().assignRoleIfAbsent(jdbc, 1L, "developers");
    dialect.security().grantPrivilegeIfAbsent(jdbc, "developers", "read");
    dialect.security().inheritRoleIfAbsent(jdbc, "developers", "readers");

    assertEquals(4, jdbc.sql.size());
    assertTrue(jdbc.sql.stream().allMatch(sql -> sql.contains("ON CONFLICT")));
    assertTrue(jdbc.sql.stream().allMatch(sql -> sql.contains("DO NOTHING")));
  }

  @Test
  void componentAndCacheOperationsUseAtomicReturningStatements() {
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
    assertEquals(9L, cacheVersion);
    assertTrue(jdbc.sql.get(0).contains("ON CONFLICT (repository_id, coordinate_hash)"));
    assertTrue(jdbc.sql.get(0).contains("RETURNING id"));
    assertTrue(jdbc.sql.get(1).contains("ON CONFLICT (component_id)"));
    assertTrue(jdbc.sql.get(2).contains("version = cache_version.version + 1"));
    assertTrue(jdbc.sql.get(2).contains("RETURNING version"));
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final List<String> sql = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      this.sql.add(sql);
      return 1;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      this.sql.add(sql);
      return requiredType.cast(9L);
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
        PostgreSqlDatabaseDialectTest.class.getClassLoader(),
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
        PostgreSqlDatabaseDialectTest.class.getClassLoader(),
        new Class<?>[]{PreparedStatement.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "executeQuery" -> resultSet();
          default -> defaultValue(method.getReturnType());
        });
  }

  private static ResultSet resultSet() {
    AtomicInteger next = new AtomicInteger();
    return (ResultSet) Proxy.newProxyInstance(
        PostgreSqlDatabaseDialectTest.class.getClassLoader(),
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
