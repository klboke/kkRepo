package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingGroupBy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.FindingGroupCursor;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opaque keyset cursors for finding groups. A cursor carries the last group's severity rank and
 * normalized key, so any replica can serve the next page without server-side state.
 */
final class FindingGroupPagination {
  private static final String VERSION = "g1";
  // Artifact keys include asset paths of up to 2048 characters; base64 adds a third.
  private static final int MAX_CURSOR_LENGTH = 16 * 1024;

  private FindingGroupPagination() {}

  static String encode(FindingGroupBy groupBy, FindingGroupCursor cursor) {
    StringBuilder value = new StringBuilder(VERSION)
        .append('.').append(groupBy.name())
        .append('.').append(cursor.severityRank());
    for (String part : cursor.key()) {
      value.append('.').append(Base64.getUrlEncoder().withoutPadding()
          .encodeToString((part == null ? "" : part).getBytes(StandardCharsets.UTF_8)));
    }
    return value.toString();
  }

  static FindingGroupCursor decode(FindingGroupBy groupBy, String cursor) {
    if (cursor == null || cursor.isEmpty()) return null;
    try {
      if (cursor.length() > MAX_CURSOR_LENGTH) throw new IllegalArgumentException();
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 3 + keySize(groupBy)
          || !VERSION.equals(parts[0])
          || !groupBy.name().equals(parts[1])) {
        throw new IllegalArgumentException();
      }
      int rank = Integer.parseInt(parts[2]);
      if (Arrays.stream(Severity.values()).noneMatch(value -> value.rank() == rank)) {
        throw new IllegalArgumentException();
      }
      List<String> key = new ArrayList<>(parts.length - 3);
      for (int index = 3; index < parts.length; index++) {
        key.add(new String(Base64.getUrlDecoder().decode(parts[index]), StandardCharsets.UTF_8));
      }
      return new FindingGroupCursor(rank, key);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid finding group cursor for this grouping");
    }
  }

  private static int keySize(FindingGroupBy groupBy) {
    return switch (groupBy) {
      case PACKAGE -> 2;
      case ARTIFACT -> 3;
      case ARTIFACT_VERSION -> 4;
    };
  }
}
