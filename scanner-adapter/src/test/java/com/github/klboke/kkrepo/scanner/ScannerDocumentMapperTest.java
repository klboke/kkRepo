package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.DatabaseProvenance;
import com.github.klboke.kkrepo.scanner.ScannerDocumentMapper.PlatformSbom;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
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
}
