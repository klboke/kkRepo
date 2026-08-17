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
import java.util.ArrayList;
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
  void terraformSearchReturnsLogicalBrowsePathAndFiltersPhysicalRowsBeforeLimit() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search(null, RepositoryFormat.TERRAFORM, 20);

    assertTrue(jdbcTemplate.sql.contains(
        "JSON_EXTRACT(c.attributes_json, '$.browsePath')"));
    assertTrue(jdbcTemplate.sql.contains(
        "c.name NOT LIKE 'v1/providers/%/package/%'"));
    assertTrue(jdbcTemplate.sql.indexOf("c.name NOT LIKE 'v1/providers/%/package/%'")
        < jdbcTemplate.sql.indexOf("LIMIT ?"));
  }

  @Test
  void aptSearchReturnsPackageAssetPathForProtocolDetails() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search(null, RepositoryFormat.APT, 20);

    assertTrue(jdbcTemplate.sql.contains(
        "JSON_EXTRACT(c.attributes_json, '$.assetPath')"));
    assertTrue(jdbcTemplate.sql.contains("WHEN c.format = 'apt'"));
  }

  @Test
  void alpineSearchReturnsPackageAssetPathForProtocolDetails() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.search(null, RepositoryFormat.ALPINE, 20);

    assertTrue(jdbcTemplate.sql.contains(
        "JSON_EXTRACT(c.attributes_json, '$.assetPath')"));
    assertTrue(jdbcTemplate.sql.contains("WHEN c.format = 'alpine'"));
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
    assertTrue(jdbcTemplate.probeSql.contains("WHERE cs.repository_id IN"));
  }

  @Test
  void newestRepositoryScopedSearchUsesTheOrderedAuthorizationIndex() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.searchPageByRepositoryIds(
        List.of(1L, 2L), RepositoryFormat.NPM, null, null, 20);

    assertTrue(jdbcTemplate.sql.contains(
        "/*+ JOIN_ORDER(search_order, c, r) "
            + "INDEX(search_order idx_component_format_last_updated) */"));
    assertTrue(jdbcTemplate.sql.contains(
        "ORDER BY search_order.last_updated_at DESC, search_order.id DESC"));
    assertTrue(jdbcTemplate.sql.contains(
        "JOIN component c ON c.id = search_order.id"));
    Assertions.assertFalse(jdbcTemplate.sql.contains("CASE WHEN c.last_updated_at IS NULL"));
  }

  @Test
  void broadFulltextSearchSwitchesToTheTimeOrderedPlan() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(5_001);
    ComponentDao dao = new JdbcComponentDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper(), DIALECT),
        DIALECT);

    dao.searchPageByRepositoryIds(
        List.of(1L, 2L), RepositoryFormat.NPM, "common", null, 20);
    dao.searchPageByRepositoryIds(
        List.of(1L, 2L), RepositoryFormat.NPM, "common", null, 20);

    assertTrue(jdbcTemplate.probeSql.contains("FROM component_search cs"));
    assertTrue(jdbcTemplate.probeSql.contains("LIMIT ?"));
    Assertions.assertEquals(1, jdbcTemplate.probeCalls);
    assertTrue(jdbcTemplate.sql.contains(
        "/*+ JOIN_ORDER(search_order, cs, c, r) "
            + "INDEX(search_order idx_component_format_last_updated) */"));
    assertTrue(jdbcTemplate.sql.indexOf("FROM component search_order")
        < jdbcTemplate.sql.indexOf("JOIN component_search cs"));
    assertTrue(jdbcTemplate.sql.contains("AND cs.repository_id IN"));
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
        "+com* +example* +artifact* 1* 0*",
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
    private final int probeRows;
    private String sql;
    private String probeSql;
    private int probeCalls;

    private RecordingJdbcTemplate() {
      this(0);
    }

    private RecordingJdbcTemplate(int probeRows) {
      this.probeRows = probeRows;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      this.sql = sql;
      return List.of();
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
      this.probeSql = sql;
      this.probeCalls++;
      List<T> rows = new ArrayList<>(probeRows);
      for (int index = 0; index < probeRows; index++) {
        rows.add(elementType.cast(1L));
      }
      return rows;
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
