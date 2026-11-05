package com.github.klboke.kkrepo.server.openvsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
    String namespaceName,
    String extensionName,
    String extensionVersion,
    String extensionId) {

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
        request.getParameter("namespaceName"),
        request.getParameter("extensionName"),
        request.getParameter("extensionVersion"),
        request.getParameter("extensionId"));
  }

  /**
   * Applies the filter criteria carried in a VS Code gallery {@code extensionquery} POST body.
   * The gallery sends search terms, publisher, extension id/name and paging in the JSON body
   * rather than in the URL, so ignoring it makes read-only discovery return the wrong page.
   */
  public OpenVsxQuery withVscodeBody(byte[] body, ObjectMapper objectMapper) {
    if (body == null || body.length == 0) return this;
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode filter = root.path("filters").path(0);
      JsonNode criteria = filter.path("criteria");
      String parsedQuery = query;
      String parsedCategory = category;
      String parsedNamespace = namespaceName;
      String parsedExtension = extensionName;
      String parsedVersion = extensionVersion;
      String parsedExtensionId = extensionId;
      if (criteria.isArray()) {
        for (JsonNode criterion : criteria) {
          String value = text(criterion.path("value"));
          if (value == null) continue;
          switch (criterion.path("filterType").asInt(-1)) {
            case 4 -> parsedExtension = value;
            case 5 -> parsedExtensionId = value;
            case 6 -> parsedNamespace = value;
            case 7 -> parsedVersion = value;
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
          parsedOffset, sortBy, sortOrder, includeAllVersions, parsedNamespace, parsedExtension,
          parsedVersion, parsedExtensionId);
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid VS Code gallery query", invalid);
    }
  }

  private static String text(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return null;
    String text = value.asText();
    return text == null || text.isBlank() ? null : text;
  }

  boolean matches(String namespace, String name, String version) {
    if (extensionId != null && !extensionId.isBlank()
        && !extensionId.equalsIgnoreCase(namespace + "." + name)) {
      return false;
    }
    if (namespaceName != null && !namespaceName.isBlank()
        && !namespaceName.equalsIgnoreCase(namespace)) {
      return false;
    }
    if (extensionName != null && !extensionName.isBlank()
        && !extensionName.equalsIgnoreCase(name)) {
      return false;
    }
    return extensionVersion == null || extensionVersion.isBlank()
        || extensionVersion.equals(version);
  }

  boolean hasTextQuery() {
    return query != null && !query.isBlank();
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
