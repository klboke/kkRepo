package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.HashColumns;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class BrowseNodeDaoTest {
  @Test
  void upsertPathAncestorsSkipsExistingSubtreeMarkersAlreadyTrue() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(Map.of(
        ByteBuffer.wrap(HashColumns.pathHash("com")), new ExistingRow(10L, true),
        ByteBuffer.wrap(HashColumns.pathHash("com/acme")), new ExistingRow(11L, true),
        ByteBuffer.wrap(HashColumns.pathHash("com/acme/app.jar")), new ExistingRow(12L, false)));
    BrowseNodeDao dao = new JdbcBrowseNodeDao(jdbcTemplate);

    dao.upsertPathAncestors(7L, "com/acme/app.jar", 101L, 201L);

    assertTrue(jdbcTemplate.lookupSql.contains("has_asset_subtree"));
    List<String> subtreeUpdates = jdbcTemplate.updateSql.stream()
        .filter(sql -> sql.contains("has_asset_subtree = 1"))
        .toList();
    assertEquals(1, subtreeUpdates.size());
    assertTrue(subtreeUpdates.get(0).contains("AND has_asset_subtree = 0"));
    assertEquals(12L, jdbcTemplate.subtreeUpdateIds.get(0));
  }

  private record ExistingRow(long id, boolean hasAssetSubtree) {}

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private final Map<ByteBuffer, ExistingRow> rows;
    private final List<String> updateSql = new ArrayList<>();
    private final List<Long> subtreeUpdateIds = new ArrayList<>();
    private String lookupSql;

    private RecordingJdbcTemplate(Map<ByteBuffer, ExistingRow> rows) {
      this.rows = rows;
    }

    @Override
    public void query(String sql, RowCallbackHandler rch, Object... args) {
      this.lookupSql = sql;
      try {
        for (Map.Entry<ByteBuffer, ExistingRow> entry : rows.entrySet()) {
          rch.processRow(resultSet(entry.getKey().array(), entry.getValue()));
        }
      } catch (java.sql.SQLException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public int update(String sql, Object... args) {
      updateSql.add(sql);
      if (sql.contains("has_asset_subtree = 1")) {
        subtreeUpdateIds.add((Long) args[0]);
      }
      return 1;
    }
  }

  private static ResultSet resultSet(byte[] pathHash, ExistingRow row) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("path_hash", pathHash);
    values.put("id", row.id());
    values.put("has_asset_subtree", row.hasAssetSubtree());
    return (ResultSet) Proxy.newProxyInstance(
        BrowseNodeDaoTest.class.getClassLoader(),
        new Class<?>[]{ResultSet.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getBytes" -> values.get(args[0]);
          case "getLong" -> values.get(args[0]);
          case "getBoolean" -> values.get(args[0]);
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
