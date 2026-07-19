package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NpmPackumentResponseWriterTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("demo");
  private static final String BASE = "https://packages.example/repository/npm";
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void fullProjectionMatchesCopyFilterAndRewriteWithoutMutatingSource() throws Exception {
    Map<String, Object> root = packument();
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 60);
    Map<String, Object> expected = analysis.filter(root, NOW);
    NpmMetadata.rewriteTarballUrls(expected, PACKAGE, BASE);

    byte[] bytes = NpmPackumentResponseWriter.write(
        mapper, root, analysis, NOW, NpmPackumentVariant.FULL, PACKAGE, BASE);
    Map<?, ?> response = mapper.readValue(bytes, Map.class);

    assertEquals(expected, response);
    assertFalse(((Map<?, ?>) response.get("time")).containsKey("1.1.0"));
    assertTrue(NpmMetadata.versions(root).containsKey("1.1.0"));
    assertTrue(((Map<?, ?>) root.get("time")).containsKey("1.1.0"));
    assertEquals(
        "https://registry.npmjs.org/demo/-/demo-1.0.0.tgz",
        tarball(NpmMetadata.versions(root).get("1.0.0")));
  }

  @Test
  void installProjectionMatchesExistingAbbreviatedSemanticsInOneWritePass() throws Exception {
    Map<String, Object> root = packument();
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(root, 60);
    Map<String, Object> expected = NpmMetadata.abbreviatePackageRoot(root);
    analysis.filterPreparedCopy(expected, NOW);
    NpmMetadata.rewriteTarballUrls(expected, PACKAGE, BASE);

    byte[] bytes = NpmPackumentResponseWriter.write(
        mapper, root, analysis, NOW, NpmPackumentVariant.INSTALL_V1, PACKAGE, BASE);

    assertEquals(expected, mapper.readValue(bytes, Map.class));
  }

  private static Map<String, Object> packument() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", "demo");
    root.put("readme", "large root readme");
    root.put("dist-tags", new LinkedHashMap<>(Map.of("latest", "1.1.0")));
    root.put("time", new LinkedHashMap<>(Map.of(
        "1.0.0", "2026-07-18T12:00:00Z",
        "1.1.0", "2026-07-19T11:30:01Z")));
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("1.0.0", version("1.0.0"));
    versions.put("1.1.0", version("1.1.0"));
    root.put("versions", versions);
    return root;
  }

  private static Map<String, Object> version(String version) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("name", "demo");
    document.put("version", version);
    document.put("readme", "large version readme");
    document.put("scripts", Map.of("install", "node install.js"));
    document.put("dependencies", Map.of("left-pad", "^1.3.0"));
    document.put("dist", new LinkedHashMap<>(Map.of(
        "tarball", "https://registry.npmjs.org/demo/-/demo-" + version + ".tgz",
        "shasum", "0".repeat(40))));
    return document;
  }

  @SuppressWarnings("unchecked")
  private static String tarball(Object rawVersion) {
    Map<String, Object> version = (Map<String, Object>) rawVersion;
    return ((Map<String, Object>) version.get("dist")).get("tarball").toString();
  }
}
