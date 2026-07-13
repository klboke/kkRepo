package com.github.klboke.kkrepo.persistence.mysql;

import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseBackend;

/** MySQL backend identity. Dialect behavior is introduced in the next implementation phase. */
public final class MySqlDatabaseBackend implements DatabaseBackend {
  @Override
  public String id() {
    return "mysql";
  }
}
