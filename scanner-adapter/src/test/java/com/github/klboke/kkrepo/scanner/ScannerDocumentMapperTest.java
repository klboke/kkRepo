package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.DatabaseProvenance;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.PlatformSbom;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScannerContract;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScannerDocumentMapperTest {
  private final ScannerDocumentMapper mapper =
      new ScannerDocumentMapper(JsonMapper.builder().build());

  @Test
  void mapsCycloneDxToBoundedComponents() {
    byte[] sbom = """
        {
          "bomFormat":"CycloneDX","specVersion":"1.6",
          "components":[{
            "bom-ref":"pkg:maven/org.example/demo@1.0",
            "type":"library","group":"org.example","name":"demo","version":"1.0",
            "purl":"pkg:maven/org.example/demo@1.0",
            "licenses":[{"license":{"id":"Apache-2.0"}}],
            "evidence":{"occurrences":[{"location":"demo.jar"}]}
          }],
          "dependencies":[{"ref":"root","dependsOn":["pkg:maven/org.example/demo@1.0"]}]
        }
        """.getBytes(StandardCharsets.UTF_8);

    var response = mapper.catalog(sbom, "a".repeat(64), "1.49.0", "cap", Map.of());

    assertThat(response.componentCount()).isEqualTo(1);
    assertThat(response.dependencyCount()).isEqualTo(1);
    assertThat(response.components().getFirst().packageUrl())
        .isEqualTo("pkg:maven/org.example/demo@1.0");
    assertThat(response.components().getFirst().licenses()).containsExactly("Apache-2.0");
  }

  @Test
  void mapsGrypeFindingAndNormalizesSeverity() {
    byte[] report = """
        {"matches":[{
          "vulnerability":{
            "id":"CVE-2026-1234","severity":"Important","namespace":"nvd",
            "dataSource":"https://example.invalid/feed",
            "description":"bounded description",
            "urls":["https://example.invalid/CVE-2026-1234"],
            "fix":{"state":"fixed","versions":["1.1"]},
            "cvss":[{"vector":"CVSS:3.1/test","metrics":{"baseScore":8.2}}]
          },
          "artifact":{
            "name":"demo","version":"1.0","purl":"pkg:maven/org.example/demo@1.0",
            "locations":[{"path":"demo.jar"}]
          },
          "relatedVulnerabilities":[{"id":"GHSA-test"}]
        }]}
        """.getBytes(StandardCharsets.UTF_8);

    var response = mapper.match(
        report, "0.116.0", new DatabaseProvenance("db-1", Instant.EPOCH), "cap");

    assertThat(response.findings()).hasSize(1);
    assertThat(response.findings().getFirst().severity()).isEqualTo(Severity.HIGH);
    assertThat(response.findings().getFirst().fixedVersions()).containsExactly("1.1");
    assertThat(response.findings().getFirst().findingKey()).hasSize(64);
  }

  @Test
  void truncatesLargeEngineProjectionsBeforeSerializingTheAdapterResponse() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    var bom = json.createObjectNode();
    bom.put("bomFormat", "CycloneDX");
    var components = bom.putArray("components");
    for (int index = 0;
        index < ScannerContract.MAX_COMPONENT_PROJECTION_COUNT + 1;
        index++) {
      components.addObject()
          .put("bom-ref", "component-" + index)
          .put("name", "component-" + index);
    }
    var catalog =
        mapper.catalog(json.writeValueAsBytes(bom), "a".repeat(64), "1", "cap", Map.of());
    assertThat(catalog.components())
        .hasSize(ScannerContract.MAX_COMPONENT_PROJECTION_COUNT);
    assertThat(catalog.completeness()).isEqualTo(ScanCompleteness.PARTIAL);

    var report = json.createObjectNode();
    var matches = report.putArray("matches");
    for (int index = 0;
        index < ScannerContract.MAX_FINDING_PROJECTION_COUNT + 1;
        index++) {
      var match = matches.addObject();
      match.putObject("vulnerability").put("id", "CVE-" + index);
      match.putObject("artifact").put("name", "component-" + index);
    }
    var findings = mapper.match(
        json.writeValueAsBytes(report),
        "1",
        new DatabaseProvenance("db", Instant.EPOCH),
        "cap");
    assertThat(findings.findings())
        .hasSize(ScannerContract.MAX_FINDING_PROJECTION_COUNT);
    assertThat(findings.completeness()).isEqualTo(ScanCompleteness.PARTIAL);
  }

  @Test
  void boundsScannerProjectionFieldsToPortablePersistenceColumns() throws Exception {
    JsonMapper json = JsonMapper.builder().build();
    var bom = json.createObjectNode();
    bom.put("bomFormat", "CycloneDX");
    bom.put("specVersion", "s".repeat(100));
    var component = bom.putArray("components").addObject();
    component.put("bom-ref", "r".repeat(2_000));
    component.put("purl", "p".repeat(3_000));
    component.put("type", "t".repeat(100));
    component.put("group", "g".repeat(700));
    component.put("name", "😀".repeat(600));
    component.put("version", "v".repeat(700));

    var catalog =
        mapper.catalog(json.writeValueAsBytes(bom), "a".repeat(64), "1", "cap", Map.of());
    var storedComponent = catalog.components().getFirst();
    assertThat(catalog.specVersion()).hasSize(32);
    assertThat(storedComponent.componentRef()).hasSize(1_024);
    assertThat(storedComponent.packageUrl()).hasSize(2_048);
    assertThat(storedComponent.type()).hasSize(64);
    assertThat(storedComponent.namespace()).hasSize(512);
    assertThat(storedComponent.name().codePointCount(0, storedComponent.name().length()))
        .isEqualTo(512);
    assertThat(storedComponent.version()).hasSize(512);

    var report = json.createObjectNode();
    var match = report.putArray("matches").addObject();
    var vulnerability = match.putObject("vulnerability");
    vulnerability.put("id", "a".repeat(400));
    vulnerability.put("severity", "high");
    vulnerability.put("dataSource", "d".repeat(3_000));
    vulnerability.put("description", "😀".repeat(20_000));
    vulnerability.putArray("urls").add("u".repeat(3_000));
    vulnerability.putObject("fix").put("state", "f".repeat(100));
    vulnerability.putArray("cvss").addObject().put("vector", "c".repeat(400));
    var artifact = match.putObject("artifact");
    artifact.put("name", "n".repeat(700));
    artifact.put("version", "i".repeat(700));
    artifact.put("purl", "p".repeat(3_000));

    var finding = mapper.match(
            json.writeValueAsBytes(report),
            "1",
            new DatabaseProvenance("db", Instant.EPOCH),
            "cap")
        .findings()
        .getFirst();
    assertThat(finding.advisoryId()).hasSize(255);
    assertThat(finding.dataSource()).hasSize(2_048);
    assertThat(finding.packageUrl()).hasSize(2_048);
    assertThat(finding.packageName()).hasSize(512);
    assertThat(finding.installedVersion()).hasSize(512);
    assertThat(finding.cvssVector()).hasSize(255);
    assertThat(finding.title().codePointCount(0, finding.title().length())).isEqualTo(1_024);
    assertThat(finding.description().getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(65_535);
    assertThat(finding.description()).doesNotEndWith("\uFFFD");
    assertThat(finding.primaryUrl()).hasSize(2_048);
    assertThat(finding.sourceStatus()).hasSize(64);

    assertThat(mapper.engineVersion(
            ("{\"version\":\"" + "v".repeat(200) + "\"}")
                .getBytes(StandardCharsets.UTF_8),
            "grype").version())
        .hasSize(128);
    assertThat(mapper.database(
            ("{\"revision\":\"" + "d".repeat(400) + "\"}")
                .getBytes(StandardCharsets.UTF_8)).revision())
        .hasSize(255);
  }

  @Test
  void mergesPlatformSbomsWithoutDuplicatingComponents() throws Exception {
    byte[] amd64 = """
        {"bomFormat":"CycloneDX","specVersion":"1.6","serialNumber":"urn:uuid:old",
         "components":[{"bom-ref":"same","type":"library","name":"demo","version":"1"}],
         "dependencies":[]}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] arm64 = amd64.clone();

    byte[] merged = mapper.mergeCycloneDx(List.of(
        new PlatformSbom("linux/amd64", amd64),
        new PlatformSbom("linux/arm64", arm64)));
    var root = JsonMapper.builder().build().readTree(merged);

    assertThat(root.path("components")).hasSize(1);
    assertThat(root.path("components").get(0).path("properties").get(0).path("value").asText())
        .contains("linux/amd64", "linux/arm64");
  }

  @Test
  void rejectsMergedPlatformSbomDuringBoundedSerialization() {
    byte[] sbom = """
        {"bomFormat":"CycloneDX","components":[
          {"bom-ref":"same","type":"library","name":"demo","version":"1"}
        ],"dependencies":[]}
        """.getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> mapper.mergeCycloneDx(
            List.of(new PlatformSbom("linux/amd64", sbom)),
            32))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("SCANNER_OUTPUT_TOO_LARGE");
  }

  @Test
  void rejectsExcessiveJsonNestingAndFieldLength() {
    byte[] deeplyNested = ("{\"bomFormat\":\"CycloneDX\",\"components\":"
        + "[".repeat(300) + "]".repeat(300) + "}")
        .getBytes(StandardCharsets.UTF_8);
    byte[] oversizedField = ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\""
        + "x".repeat(70 * 1024) + "\",\"components\":[]}")
        .getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(
            () -> mapper.catalog(deeplyNested, "a".repeat(64), "1", "cap", Map.of()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("SYFT_SBOM_INVALID");
    assertThatThrownBy(
            () -> mapper.catalog(oversizedField, "a".repeat(64), "1", "cap", Map.of()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("SYFT_SBOM_INVALID");
  }

  @Test
  void mapsFallbackComponentFieldsPropertiesLicensesAndDuplicateReferences() {
    byte[] sbom = """
        {
          "bomFormat":"CycloneDX","specVersion":"1.6",
          "components":[
            {
              "scope":"required","group":"org.example","name":"","version":"1",
              "properties":[
                {"name":"source.location","value":"lib/demo.jar"},
                {"name":"custom","value":"value"}
              ],
              "licenses":[{"expression":"MIT"}]
            },
            {
              "group":"org.example","name":"","version":"1"
            }
          ],
          "dependencies":[
            {"ref":"root","dependsOn":["child","child",7]},
            {"dependsOn":["ignored"]}
          ]
        }
        """.getBytes(StandardCharsets.UTF_8);

    var response = mapper.catalog(sbom, "a".repeat(64), "1", "cap", null);

    assertThat(response.completeness())
        .isEqualTo(com.github.klboke.kkrepo.security.scan.ScanEnums.ScanCompleteness.PARTIAL);
    assertThat(response.components()).hasSize(1);
    assertThat(response.components().getFirst().name()).isEqualTo("unknown");
    assertThat(response.components().getFirst().directness()).isEqualTo("DIRECT_OR_REQUIRED");
    assertThat(response.components().getFirst().locations()).contains("lib/demo.jar");
    assertThat(response.components().getFirst().licenses()).containsExactly("MIT");
    assertThat(response.dependencyCount()).isEqualTo(4);
  }

  @Test
  void mapsSparseGrypeReportsAndRejectsMalformedScannerDocuments() {
    byte[] sparse = """
        {"matches":[{
          "vulnerability":{"severity":"invented","fix":{"versions":[1]},"cvss":[]},
          "artifact":{"name":"demo","locations":[{"realPath":"/demo"}]},
          "relatedVulnerabilities":[{"id":""}]
        }]}
        """.getBytes(StandardCharsets.UTF_8);

    var response = mapper.match(
        sparse, "1", new DatabaseProvenance("db", Instant.EPOCH), "cap");

    assertThat(response.findings().getFirst().advisoryId()).isEqualTo("UNKNOWN");
    assertThat(response.findings().getFirst().severity()).isEqualTo(Severity.UNKNOWN);
    assertThat(response.findings().getFirst().locations()).containsExactly("/demo");
    assertThat(response.findings().getFirst().cvssScore()).isNull();

    assertThatThrownBy(() -> mapper.catalog(
            "{}".getBytes(StandardCharsets.UTF_8), "a".repeat(64), "1", "cap", null))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("SYFT_SBOM_INVALID");
    assertThatThrownBy(() -> mapper.match(
            "{".getBytes(StandardCharsets.UTF_8),
            "1",
            new DatabaseProvenance("db", Instant.EPOCH),
            "cap"))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("GRYPE_REPORT_INVALID");
  }

  @Test
  void mergesDependenciesAndRejectsMissingOrMalformedPlatformSboms() throws Exception {
    byte[] first = """
        {"bomFormat":"CycloneDX","components":[
          {"type":"library","group":"acme","name":"demo","version":"1"},
          "ignored"
        ],"dependencies":[{"ref":"root","dependsOn":["a"]}]}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] second = """
        {"bomFormat":"CycloneDX","components":[
          {"type":"library","group":"acme","name":"demo","version":"1"}
        ],"dependencies":[{"ref":"root","dependsOn":["b"]}]}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] merged = mapper.mergeCycloneDx(List.of(
        new PlatformSbom("linux/amd64", first),
        new PlatformSbom("linux/arm64", second)));
    var root = JsonMapper.builder().build().readTree(merged);
    JsonNode dependsOn = root.path("dependencies").get(0).path("dependsOn");
    assertThat(dependsOn).hasSize(2);
    assertThat(dependsOn.get(0).asText()).isEqualTo("a");
    assertThat(dependsOn.get(1).asText()).isEqualTo("b");

    assertThatThrownBy(() -> mapper.mergeCycloneDx(List.of()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("OCI_NO_PLATFORM_SCANNED");
    assertThatThrownBy(() -> mapper.mergeCycloneDx(List.of(
            new PlatformSbom("linux/amd64", "[]".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("OCI_SBOM_INVALID");
    assertThatThrownBy(() -> mapper.mergeCycloneDx(List.of(
            new PlatformSbom("linux/amd64", "{".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("OCI_SBOM_MERGE_FAILED");
  }

  @Test
  void readsVersionAndDatabaseProvenanceAcrossSupportedShapes() {
    assertThat(mapper.engineVersion(
            "{\"applicationVersion\":\"2.0\"}".getBytes(StandardCharsets.UTF_8), "syft"))
        .isEqualTo(new ScannerDocumentMapper.EngineVersion("syft", "2.0"));
    assertThat(mapper.engineVersion("{}".getBytes(StandardCharsets.UTF_8), "grype").version())
        .isEqualTo("unknown");
    assertThatThrownBy(() -> mapper.engineVersion(
            "{".getBytes(StandardCharsets.UTF_8), "grype"))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("SCANNER_VERSION_INVALID");

    var explicit = mapper.database(
        "{\"nested\":{\"checksum\":\"abc\",\"updatedAt\":\"2026-07-27T00:00:00Z\"}}"
            .getBytes(StandardCharsets.UTF_8));
    assertThat(explicit.revision()).isEqualTo("abc");
    assertThat(explicit.updatedAt()).isEqualTo(Instant.parse("2026-07-27T00:00:00Z"));
    var fallback = mapper.database(
        "{\"updatedAt\":\"not-an-instant\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(fallback.revision()).hasSize(64);
    assertThat(fallback.updatedAt()).isEqualTo(Instant.EPOCH);
    assertThatThrownBy(() -> mapper.database("{".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("GRYPE_DATABASE_STATUS_INVALID");

    byte[] platformDocument = {1, 2};
    PlatformSbom platform = new PlatformSbom("linux/amd64", platformDocument);
    platformDocument[0] = 9;
    assertThat(platform.document()).containsExactly(1, 2);
    assertThat(new PlatformSbom("linux/arm64", null).document()).isEmpty();
  }
}
