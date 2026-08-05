package com.github.klboke.kkrepo.persistence.jdbc.spi;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcOperations;

/** Atomic component identity and search-index persistence operations. */
public interface ComponentPersistenceDialect {
  long upsertAndReturnId(JdbcOperations jdbc, ComponentUpsert command);

  void upsertSearchDocument(JdbcOperations jdbc, ComponentSearchDocument document);

  /** Returns a deterministic case-sensitive expression suitable for cleanup keyset ordering. */
  String caseSensitiveText(String expression);

  /**
   * Indexed expressions used to keep every release in one cleanup family contiguous.
   *
   * <p>Backends with oversized text keys may use stable binary prefixes plus hashes instead of
   * indexing the full coordinate strings.
   */
  default List<String> cleanupFamilyOrderExpressions() {
    return List.of(
        caseSensitiveText("COALESCE(namespace, '')"),
        caseSensitiveText("name"),
        caseSensitiveText("COALESCE(kind, '')"));
  }

  /** Values matching {@link #cleanupFamilyOrderExpressions()} for a keyset cursor. */
  default List<Object> cleanupFamilyCursorValues(
      String namespace, String name, String kind) {
    return List.of(
        namespace == null ? "" : namespace,
        name == null ? "" : name,
        kind == null ? "" : kind);
  }

  /** Backend-optimized keyset predicate over the configured family order expressions. */
  default CleanupFamilyCursorClause cleanupFamilyCursorClause(
      String namespace, String name, String kind) {
    List<String> expressions = cleanupFamilyOrderExpressions();
    List<Object> values = cleanupFamilyCursorValues(namespace, name, kind);
    return new CleanupFamilyCursorClause(
        "(" + String.join(",", expressions) + ") > ("
            + String.join(",", java.util.Collections.nCopies(expressions.size(), "?"))
            + ")",
        values);
  }

  record CleanupFamilyCursorClause(String sql, List<Object> arguments) {
    public CleanupFamilyCursorClause {
      arguments = List.copyOf(arguments);
    }
  }

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
