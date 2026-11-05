package com.github.klboke.kkrepo.protocol.ohpm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OhpmGroupMetadata {
  public static final String SOURCE_PARAMETER = "kkrepo_source";
  private final ObjectNode metadata;
  private final Map<String, Selection> files;

  private OhpmGroupMetadata(ObjectNode metadata, Map<String, Selection> files) {
    this.metadata = metadata;
    this.files = Map.copyOf(files);
  }

  public static OhpmGroupMetadata merge(long groupId, OhpmPackageId packageId, List<Source> sources) {
    ObjectNode root = JsonNodeFactory.instance.objectNode().put("name", packageId.id()).put("_id", packageId.id());
    ObjectNode versions = root.putObject("versions");
    ObjectNode tags = root.putObject("dist-tags");
    ObjectNode time = root.putObject("time");
    Map<String, Selection> files = new LinkedHashMap<>();
    boolean first = true;
    for (Source source : sources) {
      ObjectNode member = source.metadata().original();
      if (!packageId.id().equals(member.path("name").asText())) throw new IllegalArgumentException("Wrong OHPM package");
      if (member.path("versions").isEmpty()) continue;
      if (first) {
        member.fields().forEachRemaining(entry -> {
          if (!List.of("versions", "dist-tags", "time", "_rev", "packageType", "hspType").contains(entry.getKey())) {
            root.set(entry.getKey(), entry.getValue().deepCopy());
          }
        });
        first = false;
      }
      member.path("versions").fields().forEachRemaining(entry -> {
        String version = entry.getKey();
        if (versions.has(version)) return;
        ObjectNode manifest = source.metadata().manifest(version);
        String stem = packageId.downloadPath(packageId.id() + "-" + version);
        var har = source.metadata().download(stem + ".har").orElseThrow();
        var hsp = source.metadata().download(stem + ".hsp").orElse(null);
        if (hsp != null) manifest.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
        versions.set(version, manifest);
        String fingerprint = fingerprint(groupId, source.repositoryId(), packageId, version, har, hsp);
        files.put(har.path(), new Selection(source.repositoryId(), har, fingerprint));
        if (hsp != null) files.put(hsp.path(), new Selection(source.repositoryId(), hsp, fingerprint));
        if (member.path("time").has(version)) time.set(version, member.path("time").path(version).deepCopy());
      });
      member.path("dist-tags").fields().forEachRemaining(entry -> {
        if (!tags.has(entry.getKey())) tags.set(entry.getKey(), entry.getValue().deepCopy());
      });
      mergeTime(time, member.path("time"), "created", false);
      mergeTime(time, member.path("time"), "modified", true);
    }
    return new OhpmGroupMetadata(root, files);
  }

  public boolean isEmpty() {
    return metadata.path("versions").isEmpty();
  }

  public Optional<Selection> selection(String path) {
    return Optional.ofNullable(files.get(path));
  }

  public ObjectNode rewritten(String baseUrl) {
    String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    ObjectNode result = metadata.deepCopy();
    for (var entry : files.entrySet()) {
      Selection selected = entry.getValue();
      ObjectNode dist = (ObjectNode) result.path("versions").path(selected.download().version()).path("dist");
      dist.put(selected.download().hsp() ? "resolved_hsp" : "tarball",
          base + entry.getKey() + "?" + SOURCE_PARAMETER + "=" + selected.fingerprint());
    }
    return result;
  }

  private static String fingerprint(long groupId, long repositoryId, OhpmPackageId packageId, String version,
      OhpmPackageMetadata.Download har, OhpmPackageMetadata.Download hsp) {
    String identity = groupId + "\n" + repositoryId + "\n" + packageId.id() + "\n" + version + "\n"
        + har.integrity().value() + "\n" + (hsp == null ? "" : hsp.integrity().value());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static void mergeTime(ObjectNode target, JsonNode source, String field, boolean latest) {
    if (!source.path(field).isTextual()) return;
    try {
      Instant candidate = Instant.parse(source.path(field).asText());
      if (!target.has(field) || (latest
          ? candidate.isAfter(Instant.parse(target.path(field).asText()))
          : candidate.isBefore(Instant.parse(target.path(field).asText())))) {
        target.put(field, candidate.toString());
      }
    } catch (DateTimeParseException ignored) {
    }
  }

  public record Source(long repositoryId, OhpmPackageMetadata metadata) {}
  public record Selection(long repositoryId, OhpmPackageMetadata.Download download, String fingerprint) {}
}
