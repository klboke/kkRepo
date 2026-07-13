package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public interface SecurityAuditDao {
  void insert(AuditLogRecord record);

  AuditLogPage search(AuditLogQuery query);

  record AuditLogRecord(
      LocalDateTime occurredAt,
      String actorSource,
      String actorUserId,
      String actorRealmId,
      Long actorApiKeyId,
      String remoteAddr,
      String method,
      String path,
      String permission,
      Integer status,
      String outcome,
      Map<String, Object> details) {
  }

  record AuditLogQuery(
      String query,
      String actorSource,
      String actorUserId,
      String remoteAddr,
      String method,
      String path,
      String permission,
      Integer status,
      String outcome,
      LocalDateTime from,
      LocalDateTime to,
      int page,
      int size) {
    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 200;

    public static AuditLogQuery sanitize(AuditLogQuery query) {
      if (query == null) {
        return new AuditLogQuery(null, null, null, null, null, null, null, null, null, null, null, 0, DEFAULT_SIZE);
      }
      int safePage = Math.max(0, query.page());
      int requestedSize = query.size() <= 0 ? DEFAULT_SIZE : query.size();
      int safeSize = Math.min(MAX_SIZE, requestedSize);
      String method = blankToNull(query.method());
      String outcome = blankToNull(query.outcome());
      return new AuditLogQuery(
          blankToNull(query.query()),
          blankToNull(query.actorSource()),
          blankToNull(query.actorUserId()),
          blankToNull(query.remoteAddr()),
          method == null ? null : method.toUpperCase(Locale.ROOT),
          blankToNull(query.path()),
          blankToNull(query.permission()),
          query.status(),
          outcome == null ? null : outcome.toUpperCase(Locale.ROOT),
          query.from(),
          query.to(),
          safePage,
          safeSize);
    }
  }

  record AuditLogEntry(
      long id,
      LocalDateTime occurredAt,
      String actorSource,
      String actorUserId,
      String actorRealmId,
      Long actorApiKeyId,
      String remoteAddr,
      String method,
      String path,
      String permission,
      Integer status,
      String outcome,
      Map<String, Object> details) {
  }

  record AuditLogPage(
      long total,
      int page,
      int size,
      List<AuditLogEntry> items) {
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
