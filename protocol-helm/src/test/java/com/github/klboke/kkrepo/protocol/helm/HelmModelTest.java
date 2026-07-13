package com.github.klboke.kkrepo.protocol.helm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.ProtocolCapability;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelmModelTest {
  @Test
  void describesSupportedRepositoryModes() {
    HelmRepositoryProtocol protocol = new HelmRepositoryProtocol();

    assertEquals(RepositoryFormat.HELM, protocol.format());
    assertEquals(
        new ProtocolCapability(true, true, true, false, true),
        protocol.capability());
  }

  @Test
  void classifiesSupportedAssetPaths() {
    assertEquals(HelmAssetKind.INDEX, HelmAssetKind.fromPath("INDEX.YAML"));
    assertEquals(HelmAssetKind.PACKAGE, HelmAssetKind.fromPath("charts/demo-1.0.0.TGZ"));
    assertEquals(HelmAssetKind.PROVENANCE, HelmAssetKind.fromPath("demo-1.0.0.tgz.prov"));

    assertEquals("index.yaml", HelmAssetKind.INDEX.fixedPath());
    assertEquals(".yaml", HelmAssetKind.INDEX.extension());
    assertEquals("text/x-yaml", HelmAssetKind.INDEX.contentType());
    assertTrue(HelmAssetKind.INDEX.metadata());
    assertFalse(HelmAssetKind.PACKAGE.metadata());
    assertEquals("application/gzip", HelmAssetKind.PACKAGE.contentType());
    assertEquals("application/octet-stream", HelmAssetKind.PROVENANCE.contentType());
  }

  @Test
  void rejectsUnsupportedAssetPaths() {
    assertThrows(IllegalArgumentException.class, () -> HelmAssetKind.fromPath(null));
    assertThrows(IllegalArgumentException.class, () -> HelmAssetKind.fromPath("README.md"));
  }

  @Test
  void convertsChartYamlValuesAndDropsInvalidCollections() {
    HelmChartMetadata metadata = HelmChartMetadata.fromYamlMap(Map.of(
        "apiVersion", "v2",
        "name", "demo",
        "version", 123,
        "description", "",
        "sources", List.of("https://example.test/source", "", 42),
        "maintainers", List.of(
            Map.of("name", "ops", "email", "ops@example.test"),
            "not-a-map",
            Map.of())));

    assertEquals("v2", metadata.apiVersion());
    assertEquals("demo", metadata.name());
    assertEquals("123", metadata.version());
    assertEquals(List.of("https://example.test/source", "42"), metadata.sources());
    assertEquals(
        List.of(Map.of("name", "ops", "email", "ops@example.test")),
        metadata.maintainers());
    assertEquals(Map.of(
        "apiVersion", "v2",
        "name", "demo",
        "version", "123",
        "sources", List.of("https://example.test/source", "42"),
        "maintainers", List.of(Map.of("name", "ops", "email", "ops@example.test"))),
        metadata.attributes());
  }

  @Test
  void handlesNullMetadataAndRequiresCoordinates() {
    HelmChartMetadata empty = HelmChartMetadata.fromYamlMap(null);
    assertEquals(Map.of(), empty.raw());
    assertEquals(List.of(), empty.sources());
    assertEquals(List.of(), empty.maintainers());
    assertThrows(IllegalArgumentException.class, empty::requireNameAndVersion);

    HelmChartMetadata missingVersion = HelmChartMetadata.fromYamlMap(Map.of("name", "demo"));
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class, missingVersion::requireNameAndVersion);
    assertEquals("Helm chart metadata is missing version", error.getMessage());

    HelmChartMetadata valid = HelmChartMetadata.fromYamlMap(
        Map.of("name", "demo", "version", "1.2.3"));
    valid.requireNameAndVersion();
  }
}
