package com.github.klboke.kkrepo.persistence.jdbc.api;

/** Opens database-neutral persistence contracts for standalone tools. */
public interface PersistenceStoreFactory {
  PersistenceStores connect(DatabaseConnectionSettings settings);
}
