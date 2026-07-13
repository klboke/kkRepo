package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationCheckpointRecord;
import java.util.Optional;

public interface MigrationCheckpointDao {
  void upsert(MigrationCheckpointRecord record);

  Optional<MigrationCheckpointRecord> find(
      long jobId,
      String sourceDatabase,
      String sourceClass,
      String sourceRid);
}
