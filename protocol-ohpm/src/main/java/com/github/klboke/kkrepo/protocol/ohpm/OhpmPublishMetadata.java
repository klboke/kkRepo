package com.github.klboke.kkrepo.protocol.ohpm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record OhpmPublishMetadata(
    OhpmPackageId packageId,
    String version,
    ObjectNode metadata,
    OhpmIntegrity harIntegrity,
    OhpmIntegrity hspIntegrity) {

  public static OhpmPublishMetadata parse(OhpmPackageId packageId, JsonNode body) {
    if (!(body instanceof ObjectNode root)) {
      throw new IllegalArgumentException("OHPM publish metadata must be an object");
    }
    requireText(root, "name", packageId.id());
    requireText(root, "_id", packageId.id());
    JsonNode versions = root.path("versions");
    if (!versions.isObject() || versions.size() != 1) {
      throw new IllegalArgumentException("OHPM publish must contain exactly one version");
    }
    String version = versions.fieldNames().next();
    if (!OhpmVersions.isValid(version)) throw new IllegalArgumentException("Invalid OHPM version");
    JsonNode manifest = versions.path(version);
    requireText(manifest, "name", packageId.id());
    requireText(manifest, "version", version);
    if (manifest.has("_id")) requireText(manifest, "_id", packageId.id() + "@" + version);
    JsonNode tags = root.path("dist-tags");
    if (!tags.isObject() || tags.isEmpty()) {
      throw new IllegalArgumentException("OHPM publish requires dist-tags");
    }
    tags.fields().forEachRemaining(tag -> {
      if (!OhpmPathParser.isValidTag(tag.getKey()) || !tag.getValue().isTextual()
          || !version.equals(tag.getValue().textValue())) {
        throw new IllegalArgumentException("OHPM publish tags must reference the published version");
      }
    });
    String packageType = matchingText(root, manifest, "packageType");
    if (packageType != null && !packageType.equals("InterfaceHar")) {
      throw new IllegalArgumentException("Unsupported OHPM packageType");
    }
    boolean hsp = "InterfaceHar".equals(packageType);
    String hspType = matchingText(root, manifest, "hspType");
    if (hsp && !"bundle_app".equals(hspType)) {
      throw new IllegalArgumentException("OHPM HSP requires hspType bundle_app");
    }
    if (!hasText(manifest, "types") && (hsp || !hasText(manifest, "main"))) {
      throw new IllegalArgumentException(hsp ? "OHPM HSP requires types" : "OHPM HAR requires main or types");
    }
    JsonNode dist = manifest.path("dist");
    OhpmIntegrity harIntegrity = new OhpmIntegrity(dist.path("integrity").textValue());
    OhpmIntegrity hspIntegrity = hsp ? new OhpmIntegrity(dist.path("integrity_hsp").textValue()) : null;
    if (!hsp && (hspType != null || dist.has("integrity_hsp") || dist.has("resolved_hsp"))) {
      throw new IllegalArgumentException("OHPM HAR cannot contain HSP metadata");
    }
    return new OhpmPublishMetadata(packageId, version, root.deepCopy(), harIntegrity, hspIntegrity);
  }

  public boolean isHsp() {
    return hspIntegrity != null;
  }

  private static String matchingText(JsonNode root, JsonNode manifest, String field) {
    if (root.has(field) && !hasText(root, field) || manifest.has(field) && !hasText(manifest, field)) {
      throw new IllegalArgumentException("Invalid OHPM " + field);
    }
    String rootValue = root.path(field).textValue();
    String manifestValue = manifest.path(field).textValue();
    if (rootValue != null && manifestValue != null && !rootValue.equals(manifestValue)) {
      throw new IllegalArgumentException("Inconsistent OHPM " + field);
    }
    return rootValue == null ? manifestValue : rootValue;
  }

  private static void requireText(JsonNode node, String field, String expected) {
    if (!expected.equals(node.path(field).textValue())) {
      throw new IllegalArgumentException("OHPM " + field + " does not match the published identity");
    }
  }

  private static boolean hasText(JsonNode node, String field) {
    return node.path(field).isTextual() && !node.path(field).textValue().isBlank();
  }
}
