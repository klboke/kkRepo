package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.ServiceLoader;

/** Discovers the installed persistence implementation without exposing JDBC internals. */
public final class PersistenceStoreFactories {
  private PersistenceStoreFactories() {
  }

  public static PersistenceStores connect(DatabaseConnectionSettings settings) {
    PersistenceStoreFactory factory = ServiceLoader.load(PersistenceStoreFactory.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No persistence store factory is installed"));
    return factory.connect(settings);
  }
}
