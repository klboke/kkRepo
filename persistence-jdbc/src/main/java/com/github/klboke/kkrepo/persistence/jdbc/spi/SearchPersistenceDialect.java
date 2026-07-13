package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Component full-text query preparation and SQL predicate generation. */
public interface SearchPersistenceDialect {
  String componentSearchPredicate(String searchAlias);

  String prepareComponentQuery(String keyword);
}
