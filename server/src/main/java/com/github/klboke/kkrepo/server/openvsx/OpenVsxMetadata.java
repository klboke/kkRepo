package com.github.klboke.kkrepo.server.openvsx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxVersions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds Open VSX registry JSON from the durable component projection. */
@Component
final class OpenVsxMetadata {
  private final ObjectMapper objectMapper;

  OpenVsxMetadata(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  MavenResponse json(Object value, boolean headOnly) {
    byte[] bytes;
    try {
      bytes = objectMapper.writeValueAsBytes(value);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to serialize Open VSX metadata", error);
    }
    String etag = sha256(bytes);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, "application/json", etag, null);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length, "application/json",
        etag, null);
  }

  Map<String, Object> registryVersion(String version, long maxExtensionSize) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", version);
    body.put("maxExtensionSize", maxExtensionSize);
    return body;
  }

  Map<String, Object> namespaceJson(String namespace, List<OpenVsxExtensionView> extensions,
      String baseUrl, String repository) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", namespace);
    body.put("verified", !extensions.isEmpty());
    Map<String, Object> extensionUrls = new LinkedHashMap<>();
    extensions.stream()
        .collect(java.util.stream.Collectors.toMap(
            OpenVsxExtensionView::name, view -> extensionUrl(baseUrl, repository, view),
            (left, right) -> left, LinkedHashMap::new))
        .forEach(extensionUrls::put);
    body.put("extensions", extensionUrls);
    return body;
  }

  Map<String, Object> extensionJson(
      String baseUrl,
      String repository,
      List<OpenVsxExtensionView> allVersions,
      OpenVsxExtensionView selected,
      String targetPlatform) {
    if (selected == null) return null;
    Map<String, Object> body = new LinkedHashMap<>(selected.packageFields());
    body.put("namespace", selected.namespace());
    body.put("name", selected.name());
    body.put("namespaceDisplayName", selected.namespace());
    body.put("namespaceUrl", baseUrl + "/" + repository + "/api/" + selected.namespace());
    body.put("reviewsUrl", baseUrl + "/" + repository + "/api/" + selected.namespace() + "/"
        + selected.name() + "/reviews");
    body.put("version", selected.version());
    if (selected.targetPlatform() != null && !selected.targetPlatform().isBlank()) {
      body.put("targetPlatform", selected.targetPlatform());
    }
    body.put("preRelease", selected.prerelease());
    body.put("publishedBy", Map.of(
        "loginName", selected.namespace(),
        "fullName", selected.namespace()));
    body.put("publishedWithTrustedPublishing", false);
    body.put("verified", true);
    body.put("timestamp", selected.timestamp().toString());
    body.put("downloadCount", 0);
    body.put("reviewCount", 0);
    body.put("averageRating", 0);
    body.put("files", files(baseUrl, repository, selected));
    body.put("allVersions", allVersionsMap(baseUrl, repository, allVersions));
    body.put("allVersionsUrl", baseUrl + "/" + repository + "/api/" + selected.namespace() + "/"
        + selected.name() + "/versions");
    body.put("versionAlias", aliases(allVersions));
    return body;
  }

  Map<String, Object> itemJson(
      String baseUrl, String repository, OpenVsxExtensionView view) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("itemName", view.extensionId());
    body.put("publisher", view.namespace());
    body.put("publisherDisplayName", view.namespace());
    body.put("name", view.name());
    body.put("version", view.version());
    body.put("displayName", view.displayName() == null ? view.name() : view.displayName());
    body.put("description", view.description());
    body.put("lastUpdated", view.timestamp().toString());
    body.put("downloadCount", 0);
    String icon = files(baseUrl, repository, view).get("icon");
    if (icon != null) body.put("icon", icon);
    return body;
  }

  Map<String, Object> versionsJson(
      String baseUrl, String repository, List<OpenVsxExtensionView> allVersions, int offset,
      int size) {
    List<OpenVsxExtensionView> ordered = ordered(allVersions);
    int from = Math.max(0, Math.min(offset, ordered.size()));
    int to = Math.max(from, Math.min(ordered.size(), from + Math.max(0, size)));
    Map<String, Object> versions = new LinkedHashMap<>();
    for (OpenVsxExtensionView view : ordered.subList(from, to)) {
      versions.put(view.version(), baseUrl + "/" + repository + "/api/" + view.namespace() + "/"
          + view.name() + "/" + view.version());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("offset", from);
    body.put("totalSize", ordered.size());
    body.put("versions", versions);
    return body;
  }

  Map<String, Object> searchJson(
      String baseUrl, String repository, List<OpenVsxExtensionView> latestVersions, int offset,
      int size, boolean includeAllVersions, List<OpenVsxExtensionView> allVersions) {
    List<OpenVsxExtensionView> ordered = latestVersions.stream()
        .sorted(Comparator.comparing(OpenVsxExtensionView::name,
            String.CASE_INSENSITIVE_ORDER))
        .toList();
    int from = Math.max(0, Math.min(offset, ordered.size()));
    int to = Math.max(from, Math.min(ordered.size(), from + Math.max(0, size)));
    List<Map<String, Object>> entries = new ArrayList<>();
    for (OpenVsxExtensionView view : ordered.subList(from, to)) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("url", extensionUrl(baseUrl, repository, view));
      entry.put("files", files(baseUrl, repository, view));
      entry.put("name", view.name());
      entry.put("namespace", view.namespace());
      entry.put("version", view.version());
      entry.put("timestamp", view.timestamp().toString());
      entry.put("verified", true);
      entry.put("displayName", view.displayName());
      entry.put("description", view.description());
      entry.put("downloadCount", 0);
      entry.put("reviewCount", 0);
      entry.put("averageRating", 0);
      entry.put("deprecated", false);
      if (includeAllVersions) {
        entry.put("allVersionsUrl", baseUrl + "/" + repository + "/api/" + view.namespace() + "/"
            + view.name() + "/version-references");
      }
      entries.add(entry);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("offset", from);
    body.put("totalSize", ordered.size());
    body.put("extensions", entries);
    return body;
  }

  Map<String, Object> queryJson(
      String baseUrl, String repository, List<OpenVsxExtensionView> views, int offset, int size,
      List<OpenVsxExtensionView> allVersions) {
    int from = Math.max(0, Math.min(offset, views.size()));
    int to = Math.max(from, Math.min(views.size(), from + Math.max(0, size)));
    List<Map<String, Object>> extensions = new ArrayList<>();
    for (OpenVsxExtensionView view : views.subList(from, to)) {
      Map<String, Object> json = extensionJson(baseUrl, repository, allVersions, view, null);
      if (json != null) extensions.add(json);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("offset", from);
    body.put("totalSize", views.size());
    body.put("extensions", extensions);
    return body;
  }

  Map<String, Object> versionReferencesJson(
      String baseUrl, String repository, List<OpenVsxExtensionView> allVersions, int offset,
      int size) {
    List<OpenVsxExtensionView> ordered = ordered(allVersions);
    int from = Math.max(0, Math.min(offset, ordered.size()));
    int to = Math.max(from, Math.min(ordered.size(), from + Math.max(0, size)));
    List<Map<String, Object>> references = new ArrayList<>();
    for (OpenVsxExtensionView view : ordered.subList(from, to)) {
      references.add(Map.of(
          "version", view.version(),
          "targetPlatform", view.targetPlatform() == null ? "" : view.targetPlatform(),
          "url", baseUrl + "/" + repository + "/api/" + view.namespace() + "/" + view.name()
              + "/" + view.version(),
          "timestamp", view.timestamp().toString()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("offset", from);
    body.put("totalSize", ordered.size());
    body.put("versions", references);
    return body;
  }

  /** Minimal VS Code gallery query response sufficient for extension discovery/install. */
  Map<String, Object> vscodeQueryJson(
      String baseUrl, String repository, List<OpenVsxExtensionView> views, int offset, int size) {
    int from = Math.max(0, Math.min(offset, views.size()));
    int to = Math.max(from, Math.min(views.size(), from + Math.max(0, size)));
    List<Map<String, Object>> extensions = new ArrayList<>();
    for (OpenVsxExtensionView view : views.subList(from, to)) {
      Map<String, Object> version = new LinkedHashMap<>();
      version.put("version", view.version());
      version.put("targetPlatform", view.targetPlatform() == null ? "universal"
          : view.targetPlatform());
      version.put("lastUpdated", view.timestamp().toString());
      version.put("assetUri", baseUrl + "/" + repository + "/vscode/asset/" + view.namespace()
          + "/" + view.name() + "/" + view.version());
      version.put("fallbackAssetUri", baseUrl + "/" + repository + "/vscode/asset/"
          + view.namespace() + "/" + view.name() + "/" + view.version());
      version.put("files", List.of(
          Map.of("assetType", "Microsoft.VisualStudio.Services.VSIXPackage",
              "source", baseUrl + "/" + repository + "/vscode/gallery/publishers/"
                  + view.namespace() + "/vsextensions/" + view.name() + "/" + view.version()
                  + "/vspackage")));
      Map<String, Object> extension = new LinkedHashMap<>();
      extension.put("publisherName", view.namespace());
      extension.put("extensionName", view.name());
      extension.put("displayName", view.displayName() == null ? view.name() : view.displayName());
      extension.put("shortDescription", view.description());
      extension.put("versions", List.of(version));
      extension.put("statistics", List.of(Map.of("statisticName", "install", "value", 0)));
      extensions.add(extension);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("results", List.of(Map.of(
        "extensions", extensions,
        "resultMetadata", List.of(Map.of(
            "metadataType", "ResultCount",
            "metadataItems", List.of(Map.of("name", "TotalCount", "count", views.size())))))));
    return body;
  }

  List<OpenVsxExtensionView> ordered(List<OpenVsxExtensionView> views) {
    return views.stream()
        .sorted(Comparator.comparing(OpenVsxExtensionView::version, OpenVsxVersions.DESCENDING)
            .thenComparing(view -> view.targetPlatform() == null ? "" : view.targetPlatform()))
        .toList();
  }

  OpenVsxExtensionView latest(List<OpenVsxExtensionView> views, String targetPlatform) {
    List<OpenVsxExtensionView> candidates;
    if (targetPlatform == null || targetPlatform.isBlank()) {
      candidates = views;
    } else {
      candidates = views.stream()
          .filter(view -> targetPlatform.equals(view.targetPlatform())
              || view.targetPlatform() == null || view.targetPlatform().isBlank()
              || "universal".equals(view.targetPlatform()))
          .toList();
      if (candidates.isEmpty()) return null;
    }
    if (candidates.isEmpty()) return null;
    final List<OpenVsxExtensionView> selectedCandidates = candidates;
    String latest = OpenVsxVersions.latest(
        selectedCandidates.stream().map(OpenVsxExtensionView::version).distinct().toList());
    if (latest == null) return null;
    final String selectedVersion = latest;
    return selectedCandidates.stream()
        .filter(view -> selectedVersion.equals(view.version()))
        .filter(view -> targetPlatform != null && targetPlatform.equals(view.targetPlatform()))
        .findFirst()
        .orElseGet(() -> selectedCandidates.stream()
            .filter(view -> selectedVersion.equals(view.version()))
            .filter(view -> view.targetPlatform() == null || view.targetPlatform().isBlank()
                || "universal".equals(view.targetPlatform()))
            .findFirst()
            .orElseGet(() -> selectedCandidates.stream()
                .filter(view -> selectedVersion.equals(view.version()))
                .findFirst().orElse(null)));
  }

  OpenVsxExtensionView findVersion(
      List<OpenVsxExtensionView> views, String version, String targetPlatform) {
    if (version == null || version.isBlank()) return null;
    List<OpenVsxExtensionView> matching = views.stream()
        .filter(view -> version.equals(view.version()))
        .toList();
    if (matching.isEmpty()) return null;
    if (targetPlatform == null || targetPlatform.isBlank()) {
      return matching.stream()
          .filter(view -> view.targetPlatform() == null || view.targetPlatform().isBlank()
              || "universal".equals(view.targetPlatform()))
          .findFirst()
          .orElse(matching.getFirst());
    }
    return matching.stream()
        .filter(view -> targetPlatform.equals(view.targetPlatform()))
        .findFirst()
        .orElseGet(() -> matching.stream()
            .filter(view -> view.targetPlatform() == null || view.targetPlatform().isBlank()
                || "universal".equals(view.targetPlatform()))
            .findFirst().orElse(null));
  }

  List<OpenVsxExtensionView> latestPerExtension(List<OpenVsxExtensionView> views) {
    Map<String, List<OpenVsxExtensionView>> byName = new LinkedHashMap<>();
    for (OpenVsxExtensionView view : views) {
      byName.computeIfAbsent(view.namespace() + "." + view.name(), ignored -> new ArrayList<>())
          .add(view);
    }
    List<OpenVsxExtensionView> latest = new ArrayList<>();
    for (List<OpenVsxExtensionView> versions : byName.values()) {
      OpenVsxExtensionView selected = latest(versions, null);
      if (selected != null) latest.add(selected);
    }
    return latest;
  }

  private Map<String, String> files(
      String baseUrl, String repository, OpenVsxExtensionView view) {
    String root = baseUrl + "/" + repository + "/api/" + view.namespace() + "/" + view.name()
        + "/" + view.version() + "/file/";
    Map<String, String> files = new LinkedHashMap<>();
    files.put("download", root + view.fileName());
    files.put("manifest", root + "extension.vsixmanifest");
    Object icon = view.packageJson().get("icon");
    if (icon != null && !icon.toString().isBlank()) {
      files.put("icon", root + icon);
    }
    files.put("readme", root + "README.md");
    files.put("license", root + "LICENSE");
    files.put("changelog", root + "CHANGELOG.md");
    return files;
  }

  private Map<String, String> allVersionsMap(
      String baseUrl, String repository, List<OpenVsxExtensionView> allVersions) {
    Map<String, String> versions = new LinkedHashMap<>();
    for (OpenVsxExtensionView view : ordered(allVersions)) {
      versions.put(view.version(), baseUrl + "/" + repository + "/api/" + view.namespace() + "/"
          + view.name() + "/" + view.version());
    }
    return versions;
  }

  private List<String> aliases(List<OpenVsxExtensionView> allVersions) {
    List<String> aliases = new ArrayList<>();
    String latest = OpenVsxVersions.latest(
        allVersions.stream().map(OpenVsxExtensionView::version).distinct().toList());
    if (latest != null) aliases.add("latest");
    if (allVersions.stream().anyMatch(OpenVsxExtensionView::prerelease)) {
      aliases.add("pre-release");
    }
    return aliases;
  }

  private String extensionUrl(
      String baseUrl, String repository, OpenVsxExtensionView view) {
    return baseUrl + "/" + repository + "/api/" + view.namespace() + "/" + view.name();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  static String iso(Instant instant) {
    return instant == null ? Instant.EPOCH.toString() : instant.toString();
  }

  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
