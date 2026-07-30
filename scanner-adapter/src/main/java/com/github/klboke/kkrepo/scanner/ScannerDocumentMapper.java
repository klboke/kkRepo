package com.github.klboke.kkrepo.scanner;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import com.github.klboke.kkrepo.security.scan.ScannerContract.CatalogResponse;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Component;
import com.github.klboke.kkrepo.security.scan.ScannerContract.Finding;
import com.github.klboke.kkrepo.security.scan.ScannerContract.MatchResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps third-party JSON into the bounded, engine-neutral kkRepo contract. */
@org.springframework.stereotype.Component
public class ScannerDocumentMapper {
  private static final int MAX_JSON_FIELD = 16_384;
  private static final int MAX_ENGINE_VERSION = 128;
  private static final int MAX_DATABASE_REVISION = 255;
  private static final int MAX_SPEC_VERSION = 32;
  private static final int MAX_COMPONENT_REF = 1_024;
  private static final int MAX_PACKAGE_URL = 2_048;
  private static final int MAX_COMPONENT_TYPE = 64;
  private static final int MAX_COMPONENT_NAMESPACE = 512;
  private static final int MAX_COMPONENT_NAME = 512;
  private static final int MAX_COMPONENT_VERSION = 512;
  private static final int MAX_ADVISORY_ID = 255;
  private static final int MAX_DATA_SOURCE = 2_048;
  private static final int MAX_PACKAGE_NAME = 512;
  private static final int MAX_INSTALLED_VERSION = 512;
  private static final int MAX_CVSS_VECTOR = 255;
  private static final int MAX_TITLE = 1_024;
  private static final int MAX_SOURCE_STATUS = 64;
  private static final int MAX_DESCRIPTION_UTF8_BYTES = 65_535;
  private static final int MAX_DEPENDENCY_PROJECTION_COUNT = 100_000;

  private final ObjectMapper mapper;

  public ScannerDocumentMapper(ObjectMapper mapper) {
    this.mapper = mapper.copy();
    this.mapper.getFactory().setStreamReadConstraints(
        StreamReadConstraints.builder()
            .maxDocumentLength(128L * 1024 * 1024)
            .maxTokenCount(2_000_000)
            .maxNestingDepth(256)
            .maxNumberLength(1_000)
            .maxStringLength(64 * 1024)
            .maxNameLength(4 * 1024)
            .build());
  }

