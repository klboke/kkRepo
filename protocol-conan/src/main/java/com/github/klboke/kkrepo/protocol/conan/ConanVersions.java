package com.github.klboke.kkrepo.protocol.conan;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Conan 2's non-SemVer ordering: dot items, numeric comparison, then pre/build qualifiers. */
public final class ConanVersions {
  private ConanVersions() {
  }

  public static Comparator<String> comparator() {
    return (left, right) -> new Version(left, false).compareTo(new Version(right, false));
  }

  private record Item(BigInteger number, String text) implements Comparable<Item> {
    static Item parse(String value) {
      try {
        return new Item(new BigInteger(value), null);
      } catch (NumberFormatException ignored) {
        return new Item(null, value);
      }
    }

    boolean zero() {
      return BigInteger.ZERO.equals(number);
    }

    @Override
    public int compareTo(Item other) {
      if (number != null && other.number != null) return number.compareTo(other.number);
      String left = number == null ? text : number.toString();
      String right = other.number == null ? other.text : other.number.toString();
      return left.compareTo(right);
    }
  }

  private static final class Version implements Comparable<Version> {
    private final List<Item> main;
    private final Version pre;
    private final Version build;

    private Version(String input, boolean qualifier) {
      String value = input == null ? "" : input;
      Version parsedBuild = null;
      Version parsedPre = null;
      if (!qualifier) {
        int buildSeparator = value.lastIndexOf('+');
        if (buildSeparator >= 0) {
          parsedBuild = new Version(value.substring(buildSeparator + 1), true);
          value = value.substring(0, buildSeparator);
        }
        int preSeparator = value.indexOf('-');
        if (preSeparator >= 0) {
          parsedPre = new Version(value.substring(preSeparator + 1), true);
          value = value.substring(0, preSeparator);
        }
      }
      List<Item> items = new ArrayList<>();
      for (String item : value.split("\\.", -1)) items.add(Item.parse(item));
      while (!items.isEmpty() && items.get(items.size() - 1).zero()) {
        items.remove(items.size() - 1);
      }
      main = Collections.unmodifiableList(items);
      pre = parsedPre;
      build = parsedBuild;
    }

    @Override
    public int compareTo(Version other) {
      int mainComparison = compareItems(main, other.main);
      if (mainComparison != 0) return mainComparison;
      if (pre != null && other.pre == null) return -1;
      if (pre == null && other.pre != null) return 1;
      if (pre != null) {
        int preComparison = pre.compareTo(other.pre);
        if (preComparison != 0) return preComparison;
      }
      if (build == null && other.build == null) return 0;
      if (build == null) return -1;
      if (other.build == null) return 1;
      return build.compareTo(other.build);
    }

    private static int compareItems(List<Item> left, List<Item> right) {
      int length = Math.min(left.size(), right.size());
      for (int index = 0; index < length; index++) {
        int comparison = left.get(index).compareTo(right.get(index));
        if (comparison != 0) return comparison;
      }
      return Integer.compare(left.size(), right.size());
    }
  }
}
