package com.github.klboke.kkrepo.server.openvsx;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-side projection of one published extension version. */
record OpenVsxExtensionView(
    String namespace,
    String name,
    String version,
    String targetPlatform,
    boolean prerelease,
    String fileName,
    String displayName,
    String description,
    Map<String, Object> packageJson,
    Instant timestamp,
    String storagePath,
    long size,
    String sha1,
    String sha256) {

  @SuppressWarnings("unchecked")
  static OpenVsxExtensionView fromComponent(ComponentRecord component) {
    Map<String, Object> attributes = component.attributes() == null
        ? Map.of() : component.attributes();
    String version = string(attributes.get("openvsxVersion"), component.version());
    String targetPlatform = string(attributes.get("openvsxTargetPlatform"), null);
    Map<String, Object> packageJson = attributes.get("openvsxPackageJson") instanceof Map<?, ?> map
        ? (Map<String, Object>) map
        : Map.of();
    Instant timestamp;
    try {
      timestamp = Instant.parse(string(attributes.get("openvsxTimestamp"), Instant.EPOCH.toString()));
    } catch (RuntimeException ignored) {
      timestamp = component.lastUpdatedAt() == null ? Instant.EPOCH : component.lastUpdatedAt();
    }
    return new OpenVsxExtensionView(
        component.namespace(),
        component.name(),
        version,
        targetPlatform,
        Boolean.TRUE.equals(attributes.get("openvsxPrerelease"))
            || version.contains("-"),
        string(attributes.get("openvsxFileName"), defaultFileName(
            component.namespace(), component.name(), version, targetPlatform)),
        string(attributes.get("openvsxDisplayName"),
            string(packageJson.get("displayName"), null)),
        string(attributes.get("openvsxDescription"),
            string(packageJson.get("description"), null)),
        packageJson,
        timestamp,
        string(attributes.get("openvsxPath"), null),
        longValue(attributes.get("openvsxSize")),
        string(attributes.get("openvsxSha1"), null),
        string(attributes.get("openvsxSha256"), null));
  }

  static String defaultFileName(
      String namespace, String name, String version, String targetPlatform) {
    return namespace + "." + name + "-" + version
        + (targetPlatform == null || targetPlatform.isBlank() || "universal".equals(targetPlatform)
            ? "" : "-" + targetPlatform)
        + ".vsix";
  }

  String extensionId() {
    return OpenVsxCoordinates.extensionId(namespace, name);
  }

  String versionKey() {
    return OpenVsxComponentFactory.versionKey(version, targetPlatform);
  }

  Map<String, Object> packageFields() {
    Map<String, Object> fields = new LinkedHashMap<>(packageJson);
    fields.remove("publisher");
    fields.remove("name");
    fields.remove("version");
    if (displayName != null) fields.put("displayName", displayName);
    if (description != null) fields.put("description", description);
    return fields;
  }

  private static String string(Object value, String fallback) {
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static long longValue(Object value) {
    if (value instanceof Number number) return number.longValue();
    if (value == null) return 0;
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
