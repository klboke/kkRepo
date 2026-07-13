package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogEntry;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogPage;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogQuery;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSecurityAuditDao implements com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao {
  private final JdbcTemplate jdbcTemplate;
  private final JsonColumns jsonColumns;

  public JdbcSecurityAuditDao(JdbcTemplate jdbcTemplate, JsonColumns jsonColumns) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonColumns = jsonColumns;
  }

  public void insert(AuditLogRecord record) {
    jdbcTemplate.update("""
        INSERT INTO security_audit_log
          (occurred_at, actor_source, actor_user_id, actor_realm_id, actor_api_key_id,
           remote_addr, method, path, permission, status, outcome, details_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.occurredAt(),
        record.actorSource(),
        record.actorUserId(),
        record.actorRealmId(),
        record.actorApiKeyId(),
        record.remoteAddr(),
        record.method(),
        record.path(),
        record.permission(),
        record.status(),
        record.outcome(),
        jsonColumns.parameter(record.details()));
  }

  public AuditLogPage search(AuditLogQuery query) {
    AuditLogQuery safeQuery = AuditLogQuery.sanitize(query);
    SqlFilters filters = filters(safeQuery);
    Long total = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM security_audit_log" + filters.where(),
        Long.class,
        filters.args().toArray());
    int offset = safeQuery.page() * safeQuery.size();
    List<Object> rowArgs = new ArrayList<>(filters.args());
    rowArgs.add(safeQuery.size());
    rowArgs.add(offset);
    List<AuditLogEntry> entries = jdbcTemplate.query("""
        SELECT id, occurred_at, actor_source, actor_user_id, actor_realm_id, actor_api_key_id,
               remote_addr, method, path, permission, status, outcome, details_json
          FROM security_audit_log
        """ + filters.where() + """
         ORDER BY occurred_at DESC, id DESC
         LIMIT ? OFFSET ?
        """, auditLogEntryMapper, rowArgs.toArray());
    return new AuditLogPage(total == null ? 0 : total, safeQuery.page(), safeQuery.size(), entries);
  }

  private SqlFilters filters(AuditLogQuery query) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    appendLowerLike(where, args, "actor_source", query.actorSource());
    appendLowerLike(where, args, "actor_user_id", query.actorUserId());
    appendLowerLike(where, args, "remote_addr", query.remoteAddr());
    appendLowerLike(where, args, "path", query.path());
    appendLowerLike(where, args, "permission", query.permission());
    appendEquals(where, args, "method", query.method());
    appendEquals(where, args, "outcome", query.outcome());
    if (query.status() != null) {
      where.append(" AND status = ?");
      args.add(query.status());
    }
    if (query.from() != null) {
      where.append(" AND occurred_at >= ?");
      args.add(query.from());
    }
    if (query.to() != null) {
      where.append(" AND occurred_at <= ?");
      args.add(query.to());
    }
    if (query.query() != null) {
      String pattern = likePattern(query.query());
      where.append("""
           AND (
             LOWER(COALESCE(actor_source, '')) LIKE ? ESCAPE '\\\\'
             OR LOWER(COALESCE(actor_user_id, '')) LIKE ? ESCAPE '\\\\'
             OR LOWER(COALESCE(actor_realm_id, '')) LIKE ? ESCAPE '\\\\'
             OR COALESCE(CAST(actor_api_key_id AS CHAR), '') LIKE ? ESCAPE '\\\\'
             OR LOWER(COALESCE(remote_addr, '')) LIKE ? ESCAPE '\\\\'
             OR LOWER(method) LIKE ? ESCAPE '\\\\'
             OR LOWER(path) LIKE ? ESCAPE '\\\\'
             OR LOWER(COALESCE(permission, '')) LIKE ? ESCAPE '\\\\'
             OR COALESCE(CAST(status AS CHAR), '') LIKE ? ESCAPE '\\\\'
             OR LOWER(outcome) LIKE ? ESCAPE '\\\\'
             OR LOWER(COALESCE(CAST(details_json AS CHAR), '')) LIKE ? ESCAPE '\\\\'
           )
          """);
      for (int i = 0; i < 11; i++) {
        args.add(pattern);
      }
    }
    return new SqlFilters(where.toString(), args);
  }

  private static void appendLowerLike(StringBuilder where, List<Object> args, String column, String value) {
    if (value == null) {
      return;
    }
    where.append(" AND LOWER(").append(column).append(") LIKE ? ESCAPE '\\\\'");
    args.add(likePattern(value));
  }

  private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
    if (value == null) {
      return;
    }
    where.append(" AND ").append(column).append(" = ?");
    args.add(value);
  }

  private static String likePattern(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    return "%" + lower
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%";
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private final RowMapper<AuditLogEntry> auditLogEntryMapper = new RowMapper<>() {
    @Override
    public AuditLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new AuditLogEntry(
          rs.getLong("id"),
          rs.getObject("occurred_at", LocalDateTime.class),
          rs.getString("actor_source"),
          rs.getString("actor_user_id"),
          rs.getString("actor_realm_id"),
          boxedLong(rs, "actor_api_key_id"),
          rs.getString("remote_addr"),
          rs.getString("method"),
          rs.getString("path"),
          rs.getString("permission"),
          boxedInteger(rs, "status"),
          rs.getString("outcome"),
          jsonColumns.read(rs.getString("details_json")));
    }
  };

  private static Long boxedLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer boxedInteger(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }





  private record SqlFilters(
      String where,
      List<Object> args) {
  }
}
