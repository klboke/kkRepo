package com.github.klboke.kkrepo.persistence.jdbc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogQuery;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SecurityAuditDaoTest {
  @Test
  void searchBuildsBoundedPagedQueryWithEscapedFilters() {
    RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
    SecurityAuditDao dao = new JdbcSecurityAuditDao(
        jdbcTemplate, new JsonColumns(new ObjectMapper(), new MySqlDatabaseDialect()));
    LocalDateTime from = LocalDateTime.of(2026, 6, 1, 9, 0);
    LocalDateTime to = LocalDateTime.of(2026, 6, 1, 18, 0);

    var page = dao.search(new AuditLogQuery(
        "admin_%",
        " Local ",
        " Alice ",
        "10.0.0.1",
        "post",
        "/internal/security/users",
        "nexus:users",
        201,
        "success",
        from,
        to,
        -5,
        500));

    assertEquals(12, page.total());
    assertEquals(0, page.page());
    assertEquals(200, page.size());
    assertTrue(jdbcTemplate.countSql.contains("SELECT COUNT(*) FROM security_audit_log WHERE 1 = 1"));
    assertTrue(jdbcTemplate.rowSql.contains("ORDER BY occurred_at DESC, id DESC"));
    assertTrue(jdbcTemplate.rowSql.contains("LIMIT ? OFFSET ?"));
    assertEquals(200, jdbcTemplate.rowArgs[jdbcTemplate.rowArgs.length - 2]);
    assertEquals(0, jdbcTemplate.rowArgs[jdbcTemplate.rowArgs.length - 1]);
    List<Object> countArgs = Arrays.asList(jdbcTemplate.countArgs);
    assertTrue(jdbcTemplate.countSql.contains("LIKE ? ESCAPE '!'"));
    assertTrue(countArgs.contains("%admin!_!%%"));
    assertTrue(countArgs.contains("%local%"));
    assertTrue(countArgs.contains("%alice%"));
    assertTrue(countArgs.contains("POST"));
    assertTrue(countArgs.contains("SUCCESS"));
    assertTrue(countArgs.contains(201));
    assertTrue(countArgs.contains(from));
    assertTrue(countArgs.contains(to));
  }

  private static class RecordingJdbcTemplate extends JdbcTemplate {
    private String countSql;
    private String rowSql;
    private Object[] countArgs = new Object[0];
    private Object[] rowArgs = new Object[0];

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
      countSql = sql;
      countArgs = args;
      return requiredType.cast(12L);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
      rowSql = sql;
      rowArgs = args;
      return List.of();
    }
  }
}
