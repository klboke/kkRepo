package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.List;
import java.util.Objects;

/** Conservative candidate filter. Callers must still authorize each returned asset. */
public record AssetPathFilter(Kind kind, String value, List<AssetPathFilter> children) {
  public enum Kind { ALL, NONE, EXACT, PREFIX, AND, OR }
  public static final AssetPathFilter ALL = new AssetPathFilter(Kind.ALL, "", List.of());
  public static final AssetPathFilter NONE = new AssetPathFilter(Kind.NONE, "", List.of());
  public AssetPathFilter {
    Objects.requireNonNull(kind);
    value = value == null ? "" : value;
    children = children == null ? List.of() : List.copyOf(children);
  }
  public static AssetPathFilter exact(String path) {
    return new AssetPathFilter(Kind.EXACT, path, List.of());
  }
  public static AssetPathFilter prefix(String path) {
    return path.isEmpty() ? ALL : new AssetPathFilter(Kind.PREFIX, path, List.of());
  }
  public static AssetPathFilter and(AssetPathFilter left, AssetPathFilter right) {
    if (left.equals(NONE) || right.equals(NONE)) return NONE;
    if (left.equals(ALL)) return right;
    if (right.equals(ALL)) return left;
    return new AssetPathFilter(Kind.AND, "", List.of(left, right));
  }
  public static AssetPathFilter or(AssetPathFilter left, AssetPathFilter right) {
    if (left.equals(ALL) || right.equals(ALL)) return ALL;
    if (left.equals(NONE)) return right;
    if (right.equals(NONE)) return left;
    return new AssetPathFilter(Kind.OR, "", List.of(left, right));
  }
}
