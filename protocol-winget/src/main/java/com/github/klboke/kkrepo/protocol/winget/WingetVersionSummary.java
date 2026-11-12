package com.github.klboke.kkrepo.protocol.winget;

import java.util.List;

/** Search response projection for one package version (WinGet {@code Version} schema). */
public record WingetVersionSummary(
    String packageVersion,
    String channel,
    List<String> packageFamilyNames,
    List<String> productCodes,
    List<String> appsAndFeaturesEntryVersions,
    List<String> upgradeCodes) {
  public WingetVersionSummary {
    packageFamilyNames = immutable(packageFamilyNames);
    productCodes = immutable(productCodes);
    appsAndFeaturesEntryVersions = immutable(appsAndFeaturesEntryVersions);
    upgradeCodes = immutable(upgradeCodes);
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
