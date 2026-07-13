package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcOperations;

/** Atomic component identity and search-index persistence operations. */
public interface ComponentPersistenceDialect {
  long upsertAndReturnId(JdbcOperations jdbc, ComponentUpsert command);

  void upsertSearchDocument(JdbcOperations jdbc, ComponentSearchDocument document);

  record ComponentUpsert(
      long repositoryId,
      String format,
      String namespace,
      String name,
      String version,
      String kind,
      byte[] coordinateHash,
      String attributesJson,
      Timestamp lastUpdatedAt) {
  }

  record ComponentSearchDocument(
      long componentId,
      long repositoryId,
      String format,
      String namespace,
      String name,
      String version,
      String keywords) {
  }
}
