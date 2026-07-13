package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDataMigrationDao.TargetAssetRef;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryDataMigrationAssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

class RepositoryDataMigrationDaoTest {
  @Test
  void discoveredAssetAlreadyPresentInTargetIsMarkedMigrated() throws Exception {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper()));
    RepositoryDataMigrationAssetRecord asset = asset("com/acme/app/1.0/app-1.0.jar");

    dao.upsertDiscoveredAssets(
        100,
        List.of(asset),
        Map.of(ByteBuffer.wrap(asset.sourcePathHash()), new TargetAssetRef(11L, 22L, 33L)));

    assertTrue(jdbcTemplate.sql.contains("VALUES(status)"));
    assertEquals(JdbcRepositoryDataMigrationDao.ASSET_MIGRATED, jdbcTemplate.parameters.get(20));
    assertNotNull(jdbcTemplate.parameters.get(21));
    assertEquals(11L, jdbcTemplate.parameters.get(22));
    assertEquals(22L, jdbcTemplate.parameters.get(23));
    assertEquals(33L, jdbcTemplate.parameters.get(24));
  }

  @Test
  void discoveredAssetWithoutPrecomputedPathHashStillMatchesTarget() throws Exception {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper()));
    String path = "helm-hosted/charts/app-1.0.0.tgz";

    dao.upsertDiscoveredAssets(
        100,
        List.of(asset(path, null)),
        Map.of(ByteBuffer.wrap(HashColumns.pathHash(path)), new TargetAssetRef(11L, 22L, 33L)));

    assertEquals(JdbcRepositoryDataMigrationDao.ASSET_MIGRATED, jdbcTemplate.parameters.get(20));
    assertNotNull(jdbcTemplate.parameters.get(5));
    assertEquals(22L, jdbcTemplate.parameters.get(23));
  }

  @Test
  void discoveredAssetMissingInTargetRemainsPending() throws Exception {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    RepositoryDataMigrationDao dao = new JdbcRepositoryDataMigrationDao(
        jdbcTemplate,
        new JsonColumns(new ObjectMapper()));

    dao.upsertDiscoveredAssets(100, List.of(asset("com/acme/app/1.0/app-1.0.pom")), Map.of());

    assertEquals(JdbcRepositoryDataMigrationDao.ASSET_PENDING, jdbcTemplate.parameters.get(20));
    assertEquals(null, jdbcTemplate.parameters.get(21));
    assertEquals(null, jdbcTemplate.parameters.get(22));
    assertEquals(null, jdbcTemplate.parameters.get(23));
    assertEquals(null, jdbcTemplate.parameters.get(24));
  }

  private static RepositoryDataMigrationAssetRecord asset(String path) {
    return asset(path, HashColumns.pathHash(path));
  }

  private static RepositoryDataMigrationAssetRecord asset(String path, byte[] pathHash) {
    Instant updated = Instant.parse("2026-06-03T10:00:00Z");
    return new RepositoryDataMigrationAssetRecord(
        null,
        100,
        "#12:0",
        "#11:0",
        path,
        pathHash,
        RepositoryFormat.MAVEN2,
        "com.acme",
        "app",
        "1.0",
        "artifact",
        "application/octet-stream",
        1024L,
        "default@abc",
        updated,
        null,
        updated,
        updated,
        "admin",
        "127.0.0.1",
        JdbcRepositoryDataMigrationDao.ASSET_PENDING,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of("sourceRepositoryType", "hosted"),
        null);
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private String sql;
    private final Map<Integer, Object> parameters = new LinkedHashMap<>();

    @Override
    public <T> int[][] batchUpdate(
        String sql,
        Collection<T> batchArgs,
        int batchSize,
        ParameterizedPreparedStatementSetter<T> pss) {
      this.sql = sql;
      try {
        for (T item : batchArgs) {
          pss.setValues(preparedStatement(), item);
        }
      } catch (java.sql.SQLException e) {
        throw new AssertionError(e);
      }
      return new int[][]{{1}};
    }

    private PreparedStatement preparedStatement() {
      return (PreparedStatement) Proxy.newProxyInstance(
          RepositoryDataMigrationDaoTest.class.getClassLoader(),
          new Class<?>[]{PreparedStatement.class},
          (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
              parameters.put(index, "setNull".equals(name) ? null : args[1]);
            }
            return defaultValue(method.getReturnType());
          });
    }
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
