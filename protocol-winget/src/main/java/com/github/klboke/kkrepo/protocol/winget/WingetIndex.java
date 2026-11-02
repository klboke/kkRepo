package com.github.klboke.kkrepo.protocol.winget;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flattened, searchable view of one WinGet package version.
 *
 * <p>The index is derived from the version manifest and is safe to persist as a cache: it can be
 * rebuilt from the manifest at any time, and never becomes the source of truth.
 */
public record WingetIndex(
    String packageIdentifier,
    String packageVersion,
    String channel,
    String packageName,
    String publisher,
    String moniker,
    String defaultLocale,
    List<String> tags,
    List<String> commands,
    List<String> packageFamilyNames,
    List<String> productCodes,
    List<String> upgradeCodes,
    List<String> appsAndFeaturesEntryVersions,
    List<String> installerTypes,
    List<String> markets,
    String normalizedPackageNameAndPublisher,
    String searchText) {

  public WingetIndex {
    packageIdentifier = text(packageIdentifier);
    packageVersion = text(packageVersion);
    channel = text(channel);
    packageName = text(packageName);
    publisher = text(publisher);
    moniker = text(moniker);
    defaultLocale = text(defaultLocale);
    tags = immutable(tags);
    commands = immutable(commands);
    packageFamilyNames = immutable(packageFamilyNames);
    productCodes = immutable(productCodes);
    upgradeCodes = immutable(upgradeCodes);
    appsAndFeaturesEntryVersions = immutable(appsAndFeaturesEntryVersions);
    installerTypes = immutable(installerTypes);
    markets = immutable(markets);
    normalizedPackageNameAndPublisher = text(normalizedPackageNameAndPublisher);
    searchText = text(searchText);
  }

  public WingetVersionSummary summary() {
    return new WingetVersionSummary(packageVersion, channel, packageFamilyNames, productCodes,
        appsAndFeaturesEntryVersions, upgradeCodes);
  }

  /** Builds the lower-cased haystack used by coarse SQL prefilters. */
  public static String buildSearchText(WingetIndex index) {
    Set<String> values = new LinkedHashSet<>();
    add(values, index.packageIdentifier());
    add(values, index.packageName());
    add(values, index.publisher());
    add(values, index.moniker());
    add(values, index.normalizedPackageNameAndPublisher());
    index.tags().forEach(value -> add(values, value));
    index.commands().forEach(value -> add(values, value));
    index.packageFamilyNames().forEach(value -> add(values, value));
    index.productCodes().forEach(value -> add(values, value));
    index.upgradeCodes().forEach(value -> add(values, value));
    index.markets().forEach(value -> add(values, value));
    index.installerTypes().forEach(value -> add(values, value));
    return String.join(" ", values).toLowerCase(Locale.ROOT);
  }

  /** Normalizes a name/publisher pair the way WinGet's normalized match field expects. */
  public static String normalizeNameAndPublisher(String packageName, String publisher) {
    return normalize(packageName) + normalize(publisher);
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
  }

  private static void add(Set<String> values, String value) {
    if (value != null && !value.isBlank()) {
      values.add(value.trim());
    }
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
