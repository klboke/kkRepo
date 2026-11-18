package com.github.klboke.kkrepo.protocol.ohpm;

import com.fasterxml.jackson.databind.JsonNode;

public record OhpmUnpublishRequest(OhpmPackageId packageId, String version) {
  public static OhpmUnpublishRequest parse(OhpmPath path, JsonNode body) {
    if (path == null || path.kind() != OhpmPath.Kind.UNPUBLISH) {
      throw new IllegalArgumentException("Not an OHPM unpublish path");
    }
    String version = path.version();
    if (body != null) {
      if (!body.isObject() || body.size() != 1 || !body.path("version").isTextual()) {
        throw new IllegalArgumentException("OHPM unpublish body must contain a version string");
      }
      String bodyVersion = body.path("version").textValue();
      if (!bodyVersion.isEmpty() && !OhpmVersions.isValid(bodyVersion)) {
        throw new IllegalArgumentException("Invalid OHPM unpublish version");
      }
      if (version != null && !version.equals(bodyVersion)) {
        throw new IllegalArgumentException("Conflicting OHPM unpublish versions");
      }
      version = bodyVersion.isEmpty() ? null : bodyVersion;
    }
    return new OhpmUnpublishRequest(path.packageId(), version);
  }
}
