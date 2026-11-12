package com.github.klboke.kkrepo.protocol.winget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Evaluates WinGet manifest search requests against rebuilt package indexes. */
public final class WingetSearch {
  private WingetSearch() {}

  /**
   * Returns matching packages, at most {@code limit}, preserving the incoming package order.
   *
   * <p>Inclusions are OR-ed, Filters are AND-ed, and {@code Query} is matched against the same
   * fields the reference REST source indexes.
   */
  public record Page(List<WingetPackage> packages, int consumedIdentifiers) {
    public Page {
      packages = packages == null ? List.of() : List.copyOf(packages);
    }
  }

  public static List<WingetPackage> search(
      List<WingetIndex> indexes, WingetSearchRequest request, int limit) {
    return searchPage(indexes, request, limit).packages();
  }

  /**
   * Returns matching packages and how many package identifiers were consumed before the page was
   * full. Callers paginating over a SQL identifier batch must advance their cursor by {@code
   * consumedIdentifiers}, not by the whole batch.
   */
  public static Page searchPage(
      List<WingetIndex> indexes, WingetSearchRequest request, int limit) {
    if (indexes == null || indexes.isEmpty() || limit <= 0) {
      return new Page(List.of(), 0);
    }
    Map<String, List<WingetIndex>> grouped = new LinkedHashMap<>();
    for (WingetIndex index : indexes) {
      grouped.computeIfAbsent(index.packageIdentifier(), ignored -> new ArrayList<>()).add(index);
    }
    List<WingetPackage> results = new ArrayList<>();
    int consumed = 0;
    for (List<WingetIndex> versions : grouped.values()) {
      consumed++;
      List<WingetIndex> matched = new ArrayList<>();
      for (WingetIndex version : versions) {
        if (matches(version, request)) {
          matched.add(version);
        }
      }
      if (matched.isEmpty()) {
        continue;
      }
      matched.sort(Comparator.comparing(WingetIndex::packageVersion, WingetVersions::compare)
          .thenComparing(WingetIndex::channel));
      List<WingetVersionSummary> summaries = new ArrayList<>(matched.size());
      Set<String> seen = new LinkedHashSet<>();
      for (WingetIndex version : matched) {
        String identity = version.packageVersion() + '\u0000' + WingetVersions.channelKey(version.channel());
        if (seen.add(identity)) {
          summaries.add(version.summary());
        }
      }
      WingetIndex first = matched.getFirst();
      results.add(new WingetPackage(first.packageIdentifier(), first.packageName(),
          first.publisher(), summaries));
      if (results.size() >= limit) {
        break;
      }
    }
    return new Page(results, consumed);
  }

  /** Collects the coarse SQL terms for a request's free-text query. */
  public static List<String> prefilterTerms(WingetSearchRequest request) {
    if (request == null || request.query() == null || request.query().keyWord() == null) {
      return List.of();
    }
    List<String> terms = new ArrayList<>();
    for (String term : request.query().keyWord().trim().split("\\s+")) {
      if (!term.isEmpty()) {
        terms.add(term.toLowerCase(Locale.ROOT));
      }
      if (terms.size() >= 8) {
        break;
      }
    }
    return terms;
  }

  public static boolean matches(WingetIndex index, WingetSearchRequest request) {
    if (request.query() != null && !matchesQuery(index, request.query())) {
      return false;
    }
    if (!request.inclusions().isEmpty()
        && request.inclusions().stream().noneMatch(filter -> matchesFilter(index, filter))) {
      return false;
    }
    return request.filters().stream().allMatch(filter -> matchesFilter(index, filter));
  }

  private static boolean matchesQuery(WingetIndex index, WingetSearchRequest.SearchMatch query) {
    List<String> values = new ArrayList<>();
    values.add(index.packageIdentifier());
    values.add(index.packageName());
    values.add(index.publisher());
    values.add(index.moniker());
    values.addAll(index.tags());
    values.addAll(index.commands());
    values.addAll(index.packageFamilyNames());
    values.addAll(index.productCodes());
    values.addAll(index.upgradeCodes());
    return matchesAny(values, query);
  }

  private static boolean matchesFilter(
      WingetIndex index, WingetSearchRequest.PackageMatchFilter filter) {
    List<String> values = switch (filter.packageMatchField()) {
      case PACKAGE_IDENTIFIER -> List.of(index.packageIdentifier());
      case PACKAGE_NAME -> List.of(index.packageName());
      case PUBLISHER -> List.of(index.publisher());
      case MONIKER -> List.of(index.moniker());
      case TAG -> index.tags();
      case COMMAND -> index.commands();
      case PACKAGE_FAMILY_NAME -> index.packageFamilyNames();
      case PRODUCT_CODE -> index.productCodes();
      case UPGRADE_CODE -> index.upgradeCodes();
      case NORMALIZED_PACKAGE_NAME_AND_PUBLISHER -> List.of(index.normalizedPackageNameAndPublisher());
      case MARKET -> index.markets();
      case HAS_INSTALLER_TYPE -> index.installerTypes();
    };
    return matchesAny(values, filter.requestMatch());
  }

  private static boolean matchesAny(List<String> values, WingetSearchRequest.SearchMatch match) {
    String keyword = match.keyWord();
    if (keyword == null || keyword.isBlank()) {
      return true;
    }
    for (String value : values) {
      if (value == null) {
        continue;
      }
      switch (match.matchType()) {
        case EXACT -> {
          if (value.equals(keyword)) return true;
        }
        case CASE_INSENSITIVE -> {
          if (value.equalsIgnoreCase(keyword)) return true;
        }
        case STARTS_WITH -> {
          if (value.startsWith(keyword)) return true;
        }
        case SUBSTRING -> {
          if (value.contains(keyword)) return true;
        }
        case SUBSTRING_IGNORE_CASE -> {
          if (value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
