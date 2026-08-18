package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Backend-specific atomic inserts used by Hugging Face proxy coordination. */
public interface HuggingFacePersistenceDialect {
  String insertFetchLeaseIfAbsentSql();
}
