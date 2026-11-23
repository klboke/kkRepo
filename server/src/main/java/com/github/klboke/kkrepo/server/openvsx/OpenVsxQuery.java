package com.github.klboke.kkrepo.server.openvsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Query parameters accepted by the Open VSX registry and VS Code gallery search routes. */
public record OpenVsxQuery(
    String query,
    String category,
    String targetPlatform,
    int size,
    int offset,
    String sortBy,
    String sortOrder,
    boolean includeAllVersions,
    List<String> namespaceNames,
    List<String> extensionNames,
    String extensionVersion,
    List<String> extensionIds) {

  public OpenVsxQuery {
    namespaceNames = namespaceNames == null ? List.of() : List.copyOf(namespaceNames);
    extensionNames = extensionNames == null ? List.of() : List.copyOf(extensionNames);
    extensionIds = extensionIds == null ? List.of() : List.copyOf(extensionIds);
  }

  public static OpenVsxQuery from(HttpServletRequest request) {
    return new OpenVsxQuery(
        request.getParameter("query"),
        request.getParameter("category"),
        blankToNull(request.getParameter("targetPlatform")),
        intParam(request, "size", 18, 0, 1000),
        intParam(request, "offset", 0, 0, Integer.MAX_VALUE),
        request.getParameter("sortBy"),
        request.getParameter("sortOrder"),
        booleanParam(request, "includeAllVersions"),
        scalar(request.getParameter("namespaceName")),
        scalar(request.getParameter("extensionName")),
        request.getParameter("extensionVersion"),
        scalar(request.getParameter("extensionId")));
  }

  /**
   * Applies the filter criteria carried in a VS Code gallery {@code extensionquery} POST body.
   * The gallery sends search terms, publisher, extension id/name and paging in the JSON body
   * rather than in the URL, so ignoring it makes read-only discovery return the wrong page.
   *
   * <p>Filter types follow the official default Gallery contract: 4 = ExtensionId, 5 = Category
   * and 7 = ExtensionName. Batched client queries carry several name/ID criteria, so same-typed
   * values accumulate instead of overwriting the previous one; within one field they OR, across
   * fields they AND.
   */
  public OpenVsxQuery withVscodeBody(byte[] body, ObjectMapper objectMapper) {
    if (body == null || body.length == 0) return this;
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode filter = root.path("filters").path(0);
      JsonNode criteria = filter.path("criteria");
      String parsedQuery = query;
      String parsedCategory = category;
      List<String> parsedNamespaces = new ArrayList<>(namespaceNames);
      List<String> parsedNames = new ArrayList<>(extensionNames);
      List<String> parsedIds = new ArrayList<>(extensionIds);
      if (criteria.isArray()) {
        for (JsonNode criterion : criteria) {
          String value = text(criterion.path("value"));
          if (value == null) continue;
          switch (criterion.path("filterType").asInt(-1)) {
            case 4 -> addUnique(parsedIds, value);
            case 5 -> parsedCategory = value;
            case 6 -> addUnique(parsedNamespaces, value);
            case 7 -> addUnique(parsedNames, value);
            case 10 -> parsedQuery = value;
            case 11 -> parsedCategory = value;
            default -> {
              // Target/Tag/Featured and unknown filters do not map to kkrepo metadata filters.
            }
          }
        }
      }
      int parsedSize = size;
      int parsedOffset = offset;
      int pageSize = filter.path("pageSize").asInt(0);
      int pageNumber = filter.path("pageNumber").asInt(0);
      if (pageSize > 0) {
        parsedSize = Math.min(1000, pageSize);
        if (pageNumber > 0) {
          long wanted = (long) (pageNumber - 1) * parsedSize;
          parsedOffset = (int) Math.min(Integer.MAX_VALUE, Math.max(0, wanted));
        }
      }
      return new OpenVsxQuery(parsedQuery, parsedCategory, targetPlatform, parsedSize,
          parsedOffset, sortBy, sortOrder, includeAllVersions, parsedNamespaces, parsedNames,
          extensionVersion, parsedIds);
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid VS Code gallery query", invalid);
    }
  }

  private static void addUnique(List<String> values, String value) {
    if (!values.stream().anyMatch(value::equalsIgnoreCase)) {
      values.add(value);
    }
  }

  private static String text(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return null;
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }

  boolean matches(String namespace, String name, String version) {
    if (!extensionIds.isEmpty()
        && extensionIds.stream().noneMatch(id -> id.equalsIgnoreCase(namespace + "." + name))) {
      return false;
    }
    if (!namespaceNames.isEmpty()
        && namespaceNames.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(namespace))) {
      return false;
    }
    if (!extensionNames.isEmpty()
        && extensionNames.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(name))) {
      return false;
    }
    return extensionVersion == null || extensionVersion.isBlank()
        || extensionVersion.equals(version);
  }

  boolean hasTextQuery() {
    return query != null && !query.isBlank();
  }

  private static List<String> scalar(String value) {
    return value == null || value.isBlank() ? List.of() : List.of(value);
  }

  private static int intParam(
      HttpServletRequest request, String name, int fallback, int min, int max) {
    String value = request.getParameter(name);
    if (value == null || value.isBlank()) return fallback;
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(min, Math.min(max, parsed));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean booleanParam(HttpServletRequest request, String name) {
    String value = request.getParameter(name);
    return "true".equalsIgnoreCase(value) || "1".equals(value);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
