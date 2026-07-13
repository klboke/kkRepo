package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.OptionalLong;

public interface MaintenanceCursorDao {
  String BLOB_UNREFERENCED_RECONCILE = "blob_unreferenced_reconcile";

  OptionalLong tryLockLastSeenId(String taskName);

  int updateLastSeenId(String taskName, long lastSeenId);

  long lastSeenId(String taskName);
}
