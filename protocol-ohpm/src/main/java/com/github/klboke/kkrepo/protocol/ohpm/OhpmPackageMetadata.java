package com.github.klboke.kkrepo.protocol.ohpm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OhpmPackageMetadata {
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
          .maxStringLength(2 * 1024 * 1024).maxNameLength(1024)
          .maxNumberLength(32).maxTokenCount(500_000).build()).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final ObjectNode metadata;
  private final Map<String, Download> downloads;

  private OhpmPackageMetadata(ObjectNode metadata, Map<String, Download> downloads) {
    this.metadata = metadata;
    this.downloads = Map.copyOf(downloads);
  }

  public static OhpmPackageMetadata read(OhpmPackageId packageId, InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw invalid("OHPM metadata exceeds size limit");
    return parse(packageId, JSON.readTree(bytes));
  }

  public static OhpmPackageMetadata parse(OhpmPackageId packageId, JsonNode value) {
    if (!(value instanceof ObjectNode root)) throw invalid("Expected OHPM package metadata");
    identity(root, "name", packageId.id());
    if (root.has("_id")) identity(root, "_id", packageId.id());
    if (!root.path("versions").isObject() || !root.path("dist-tags").isObject()) {
      throw invalid("OHPM metadata requires versions and dist-tags");
    }
    Map<String, Download> downloads = new LinkedHashMap<>();
    root.path("versions").fields().forEachRemaining(entry -> {
      String version = entry.getKey();
      JsonNode manifest = entry.getValue();
      if (!OhpmVersions.isValid(version)) throw invalid("Invalid OHPM metadata version");
      identity(manifest, "name", packageId.id());
      identity(manifest, "version", version);
      if (manifest.has("_id")) identity(manifest, "_id", packageId.id() + "@" + version);
      JsonNode dist = manifest.path("dist");
      if (!dist.isObject()) throw invalid("OHPM version requires dist");
      addDownload(downloads, packageId, version, dist, false);
      if (dist.has("integrity_hsp") || dist.has("resolved_hsp")) {
        if (!"InterfaceHar".equals(type(root, manifest, "packageType"))
            || !"bundle_app".equals(type(root, manifest, "hspType"))) {
          throw invalid("Invalid OHPM HSP metadata");
        }
        addDownload(downloads, packageId, version, dist, true);
      } else if ("InterfaceHar".equals(manifest.path("packageType").asText())
          || manifest.has("hspType")) {
        throw invalid("OHPM HSP metadata requires both attachments");
      }
    });
    root.path("dist-tags").fields().forEachRemaining(entry -> {
      if (!OhpmPathParser.isValidTag(entry.getKey()) || !entry.getValue().isTextual()
          || !root.path("versions").has(entry.getValue().textValue())) {
        throw invalid("OHPM tag must reference an existing version");
      }
    });
    if (root.has("time") && !root.path("time").isObject()) throw invalid("Invalid OHPM time metadata");
    if (root.has("_attachments")) throw invalid("OHPM read metadata must not contain attachments");
    return new OhpmPackageMetadata(root.deepCopy(), downloads);
  }

  public Optional<Download> download(String path) {
    return Optional.ofNullable(downloads.get(path));
  }

  public List<Download> downloads() {
    return List.copyOf(downloads.values());
  }

  public ObjectNode manifest(String version) {
    JsonNode manifest = metadata.path("versions").path(version);
    if (!(manifest instanceof ObjectNode object)) throw invalid("OHPM version does not exist");
    return object.deepCopy();
  }

  public ObjectNode original() {
    return metadata.deepCopy();
  }

  public ObjectNode rewritten(String repositoryBaseUrl) {
    String base = repositoryBaseUrl.isEmpty() || repositoryBaseUrl.endsWith("/")
        ? repositoryBaseUrl : repositoryBaseUrl + "/";
    ObjectNode result = metadata.deepCopy();
    for (Download download : downloads.values()) {
      ObjectNode dist = (ObjectNode) result.path("versions").path(download.version()).path("dist");
      dist.put(download.hsp() ? "resolved_hsp" : "tarball", base + download.path());
    }
    return result;
  }

  private static void addDownload(Map<String, Download> downloads, OhpmPackageId packageId,
      String version, JsonNode dist, boolean hsp) {
    OhpmIntegrity integrity = new OhpmIntegrity(dist.path(hsp ? "integrity_hsp" : "integrity").textValue());
    String remote = dist.path(hsp ? "resolved_hsp" : "tarball").textValue();
    if (remote == null) throw invalid("OHPM download URL is required");
    URI url = URI.create(remote);
    if (!("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
        || url.getHost() == null || url.getRawUserInfo() != null || url.getRawFragment() != null
        || url.getPort() > 65535 || url.getPort() == 0) {
      throw invalid("Invalid OHPM download URL");
    }
    String path = packageId.downloadPath(packageId.id() + "-" + version + (hsp ? ".hsp" : ".har"));
    downloads.put(path, new Download(version, path, remote, integrity, hsp));
  }

  private static String type(JsonNode root, JsonNode manifest, String field) {
    return (manifest.has(field) ? manifest : root).path(field).textValue();
  }

  private static void identity(JsonNode node, String field, String expected) {
    if (!expected.equals(node.path(field).textValue())) throw invalid("OHPM metadata identity mismatch");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  public record Download(String version, String path, String remoteUrl, OhpmIntegrity integrity, boolean hsp) {}
}
