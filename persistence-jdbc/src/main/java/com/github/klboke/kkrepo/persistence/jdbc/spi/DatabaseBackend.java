package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Marker contract implemented by installable relational database backends. */
public interface DatabaseBackend {
  /** Stable configuration identifier for the backend. */
  String id();
}