  public CatalogResponse catalog(
      byte[] cyclonedx,
      String actualInputSha256,
      String syftVersion,
      String capabilityDigest,
      Map<String, Object> extraSummary) {
    try {
      JsonNode root = mapper.readTree(cyclonedx);
      if (!"CycloneDX".equalsIgnoreCase(text(root, "bomFormat"))) {
        throw invalid("SYFT_SBOM_INVALID", "Syft did not return a CycloneDX document");
      }
      LinkedHashMap<String, Component> components = new LinkedHashMap<>();
      JsonNode nodes = root.path("components");
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          if (components.size() >= ScannerContract.MAX_COMPONENT_PROJECTION_COUNT) break;
          Component component = component(node);
          components.putIfAbsent(component.componentRef(), component);
        }
      }
      int dependencyCount = 0;
      JsonNode dependencies = root.path("dependencies");
      if (dependencies.isArray()) {
        for (JsonNode dependency : dependencies) {
          dependencyCount += Math.min(
              MAX_DEPENDENCY_PROJECTION_COUNT - dependencyCount,
              dependency.path("dependsOn").isArray() ? dependency.path("dependsOn").size() : 0);
          if (dependencyCount >= MAX_DEPENDENCY_PROJECTION_COUNT) break;
        }
      }
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("componentProjectionCount", components.size());
      summary.put("dependencyProjectionCount", dependencyCount);
      if (extraSummary != null) summary.putAll(extraSummary);
      return new CatalogResponse(
          "syft-grype-v1",
          "1",
          "syft",
          syftVersion,
          capabilityDigest,
          actualInputSha256,
          nodes.size() > components.size()
              ? ScanCompleteness.PARTIAL : ScanCompleteness.COMPLETE,
          "CycloneDX",
          bounded(text(root, "specVersion"), MAX_SPEC_VERSION),
          nodes.isArray() ? nodes.size() : 0,
          dependencyCount,
          cyclonedx,
          List.copyOf(components.values()),
          summary);
    } catch (IOException e) {
      throw invalid("SYFT_SBOM_INVALID", "Syft returned malformed CycloneDX JSON", e);
    }
  }

  public MatchResponse match(
      byte[] report,
      String grypeVersion,
      DatabaseProvenance database,
      String capabilityDigest) {
    try {
      JsonNode root = mapper.readTree(report);
      List<Finding> findings = new ArrayList<>();
      JsonNode matches = root.path("matches");
      if (matches.isArray()) {
        for (JsonNode match : matches) {
          if (findings.size() >= ScannerContract.MAX_FINDING_PROJECTION_COUNT) break;
          findings.add(finding(match));
        }
      }
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Severity severity : Severity.values()) counts.put(severity.name(), 0);
      for (Finding finding : findings) {
        counts.computeIfPresent(finding.severity().name(), (ignored, value) -> value + 1);
      }
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("findingProjectionCount", findings.size());
      summary.put("severityCounts", counts);
      summary.put("matchesReported", matches.isArray() ? matches.size() : 0);
      return new MatchResponse(
          "syft-grype-v1",
          "1",
          "grype",
          grypeVersion,
          database.revision(),
          database.updatedAt(),
          capabilityDigest,
          matches.size() > findings.size()
              ? ScanCompleteness.PARTIAL : ScanCompleteness.COMPLETE,
          report,
          findings,
          summary);
    } catch (IOException e) {
      throw invalid("GRYPE_REPORT_INVALID", "Grype returned malformed JSON", e);
    }
  }

  public byte[] mergeCycloneDx(List<PlatformSbom> documents) {
    return mergeCycloneDx(documents, Integer.MAX_VALUE - 1L);
  }

  public byte[] mergeCycloneDx(List<PlatformSbom> documents, long maxOutputBytes) {
    if (documents.isEmpty()) {
      throw invalid("OCI_NO_PLATFORM_SCANNED", "No requested OCI platform could be scanned");
    }
    try {
      JsonNode base = mapper.readTree(documents.getFirst().document());
      if (!(base instanceof ObjectNode baseObject)) {
        throw invalid("OCI_SBOM_INVALID", "OCI platform SBOM root must be an object");
      }
      ObjectNode result = baseObject.deepCopy();
      LinkedHashMap<String, JsonNode> components = new LinkedHashMap<>();
      LinkedHashMap<String, LinkedHashSet<String>> platforms = new LinkedHashMap<>();
      LinkedHashMap<String, LinkedHashSet<String>> dependencies = new LinkedHashMap<>();
      for (PlatformSbom document : documents) {
        JsonNode root = mapper.readTree(document.document());
        for (JsonNode component : root.path("components")) {
          String ref = componentRef(component);
          components.putIfAbsent(ref, component.deepCopy());
          platforms.computeIfAbsent(ref, ignored -> new LinkedHashSet<>()).add(document.platform());
        }
        for (JsonNode dependency : root.path("dependencies")) {
          String ref = bounded(text(dependency, "ref"));
          if (ref == null) continue;
          LinkedHashSet<String> values =
              dependencies.computeIfAbsent(ref, ignored -> new LinkedHashSet<>());
          for (JsonNode value : dependency.path("dependsOn")) {
            if (value.isTextual()) values.add(bounded(value.asText()));
          }
        }
      }
      var componentArray = mapper.createArrayNode();
      for (Map.Entry<String, JsonNode> entry : components.entrySet()) {
        if (!(entry.getValue() instanceof ObjectNode sourceComponent)) continue;
        ObjectNode component = sourceComponent.deepCopy();
        ArrayNode properties = component.withArray("properties");
        var property = mapper.createObjectNode();
        property.put("name", "kkrepo:oci:platforms");
        property.put("value", String.join(",", platforms.getOrDefault(
            entry.getKey(), new LinkedHashSet<>())));
        properties.add(property);
        componentArray.add(component);
      }
      var dependencyArray = mapper.createArrayNode();
      dependencies.forEach((ref, values) -> {
        var dependency = mapper.createObjectNode();
        dependency.put("ref", ref);
        var dependsOn = dependency.putArray("dependsOn");
        values.forEach(dependsOn::add);
        dependencyArray.add(dependency);
      });
      result.set("components", componentArray);
      result.set("dependencies", dependencyArray);
      result.put("serialNumber", "urn:uuid:" + deterministicUuid(documents));
      BoundedByteArrayOutputStream output =
          new BoundedByteArrayOutputStream(maxOutputBytes);
      mapper.writeValue(output, result);
      return output.toByteArray();
    } catch (OutputLimitExceededException e) {
      throw new ScannerRequestException(
          "SCANNER_OUTPUT_TOO_LARGE",
          "Merged OCI inventory exceeded the configured output limit",
          413,
          false,
          e);
    } catch (IOException e) {
      throw invalid("OCI_SBOM_MERGE_FAILED", "Unable to aggregate OCI platform SBOMs", e);
    }
  }

  public EngineVersion engineVersion(byte[] json, String fallbackName) {
    try {
      JsonNode root = mapper.readTree(json);
      String version = firstText(root, "version", "Version", "applicationVersion");
      return new EngineVersion(
          fallbackName,
          version == null ? "unknown" : bounded(version, MAX_ENGINE_VERSION));
    } catch (IOException e) {
      throw invalid("SCANNER_VERSION_INVALID", "Scanner version output was invalid", e);
    }
  }

  public DatabaseProvenance database(byte[] json) {
    try {
      JsonNode root = mapper.readTree(json);
      String revision = firstText(
          root, "checksum", "digest", "revision", "built", "schemaVersion");
      if (revision == null || revision.isBlank()) {
        revision = sha256(json);
      }
      Instant updatedAt = firstInstant(
          root, "built", "updatedAt", "updated", "createdAt", "lastUpdate");
      if (updatedAt == null) updatedAt = Instant.EPOCH;
      return new DatabaseProvenance(
          bounded(revision, MAX_DATABASE_REVISION), updatedAt);
    } catch (IOException e) {
      throw invalid("GRYPE_DATABASE_STATUS_INVALID", "Grype database status was invalid", e);
    }
  }

  private Component component(JsonNode node) {
    String ref = componentRef(node);
    String name = bounded(text(node, "name"), MAX_COMPONENT_NAME);
    if (name == null || name.isBlank()) name = "unknown";
    List<String> locations = new ArrayList<>();
    JsonNode occurrences = node.path("evidence").path("occurrences");
    if (occurrences.isArray()) {
      for (JsonNode occurrence : occurrences) addBounded(locations, text(occurrence, "location"));
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    for (JsonNode property : node.path("properties")) {
      String key = bounded(text(property, "name"));
      String value = bounded(text(property, "value"));
      if (key != null && value != null && properties.size() < 128) {
        properties.put(key, value);
        if (key.toLowerCase(java.util.Locale.ROOT).contains("location")) {
          addBounded(locations, value);
        }
      }
    }
    List<String> licenses = new ArrayList<>();
    for (JsonNode licenseChoice : node.path("licenses")) {
      JsonNode license = licenseChoice.path("license");
      String value = firstText(license, "id", "name");
      if (value == null) value = text(licenseChoice, "expression");
      addBounded(licenses, value);
    }
    return new Component(
        ref,
        bounded(text(node, "purl"), MAX_PACKAGE_URL),
        bounded(text(node, "type"), MAX_COMPONENT_TYPE),
        bounded(text(node, "group"), MAX_COMPONENT_NAMESPACE),
        name,
        bounded(text(node, "version"), MAX_COMPONENT_VERSION),
        directness(node),
        locations,
        licenses,
        properties);
  }

  private Finding finding(JsonNode match) {
    JsonNode vulnerability = match.path("vulnerability");
    JsonNode artifact = match.path("artifact");
    String advisory = bounded(text(vulnerability, "id"), MAX_ADVISORY_ID);
    String purl = bounded(text(artifact, "purl"), MAX_PACKAGE_URL);
    String packageName = bounded(text(artifact, "name"), MAX_PACKAGE_NAME);
    if (packageName == null || packageName.isBlank()) packageName = "unknown";
    String installedVersion =
        bounded(text(artifact, "version"), MAX_INSTALLED_VERSION);
    List<String> aliases = new ArrayList<>();
    for (JsonNode related : match.path("relatedVulnerabilities")) {
      addBounded(aliases, text(related, "id"));
    }
    List<String> fixed = new ArrayList<>();
    for (JsonNode version : vulnerability.path("fix").path("versions")) {
      if (version.isTextual()) addBounded(fixed, version.asText());
    }
    List<String> locations = new ArrayList<>();
    for (JsonNode location : artifact.path("locations")) {
      addBounded(locations, firstText(location, "path", "realPath"));
    }
    Cvss cvss = bestCvss(vulnerability.path("cvss"));
    String findingKey = sha256(String.join("\0",
        value(advisory), value(purl), value(packageName), value(installedVersion)));
    List<String> urls = new ArrayList<>();
    for (JsonNode url : vulnerability.path("urls")) {
      if (url.isTextual()) addBounded(urls, url.asText());
    }
    return new Finding(
        findingKey,
        advisory == null ? "UNKNOWN" : advisory,
        aliases,
        bounded(
            firstText(vulnerability, "dataSource", "namespace"),
            MAX_DATA_SOURCE),
        purl,
        packageName,
        installedVersion,
        fixed,
        Severity.normalize(text(vulnerability, "severity")),
        "grype",
        bounded(cvss.vector(), MAX_CVSS_VECTOR),
        cvss.score(),
        bounded(
            firstText(vulnerability, "description", "id"),
            MAX_TITLE),
        boundedUtf8(
            text(vulnerability, "description"),
            MAX_DESCRIPTION_UTF8_BYTES),
        urls.isEmpty()
            ? null : bounded(urls.getFirst(), MAX_PACKAGE_URL),
        locations,
        bounded(
            text(vulnerability.path("fix"), "state"),
            MAX_SOURCE_STATUS));
  }

  private static Cvss bestCvss(JsonNode values) {
    List<Cvss> parsed = new ArrayList<>();
    if (values.isArray()) {
      for (JsonNode value : values) {
        Double score = value.path("metrics").path("baseScore").isNumber()
            ? value.path("metrics").path("baseScore").doubleValue() : null;
        String vector = bounded(text(value, "vector"));
        parsed.add(new Cvss(vector, score));
      }
    }
    return parsed.stream()
        .max(Comparator.comparing(value -> value.score() == null ? -1 : value.score()))
        .orElse(new Cvss(null, null));
  }

  private static String directness(JsonNode component) {
    String scope = text(component, "scope");
    return "required".equalsIgnoreCase(scope) ? "DIRECT_OR_REQUIRED" : "UNKNOWN";
  }

  private static String componentRef(JsonNode node) {
    String ref = bounded(text(node, "bom-ref"), MAX_COMPONENT_REF);
    if (ref != null && !ref.isBlank()) return ref;
    return "urn:kkrepo:component:" + sha256(String.join("\0",
        value(text(node, "purl")),
        value(text(node, "group")),
        value(text(node, "name")),
        value(text(node, "version"))));
  }

  private static String deterministicUuid(List<PlatformSbom> documents) {
    String hash = sha256(documents.stream()
        .map(value -> value.platform() + ":" + sha256(value.document()))
        .sorted()
        .reduce("", (left, right) -> left + "\0" + right));
    return hash.substring(0, 8) + "-" + hash.substring(8, 12) + "-"
        + hash.substring(12, 16) + "-" + hash.substring(16, 20) + "-"
        + hash.substring(20, 32);
  }

  private static String firstText(JsonNode root, String... names) {
    for (String name : names) {
      JsonNode found = find(root, name);
      if (found != null && found.isValueNode() && !found.asText().isBlank()) return found.asText();
    }
    return null;
  }

  private static Instant firstInstant(JsonNode root, String... names) {
    for (String name : names) {
      String value = firstText(root, name);
      if (value == null) continue;
      try {
        return Instant.parse(value);
      } catch (DateTimeParseException ignored) {
        // Try the next supported provenance field.
      }
    }
    return null;
  }

  private static JsonNode find(JsonNode node, String name) {
    if (node == null) return null;
    JsonNode direct = node.get(name);
    if (direct != null) return direct;
    if (node.isContainerNode()) {
      var children = node.elements();
      while (children.hasNext()) {
        JsonNode found = find(children.next(), name);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node == null ? null : node.get(name);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static void addBounded(List<String> values, String value) {
    value = bounded(value);
    if (value != null && !value.isBlank() && values.size() < 256 && !values.contains(value)) {
      values.add(value);
    }
  }

  private static String bounded(String value) {
    return bounded(value, MAX_JSON_FIELD);
  }

  private static String bounded(String value, int maxCodePoints) {
    if (value == null) return null;
    String sanitized = value.replace("\0", "");
    int codePoints = sanitized.codePointCount(0, sanitized.length());
    if (codePoints <= maxCodePoints) return sanitized;
    return sanitized.substring(0, sanitized.offsetByCodePoints(0, maxCodePoints));
  }

  private static String boundedUtf8(String value, int maxBytes) {
    if (value == null) return null;
    String sanitized = value.replace("\0", "");
    int bytes = 0;
    int end = 0;
    while (end < sanitized.length()) {
      int codePoint = sanitized.codePointAt(end);
      int codePointBytes = codePoint <= 0x7f
          ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
      if (bytes + codePointBytes > maxBytes) break;
      bytes += codePointBytes;
      end += Character.charCount(codePoint);
    }
    return end == sanitized.length() ? sanitized : sanitized.substring(0, end);
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }

  static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static ScannerRequestException invalid(String code, String message) {
    return new ScannerRequestException(code, message, 422, false);
  }

  private static ScannerRequestException invalid(String code, String message, Throwable cause) {
    return new ScannerRequestException(code, message, 422, false, cause);
  }

  private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private final int limit;

    private BoundedByteArrayOutputStream(long maxOutputBytes) {
      limit = (int) Math.min(Integer.MAX_VALUE - 1L, Math.max(1L, maxOutputBytes));
    }

    @Override
    public synchronized void write(int value) {
      ensureRemaining(1);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] values, int offset, int length) {
      java.util.Objects.checkFromIndexSize(offset, length, values.length);
      ensureRemaining(length);
      super.write(values, offset, length);
    }

    private void ensureRemaining(int length) {
      if (length > limit - count) {
        throw new OutputLimitExceededException();
      }
    }
  }

  private static final class OutputLimitExceededException extends RuntimeException {}

  public record EngineVersion(String name, String version) {}

  public record DatabaseProvenance(String revision, Instant updatedAt) {}

  public record PlatformSbom(String platform, byte[] document) {
    public PlatformSbom {
      document = document == null ? new byte[0] : document.clone();
    }
  }

  private record Cvss(String vector, Double score) {}
}
