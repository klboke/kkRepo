package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ComponentDaoTest {
  private static final MySqlDatabaseDialect DIALECT = new MySqlDatabaseDialect();

  @Test
  void upsertReturningIdAlwaysTouchesDatabaseInsteadOfServingWritesFromLocalCache() {
    CountingJdbcTemplate jdbcTemplate = new CountingJdbcTemplate(101L, 102L);
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);
    ComponentRecord record = new ComponentRecord(
        null,
        26,
        RepositoryFormat.MAVEN2,
        "com.xindong.rocket",
        "base",
        "1.0.18",
        "release",
        HashColumns.componentCoordinateHash("com.xindong.rocket", "base", "1.0.18"),
        Map.of(),
        null);

    Assertions.assertEquals(101L, dao.upsertReturningId(record));
    Assertions.assertEquals(102L, dao.upsertReturningId(record));
    Assertions.assertEquals(2, jdbcTemplate.executeCalls.get());
  }

  @Test
  void latestSearchUsesIndexFriendlyOrder() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search(null, RepositoryFormat.MAVEN2, 20);

    assertTrue(jdbcTemplate.sql.contains("WHERE c.format = ?"));
    assertTrue(jdbcTemplate.sql.contains("ORDER BY c.last_updated_at DESC, c.id DESC"));
    Assertions.assertFalse(jdbcTemplate.sql.contains("ORDER BY c.last_updated_at DESC, r.name"));
  }

  @Test
  void searchExcludesComposerInternalRoutesBeforeApplyingLimit() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search(null, RepositoryFormat.COMPOSER, 20);

    assertTrue(jdbcTemplate.sql.contains("LEFT(c.name, 10) <> '_composer/'"));
    assertTrue(jdbcTemplate.sql.indexOf("LEFT(c.name, 10)") < jdbcTemplate.sql.indexOf("LIMIT ?"));
  }

  @Test
  void composerSearchReturnsStoredDistPathForBrowseNavigation() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search(null, RepositoryFormat.COMPOSER, 20);

    assertTrue(jdbcTemplate.sql.contains("JSON_EXTRACT(c.attributes_json, '$.distPath')"));
    assertTrue(jdbcTemplate.sql.contains("END AS storage_path"));
  }

  @Test
  void repositoryScopedSearchIncludesStoragePathProjection() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.searchByRepositoryIds(List.of(1L), RepositoryFormat.NPM, "example", 20);

    assertTrue(jdbcTemplate.sql.contains("END AS storage_path"));
    assertTrue(jdbcTemplate.sql.contains("c.format = ?"));
    assertTrue(jdbcTemplate.sql.contains("MATCH(cs.namespace, cs.name, cs.version, cs.keywords)"));
  }

  @Test
  void formattedFulltextSearchContainsConcretePredicateAndSingleComponentSource() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search("observability", RepositoryFormat.MAVEN2, 20);

    assertTrue(jdbcTemplate.sql.contains("AGAINST (? IN BOOLEAN MODE)"));
    Assertions.assertFalse(jdbcTemplate.sql.contains("%s"));
    assertTrue(jdbcTemplate.sql.indexOf("FROM component_search")
        == jdbcTemplate.sql.lastIndexOf("FROM component_search"));
  }

  @Test
  void fulltextBooleanQueryTokenizesWithoutRegexBacktracking() {
    String keyword = "\"".repeat(4096) + "Com.Example artifact-1.0";

    Assertions.assertEquals(
        "+com* +example* +artifact* +1* +0*",
        DIALECT.search().prepareComponentQuery(keyword));
  }

  private static final class CountingJdbcTemplate extends JdbcTemplate {
    private final long[] ids;
    private final AtomicInteger executeCalls = new AtomicInteger();

    private CountingJdbcTemplate(long... ids) {
      this.ids = ids;
    }

    @Override
    public <T> T execute(ConnectionCallback<T> action) {
      int index = executeCalls.getAndIncrement();
      long id = ids[Math.min(index, ids.length - 1)];
      try {
        return action.doInConnection(connectionReturning(id));
      } catch (java.sql.SQLException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public int update(String sql, Object... args) {
      return 1;
    }
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private String sql;

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      this.sql = sql;
      return List.of();
    }
  }

  private static Connection connectionReturning(long id) {
    return (Connection) Proxy.newProxyInstance(
        ComponentDaoTest.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        (proxy, method, args) -> {
          if ("prepareStatement".equals(method.getName())) {
            return preparedStatementReturning(id);
          }
          return defaultValue(method.getReturnType());
        });
  }

  private static PreparedStatement preparedStatementReturning(long id) {
    return (PreparedStatement) Proxy.newProxyInstance(
        ComponentDaoTest.class.getClassLoader(),
        new Class<?>[]{PreparedStatement.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "executeUpdate" -> 1;
          case "executeQuery" -> resultSetReturning(id);
          default -> defaultValue(method.getReturnType());
        });
  }

  private static ResultSet resultSetReturning(long id) {
    AtomicInteger nextCalls = new AtomicInteger();
    return (ResultSet) Proxy.newProxyInstance(
        ComponentDaoTest.class.getClassLoader(),
        new Class<?>[]{ResultSet.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "next" -> nextCalls.getAndIncrement() == 0;
          case "getLong" -> id;
          default -> defaultValue(method.getReturnType());
        });
  }

  private static Object defaultValue(Class<?> returnType) {
    if (returnType == boolean.class) return false;
    if (returnType == byte.class) return (byte) 0;
    if (returnType == short.class) return (short) 0;
    if (returnType == int.class) return 0;
    if (returnType == long.class) return 0L;
    if (returnType == float.class) return 0F;
    if (returnType == double.class) return 0D;
    return null;
  }
}
