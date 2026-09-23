package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.CompletionOrder;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ScanCompletionPaginationTest {
  private record Row(long id, Instant time) {}

  @Test
  void roundTripsTimestampAndNullBoundariesInBothDirectionsWithoutRowLookups() {
    for (String field : List.of("finished_at", "completed_at")) {
      for (boolean ascending : List.of(true, false)) {
        for (Row row : List.of(new Row(7, Instant.parse("2026-09-23T00:00:00.123456Z")),
            new Row(8, null))) {
          var page = ScanCompletionPagination.page(List.of(row, row), 1, Row::id, Row::time,
              field, new CompletionOrder(ascending, 0, null));
          assertEquals(List.of(row), page.items());
          assertNull(page.nextAfter());
          assertEquals(new CompletionOrder(ascending, row.id(), row.time()),
              ScanCompletionPagination.parse(field, field, ascending ? "asc" : "desc",
                  page.nextCursor(), 0));
          assertNull(ScanCompletionPagination.page(List.of(row), 1, Row::id, Row::time,
              field, new CompletionOrder(ascending, 0, null)).nextCursor());
        }
      }
    }
  }

  @Test
  void keepsLegacyIdPaginationAndDefaultsCompletionToNewestFirst() {
    assertNull(ScanCompletionPagination.parse("finished_at", null, null, null, 100));
    assertNull(ScanCompletionPagination.parse("finished_at", "id", "asc", null, 100));
    assertEquals(new CompletionOrder(false, 0, null),
        ScanCompletionPagination.parse("finished_at", "finished_at", null, null, 0));
  }

  @Test
  void rejectsInvalidFieldsDirectionsMixedPaginationAndMismatchedCursors() {
    String cursor = ScanCompletionPagination.page(
        List.of(new Row(7, Instant.EPOCH), new Row(8, Instant.EPOCH)), 1,
        Row::id, Row::time, "finished_at", new CompletionOrder(false, 0, null)).nextCursor();
    for (String[] values : List.of(
        new String[]{"completed_at", "desc", null},
        new String[]{"finished_at", "drop table", null},
        new String[]{"id", "desc", null},
        new String[]{"id", "asc", cursor},
        new String[]{"finished_at", "asc", cursor},
        new String[]{"finished_at", "desc", "!invalid"},
        new String[]{"finished_at", "desc", "x".repeat(257)},
        new String[]{"finished_at", "desc", token("1|finished_at|desc|bad-time|1")},
        new String[]{"finished_at", "desc", token("1|finished_at|desc||0")},
        new String[]{"finished_at", "desc", token("1|finished_at|desc||oops")})) {
      assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
          () -> ScanCompletionPagination.parse("finished_at", values[0], values[1], values[2], 0))
          .getStatusCode());
    }
    assertThrows(ResponseStatusException.class, () -> ScanCompletionPagination.parse(
        "completed_at", "completed_at", "desc", cursor, 0));
    assertThrows(ResponseStatusException.class, () -> ScanCompletionPagination.parse(
        "finished_at", "finished_at", "desc", null, 5));
  }

  @Test
  void rejectsJdbcOverflowAndDatabaseRangeViolationsAsBadRequests() {
    for (String field : List.of("finished_at", "completed_at")) {
      for (String time : List.of(
          "+1000000000-12-31T23:59:59.999999999Z",
          "-1000000000-01-01T00:00:00Z",
          "0900-12-31T23:59:59.999Z", "+11000-01-01T00:00:00Z",
          "+10000-06-01T00:00:00Z")) {
        var error = assertThrows(ResponseStatusException.class, () -> ScanCompletionPagination.parse(
            field, field, "desc", token("1|" + field + "|desc|" + time + "|1"), 0));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
      }
      for (String localTime : List.of("1000-01-01 00:00:00", "9999-12-31 23:59:59.999")) {
        String time = java.sql.Timestamp.valueOf(localTime).toInstant().toString();
        var order = ScanCompletionPagination.parse(
            field, field, "desc", token("1|" + field + "|desc|" + time + "|1"), 0);
        assertEquals(Instant.parse(time), order.afterTime());
      }
    }
  }

  private static String token(String value) {
    return Base64.getUrlEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
