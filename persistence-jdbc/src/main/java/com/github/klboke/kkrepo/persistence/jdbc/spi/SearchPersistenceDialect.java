package com.github.klboke.kkrepo.persistence.jdbc.spi;

/** Component full-text query preparation and SQL predicate generation. */
public interface SearchPersistenceDialect {
  String componentSearchPredicate(String searchAlias);

  String prepareComponentQuery(String keyword);

  /** Stable newest-first order with null timestamps placed last. */
  String componentSearchOrderBy(String componentAlias);

  /**
   * Whether broad full-text searches should probe cardinality before choosing the access path.
   *
   * <p>This is an execution-plan choice only. Both paths must enforce the same repository scope
   * and return the same ordered rows.
   */
  default boolean supportsAdaptiveComponentSearch() {
    return false;
  }

  /**
   * Optional optimizer hint for a time-ordered component search.
   *
   * <p>The hint is inserted immediately after {@code SELECT}. Backends that cost full-text and
   * B-tree plans reliably can return an empty string.
   */
  default String orderedComponentSearchHint(
      boolean formatScoped, boolean singleRepository, boolean fullText) {
    return "";
  }
}
