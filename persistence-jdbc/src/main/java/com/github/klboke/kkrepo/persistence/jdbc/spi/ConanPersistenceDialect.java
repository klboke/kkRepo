package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Backend-specific Conan persistence statements. */
public interface ConanPersistenceDialect {
  /** Indexed half-open range query used to bound wildcard recipe searches by name. */
  String recipeNameRangeSql();
}
