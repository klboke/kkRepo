package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Backend-specific Conda persistence statements and streaming configuration. */
public interface CondaPersistenceDialect {
  String insertChannelStateIfAbsentSql();

  String insertCoordinateLeaseIfAbsentSql();

  int streamingFetchSize();
}
