package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.CompletionOrder;
import com.github.klboke.kkrepo.server.securityscan.SecurityScanManagementService.CursorPage;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stateless timestamp/ID cursors can be served by any replica, even after the boundary is deleted. */
final class ScanCompletionPagination {
  // Early JVM-side range check; the DAO also handles datetime overflow in the connection timezone.
  private static final LocalDateTime MIN_COMPLETION = LocalDateTime.parse("1000-01-01T00:00:00");
  private static final LocalDateTime MAX_COMPLETION = LocalDateTime.parse("9999-12-31T23:59:59.999");

  private ScanCompletionPagination() {}

  static CompletionOrder parse(
      String field, String sort, String direction, String cursor, long after) {
    if (sort == null || "id".equals(sort)) {
      if (cursor != null || (direction != null && !"asc".equals(direction))) {
        throw invalid("ID sorting accepts direction=asc and after, not cursor");
      }
      return null;
    }
    if (!field.equals(sort)) throw invalid("Unsupported sort field: " + sort);
    boolean ascending = "asc".equals(direction);
    if (direction != null && !ascending && !"desc".equals(direction)) {
      throw invalid("direction must be asc or desc");
    }
    if (after != 0) throw invalid("Completion sorting uses cursor instead of after");
    if (cursor == null) return new CompletionOrder(ascending, 0, null);
    try {
      if (cursor.length() > 256) throw new IllegalArgumentException();
      String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
          .split("\\|", -1);
      if (parts.length != 5 || !"1".equals(parts[0]) || !field.equals(parts[1])
          || !(ascending ? "asc" : "desc").equals(parts[2])) {
        throw new IllegalArgumentException();
      }
      long id = Long.parseLong(parts[4]);
      if (id <= 0) throw new IllegalArgumentException();
      Instant time = parts[3].isEmpty() ? null : Instant.parse(parts[3]);
      if (time != null) {
        LocalDateTime jdbcTime = Timestamp.from(time).toLocalDateTime();
        if (jdbcTime.isBefore(MIN_COMPLETION) || jdbcTime.isAfter(MAX_COMPLETION)) {
          throw new IllegalArgumentException();
        }
      }
      return new CompletionOrder(ascending, id, time);
    } catch (IllegalArgumentException | java.time.DateTimeException exception) {
      throw invalid("Invalid completion cursor for this sort order");
    }
  }

  static <T> CursorPage<T> page(
      List<T> candidates, int limit, Function<T, Long> id, Function<T, Instant> timestamp,
      String field, CompletionOrder order) {
    List<T> items = candidates.stream().limit(limit).toList();
    String cursor = null;
    if (candidates.size() > limit && !items.isEmpty()) {
      T last = items.getLast();
      Instant time = timestamp.apply(last);
      String value = "1|" + field + "|" + (order.ascending() ? "asc" : "desc")
          + "|" + (time == null ? "" : time.toString()) + "|" + id.apply(last);
      cursor = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    return new CursorPage<>(items, null, cursor);
  }

  private static ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
