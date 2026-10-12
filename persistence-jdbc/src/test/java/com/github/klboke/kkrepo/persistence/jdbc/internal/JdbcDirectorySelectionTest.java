package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Query-boundary guards complement the real two-database directory deletion smoke tests. */
class JdbcDirectorySelectionTest {
  @Test void treeTraversalIsRepositoryScopedBoundedAndDoesNotInterpretPathsAsWildcards() {
    var jdbc = new RecordingJdbc();
    var nodes = new JdbcBrowseNodeDao(jdbc);
    assertTrue(nodes.listDeletionChildren(7, List.of(), 500).isEmpty());
    assertTrue(nodes.listDeletionChildren(7, List.of(11L), 0).isEmpty());
    assertNull(jdbc.sql, "empty or exhausted traversals must not query the database");
    var selected = nodes.listDeletionChildren(7, List.of(11L, 12L), 501);
    assertEquals(List.of(7L, 11L, 12L, 501), List.of(jdbc.arguments));
    assertTrue(jdbc.sql.contains("repository_id = ? AND parent_id IN (?,?)"));
    assertTrue(jdbc.sql.endsWith("ORDER BY id LIMIT ?"));
    assertFalse(jdbc.sql.contains("LIKE"));
    assertEquals("literal%_/child", selected.getFirst().path());
    assertNull(selected.getFirst().assetId(), "directory-only rows must not turn into asset zero");
  }

  @Test void componentCompletenessUsesAnExistenceProbeAndSupportsAnEmptySelection() {
    var jdbc = new RecordingJdbc();
    var assets = new JdbcAssetDao(jdbc, null, null, false);
    assertFalse(assets.hasComponentAssetsOutside(5, List.of(9L, 10L)));
    assertEquals(List.of(5L, 9L, 10L), List.of(jdbc.arguments));
    assertEquals("SELECT id FROM asset WHERE component_id = ? AND id NOT IN (?,?) LIMIT 1", jdbc.sql);
    jdbc.hasOutsideAsset = true;
    assertTrue(assets.hasComponentAssetsOutside(5, List.of()));
    assertEquals(List.of(5L), List.of(jdbc.arguments));
    assertEquals("SELECT id FROM asset WHERE component_id = ? LIMIT 1", jdbc.sql);
  }

  private static final class RecordingJdbc extends JdbcTemplate {
    String sql;
    Object[] arguments;
    boolean hasOutsideAsset;

    @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... arguments) {
      this.sql = sql;
      this.arguments = arguments;
      return hasOutsideAsset ? List.of(type.cast(99L)) : List.of();
    }

    @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
      this.sql = sql;
      this.arguments = arguments;
      ResultSet row = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
          new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getLong" -> "id".equals(args[0]) ? 21L : 0L;
            case "getString" -> "literal%_/child";
            case "wasNull" -> true;
            default -> throw new UnsupportedOperationException(method.getName());
          });
      try {
        return List.of(mapper.mapRow(row, 0));
      } catch (SQLException error) {
        throw new AssertionError(error);
      }
    }
  }
}
