package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import java.io.IOException;
import java.io.OutputStream;
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

  @Test
  void writesUnfilteredAndNonStandardVersionShapesWithoutPolicy() throws Exception {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", "demo");
    root.put("dist-tags", new LinkedHashMap<>(Map.of("latest", "1.0.0")));
    root.put("time", new LinkedHashMap<>(Map.of("1.0.0", "not-a-timestamp")));
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("1.0.0", "legacy-version-shape");
    versions.put("2.0.0", new LinkedHashMap<>(Map.of(
        "name", "demo",
        "version", "2.0.0",
        "dist", "legacy-dist-shape")));
    root.put("versions", versions);

    byte[] bytes = NpmPackumentResponseWriter.write(
        mapper, root, null, NOW, NpmPackumentVariant.FULL, PACKAGE, BASE);

    assertEquals(root, mapper.readValue(bytes, Map.class));
  }

  @Test
  void preservesUnrewritableTarballsAndReusesExistingInstallFlag() throws Exception {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", "demo");
    root.put("dist-tags", Map.of());
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("null-tarball", versionWithTarball(null));
    versions.put("empty-tarball", versionWithTarball(""));
    versions.put("blank-base", versionWithTarball(
        "https://registry.npmjs.org/demo/-/demo-blank-base.tgz"));
    Map<String, Object> flagged = versionWithTarball(
        "https://registry.npmjs.org/demo/-/demo-flagged.tgz");
    flagged.put("hasInstallScript", false);
    flagged.put("scripts", Map.of("install", "node install.js"));
    versions.put("flagged", flagged);
    root.put("versions", versions);

    Map<?, ?> response = mapper.readValue(NpmPackumentResponseWriter.write(
        mapper, root, null, NOW, NpmPackumentVariant.INSTALL_V1, PACKAGE, " "), Map.class);
    Map<?, ?> writtenVersions = (Map<?, ?>) response.get("versions");

    assertEquals(null, tarball(writtenVersions.get("null-tarball")));
    assertEquals("", tarball(writtenVersions.get("empty-tarball")));
    assertEquals(
        "https://registry.npmjs.org/demo/-/demo-blank-base.tgz",
        tarball(writtenVersions.get("blank-base")));
    assertEquals(false, ((Map<?, ?>) writtenVersions.get("flagged")).get("hasInstallScript"));
  }

  @Test
  void handlesNullBaseAndRemovesOneTrailingSlashWhenRewriting() throws Exception {
    Map<String, Object> root = packument();

    Map<?, ?> unchanged = mapper.readValue(NpmPackumentResponseWriter.write(
        mapper, root, null, NOW, NpmPackumentVariant.FULL, PACKAGE, null), Map.class);
    Map<?, ?> rewritten = mapper.readValue(NpmPackumentResponseWriter.write(
        mapper, root, null, NOW, NpmPackumentVariant.FULL, PACKAGE, BASE + "/"), Map.class);

    assertEquals(
        "https://registry.npmjs.org/demo/-/demo-1.0.0.tgz",
        tarball(((Map<?, ?>) unchanged.get("versions")).get("1.0.0")));
    assertEquals(
        BASE + "/demo/-/demo-1.0.0.tgz",
        tarball(((Map<?, ?>) rewritten.get("versions")).get("1.0.0")));
  }

  @Test
  void wrapsJsonGeneratorCreationFailure() throws Exception {
    ObjectMapper brokenMapper = mock(ObjectMapper.class);
    JsonFactory factory = mock(JsonFactory.class);
    when(brokenMapper.getFactory()).thenReturn(factory);
    when(factory.createGenerator(any(OutputStream.class)))
        .thenThrow(new IOException("generator unavailable"));

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> NpmPackumentResponseWriter.write(
            brokenMapper, packument(), null, NOW, NpmPackumentVariant.FULL, PACKAGE, BASE));

    assertEquals("Failed to serialize npm packument", error.getMessage());
    assertTrue(error.getCause() instanceof IOException);
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

  private static Map<String, Object> versionWithTarball(Object tarball) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("name", "demo");
    document.put("version", "1.0.0");
    Map<String, Object> dist = new LinkedHashMap<>();
    dist.put("tarball", tarball);
    dist.put("shasum", "0".repeat(40));
    document.put("dist", dist);
    return document;
  }

  @SuppressWarnings("unchecked")
  private static String tarball(Object rawVersion) {
    Map<String, Object> version = (Map<String, Object>) rawVersion;
    Object tarball = ((Map<String, Object>) version.get("dist")).get("tarball");
    return tarball == null ? null : tarball.toString();
  }
}
