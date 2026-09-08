package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetPathFilter;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Parameterized, database-neutral superset of selector matches; never an authorization decision. */
final class SelectorCandidateSql {
  private SelectorCandidateSql() {}
  static String repositories(String alias, Map<Long, AssetPathFilter> filters, List<Object> args) {
    StringJoiner clauses = new StringJoiner(" OR ", "(", ")");
    filters.forEach((id, filter) -> {
      args.add(id);
      clauses.add("(" + alias + ".repository_id = ? AND " + path(alias + ".path", filter, args) + ")");
    });
    return filters.isEmpty() ? "1 = 0" : clauses.toString();
  }
  private static String path(String column, AssetPathFilter filter, List<Object> args) {
    return switch (filter.kind()) {
      case ALL -> "1 = 1";
      case NONE -> "1 = 0";
      case EXACT -> { args.add(filter.value()); yield column + " = ?"; }
      case PREFIX -> {
        args.add(filter.value().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        yield column + " LIKE ? ESCAPE '!'";
      }
      case AND, OR -> {
        StringJoiner children = new StringJoiner(filter.kind() == AssetPathFilter.Kind.AND ? " AND " : " OR ", "(", ")");
        filter.children().forEach(child -> children.add(path(column, child, args)));
        yield filter.children().isEmpty() ? "1 = 0" : children.toString();
      }
    };
  }
}
