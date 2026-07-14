package com.github.klboke.kkrepo.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao.AuditLogRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ApiKeyRecord;
import com.github.klboke.kkrepo.persistence.postgresql.support.PostgreSqlIntegrationTestSupport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies legacy LocalDateTime fields retain MySQL-compatible wall-clock semantics. */
class PostgreSqlLocalDateTimeCompatibilityTest extends PostgreSqlIntegrationTestSupport {
  @Test
  void localDateTimeColumnsUseTimestampWithoutTimeZone() {
    Set<String> actual = Set.copyOf(jdbc().queryForList("""
        SELECT table_name || '.' || column_name || ':' || data_type
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND ((table_name = 'api_key'
                AND column_name IN ('created_at', 'updated_at', 'expires_at', 'last_used_at'))
            OR (table_name = 'security_audit_log' AND column_name = 'occurred_at'))
        """, String.class));

    assertEquals(Set.of(
        "api_key.created_at:timestamp without time zone",
        "api_key.updated_at:timestamp without time zone",
        "api_key.expires_at:timestamp without time zone",
        "api_key.last_used_at:timestamp without time zone",
        "security_audit_log.occurred_at:timestamp without time zone"), actual);
  }

  @Test
  void localDateTimesRoundTripWhenDatabaseAndJvmTimeZonesDiffer() {
    Instant reference = Instant.parse("2026-07-13T12:00:00Z");
    String databaseTimeZone = ZoneId.systemDefault().getRules().getOffset(reference)
            .equals(ZoneOffset.UTC)
        ? "Asia/Shanghai"
        : "UTC";
    LocalDateTime expiresAt = LocalDateTime.of(2027, 1, 1, 0, 0, 0, 123_000_000);
    LocalDateTime lastUsedAt = LocalDateTime.of(2026, 7, 13, 12, 30, 0, 456_000_000);
    LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 13, 13, 0, 0, 789_000_000);

    inTransaction(() -> {
      jdbc().execute("SET LOCAL TIME ZONE '" + databaseTimeZone + "'");

      stores().security().upsertApiKey(new ApiKeyRecord(
          null,
          "npm",
          "Local",
          "alice",
          "npm token",
          "ACTIVE",
          "hash-one",
          "npm_",
          Map.of("repositories", List.of("npm-hosted")),
          "encrypted-payload",
          null,
          null,
          expiresAt,
          null));
      ApiKeyRecord created = stores().security()
          .findApiKey("npm", "Local", "alice")
          .orElseThrow();
      assertEquals(expiresAt, created.expiresAt());

      stores().security().markApiKeyUsed(created.id(), lastUsedAt);
      assertEquals(lastUsedAt,
          stores().security().findApiKey(created.id()).orElseThrow().lastUsedAt());

      stores().securityAudit().insert(new AuditLogRecord(
          occurredAt,
          "Local",
          "alice",
          null,
          created.id(),
          "127.0.0.1",
          "GET",
          "/repository/npm-hosted/package",
          "repository-read",
          200,
          "SUCCESS",
          Map.of()));
      assertEquals(occurredAt,
          stores().securityAudit().search(null).items().getFirst().occurredAt());
    });
  }
}
