package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.MigrationJobRecord;
import java.util.Map;
import java.util.Optional;

public interface MigrationJobDao {
  long create(String sourceNexusVersion, String sourceDataPath, Map<String, Object> options);

  Optional<MigrationJobRecord> findById(long id);

  void markFinished(long id, String status, Map<String, Object> summary);
}
