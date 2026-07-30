package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AssetDaoTest {
  @Test
  void reusableBlobIdSqlSeparatesDeletedPredicateWithoutGapLocking() {
    String liveSql = JdbcAssetDao.reusableBlobIdSql(false);
    String deletedOnlySql = JdbcAssetDao.reusableBlobIdSql(true);

    assertFalse(liveSql.contains("ANDdeleted_at"));
    assertFalse(deletedOnlySql.contains("ANDdeleted_at"));
    assertFalse(liveSql.contains("NULLORDER"));
    assertFalse(deletedOnlySql.contains("NULLORDER"));
    assertFalse(liveSql.contains("FOR UPDATE"));
    assertFalse(deletedOnlySql.contains("FOR UPDATE"));
    assertTrue(liveSql.contains("SELECT id"));
    assertTrue(liveSql.contains("AND deleted_at IS NULL\n"));
    assertTrue(deletedOnlySql.contains("AND deleted_at IS NOT NULL\n"));
  }

  @Test
  void managementNamePageJoinsExactComponentNameWithStableAssetCursor() {
    RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    AssetDao dao = new JdbcAssetDao(
        jdbc,
        new JsonColumns(new ObjectMapper(), new MySqlDatabaseDialect()));

    dao.listAssetWithBlobPageByComponentName(7L, "tool", 12L, 51);

    assertTrue(jdbc.sql.contains("FROM component c"));
    assertTrue(jdbc.sql.contains("JOIN asset a ON a.component_id = c.id"));
    assertTrue(jdbc.sql.contains("c.repository_id = ?"));
    assertTrue(jdbc.sql.contains("c.name = ?"));
    assertTrue(jdbc.sql.contains("a.id > ?"));
    assertTrue(jdbc.sql.contains("ORDER BY a.id"));
    assertEquals(List.of(7L, "tool", 12L, 51), Arrays.asList(jdbc.args));
  }

  private static final class RecordingJdbcTemplate extends JdbcTemplate {
    private String sql;
    private Object[] args;

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      this.sql = sql;
      this.args = args;
      return List.of();
    }
  }
}
