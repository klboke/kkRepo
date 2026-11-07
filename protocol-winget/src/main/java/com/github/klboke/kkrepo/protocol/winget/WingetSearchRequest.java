package com.github.klboke.kkrepo.protocol.winget;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parsed WinGet {@code manifestSearch} request. */
public record WingetSearchRequest(
    SearchMatch query,
    List<PackageMatchFilter> inclusions,
    List<PackageMatchFilter> filters,
    int maximumResults,
    boolean fetchAllManifests) {

  public static final int DEFAULT_MAXIMUM_RESULTS = 20;
  public static final int MAXIMUM_RESULTS_LIMIT = 1000;

  public WingetSearchRequest {
    inclusions = inclusions == null ? List.of() : List.copyOf(inclusions);
    filters = filters == null ? List.of() : List.copyOf(filters);
  }

  public enum MatchType {
    EXACT,
    CASE_INSENSITIVE,
    STARTS_WITH,
    SUBSTRING,
    SUBSTRING_IGNORE_CASE;

    static MatchType parse(String value) {
      if (value == null || value.isBlank()) {
        return CASE_INSENSITIVE;
      }
      String normalized = value.trim().replace("_", "").replace("-", "");
      for (MatchType type : values()) {
        if (type.name().replace("_", "").equalsIgnoreCase(normalized)) {
          return type;
        }
      }
      throw WingetException.badRequest("Unknown RequestMatch.MatchType: " + value);
    }
  }

  public enum PackageMatchField {
    PACKAGE_IDENTIFIER,
    PACKAGE_NAME,
    PUBLISHER,
    MONIKER,
    TAG,
    COMMAND,
    PACKAGE_FAMILY_NAME,
    PRODUCT_CODE,
    UPGRADE_CODE,
    NORMALIZED_PACKAGE_NAME_AND_PUBLISHER,
    MARKET,
    HAS_INSTALLER_TYPE;

    static PackageMatchField parse(String value) {
      if (value == null || value.isBlank()) {
        throw WingetException.badRequest("PackageMatchField is required");
      }
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "packageidentifier" -> PACKAGE_IDENTIFIER;
        case "packagename" -> PACKAGE_NAME;
        case "publisher" -> PUBLISHER;
        case "moniker" -> MONIKER;
        case "tag" -> TAG;
        case "command" -> COMMAND;
        case "packagefamilyname" -> PACKAGE_FAMILY_NAME;
        case "productcode" -> PRODUCT_CODE;
        case "upgradecode" -> UPGRADE_CODE;
        case "normalizedpackagenameandpublisher" -> NORMALIZED_PACKAGE_NAME_AND_PUBLISHER;
        case "market" -> MARKET;
        case "hasinstallertype" -> HAS_INSTALLER_TYPE;
        default -> throw WingetException.badRequest("Unknown PackageMatchField: " + value);
      };
    }
  }

  public record SearchMatch(String keyWord, MatchType matchType) {}

  public record PackageMatchFilter(PackageMatchField packageMatchField, SearchMatch requestMatch) {}

  public static WingetSearchRequest parse(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw WingetException.badRequest("A manifestSearch request must be a JSON object");
    }
    SearchMatch query = parseSearchMatch(root.get("Query"), "Query");
    List<PackageMatchFilter> inclusions = parseFilters(root.get("Inclusions"), "Inclusions");
    List<PackageMatchFilter> filters = parseFilters(root.get("Filters"), "Filters");
    int maximumResults = DEFAULT_MAXIMUM_RESULTS;
    JsonNode maximumNode = root.get("MaximumResults");
    if (maximumNode != null && !maximumNode.isNull()) {
      if (!maximumNode.isIntegralNumber()) {
        throw WingetException.badRequest("MaximumResults must be an integer");
      }
      maximumResults = maximumNode.asInt(DEFAULT_MAXIMUM_RESULTS);
    }
    maximumResults = Math.max(1, Math.min(maximumResults, MAXIMUM_RESULTS_LIMIT));
    boolean fetchAll = root.path("FetchAllManifests").asBoolean(false);
    return new WingetSearchRequest(query, inclusions, filters, maximumResults, fetchAll);
  }

  private static SearchMatch parseSearchMatch(JsonNode node, String field) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw WingetException.badRequest(field + " must be an object");
    }
    String keyword = text(node.get("KeyWord"));
    if (keyword == null) {
      throw WingetException.badRequest(field + ".KeyWord is required");
    }
    String matchType = text(node.get("MatchType"));
    return new SearchMatch(keyword, MatchType.parse(matchType));
  }

  private static List<PackageMatchFilter> parseFilters(JsonNode node, String field) {
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray()) {
      throw WingetException.badRequest(field + " must be an array");
    }
    List<PackageMatchFilter> filters = new ArrayList<>(node.size());
    for (JsonNode entry : node) {
      if (!entry.isObject()) {
        throw WingetException.badRequest(field + " entries must be objects");
      }
      PackageMatchField matchField = PackageMatchField.parse(text(entry.get("PackageMatchField")));
      SearchMatch match = parseSearchMatch(entry.get("RequestMatch"), field + ".RequestMatch");
      if (match == null) {
        throw WingetException.badRequest(field + ".RequestMatch is required");
      }
      filters.add(new PackageMatchFilter(matchField, match));
    }
    return filters;
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual()) {
      return null;
    }
    String value = node.asText().trim();
    return value.isEmpty() ? null : value;
  }
}
