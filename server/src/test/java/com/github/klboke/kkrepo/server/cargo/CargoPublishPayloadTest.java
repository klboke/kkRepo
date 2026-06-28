package com.github.klboke.kkrepo.server.cargo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

class CargoPublishPayloadTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void readsLengthPrefixedPublishBodyAndBuildsIndexEntry() throws Exception {
    Map<String, Object> metadata = publishMetadata("hello_world", "1.2.3");
    byte[] crate = crateArchive("hello_world", "1.2.3");

    Path crateFile;
    try (CargoPublishPayload payload = CargoPublishPayload.read(
        OBJECT_MAPPER,
        new ByteArrayInputStream(publishBody(metadata, crate)))) {
      crateFile = payload.crateFile();

      assertEquals("hello_world", payload.metadata().name());
      assertEquals("hello_world", payload.metadata().normalizedName());
      assertEquals("1.2.3", payload.metadata().versionKey());
      assertEquals(crate.length, payload.crateLength());
      assertTrue(Files.exists(crateFile));

      Map<String, Object> entry = payload.metadata().indexEntry("abc123", false);
      assertEquals("hello_world", entry.get("name"));
      assertEquals("1.2.3", entry.get("vers"));
      assertEquals("abc123", entry.get("cksum"));
      assertEquals(false, entry.get("yanked"));
      assertEquals(0, entry.get("v"));
      List<Map<String, Object>> deps = (List<Map<String, Object>>) entry.get("deps");
      assertEquals("serde", deps.get(0).get("name"));
      assertEquals("^1", deps.get(0).get("req"));
      assertEquals(List.of("derive"), deps.get(0).get("features"));
    }

    assertFalse(Files.exists(crateFile));
  }

  @Test
  @SuppressWarnings("unchecked")
  void buildsIndexEntryWithOptionalCargoMetadata() {
    Map<String, Object> metadata = publishMetadata("hello_world", "1.2.3");
    Map<String, Object> features2 = Map.of("namespaced", List.of("dep:serde"));
    metadata.put("features2", features2);
    metadata.put("links", "hello_world_native");
    metadata.put("rust_version", "1.70");

    CargoPublishPayload.CargoPackageMetadata cargoMetadata =
        CargoPublishPayload.CargoPackageMetadata.fromPublishJson(metadata);
    Map<String, Object> entry = cargoMetadata.indexEntry("abc123", false);

    assertEquals(2, entry.get("v"));
    assertEquals(features2, entry.get("features2"));
    assertEquals("hello_world_native", entry.get("links"));
    assertEquals("1.70", entry.get("rust_version"));
    List<Map<String, Object>> deps = (List<Map<String, Object>>) entry.get("deps");
    assertEquals("serde", deps.get(0).get("name"));
  }

  @Test
  void rejectsCrateArchiveWhoseManifestDoesNotMatchMetadata() throws Exception {
    Map<String, Object> metadata = publishMetadata("hello_world", "1.2.3");
    byte[] crate = crateArchive("other_name", "1.2.3");

    CargoExceptions.BadRequestException thrown = assertThrows(
        CargoExceptions.BadRequestException.class,
        () -> CargoPublishPayload.read(
            OBJECT_MAPPER,
            new ByteArrayInputStream(publishBody(metadata, crate))));

    assertEquals("Cargo.toml package identity does not match publish metadata", thrown.getMessage());
  }

  @Test
  void rejectsInvalidPackageMetadataAsBadRequest() {
    Map<String, Object> metadata = publishMetadata("hello_world", "latest");

    CargoExceptions.BadRequestException thrown = assertThrows(
        CargoExceptions.BadRequestException.class,
        () -> CargoPublishPayload.CargoPackageMetadata.fromPublishJson(metadata));

    assertEquals("Invalid Cargo package metadata", thrown.getMessage());
  }

  @Test
  @SuppressWarnings("unchecked")
  void crateInspectorBuildsPublishMetadataForUiUpload() throws Exception {
    byte[] crate = crateArchiveWithManifest("ui_demo", "0.1.0", """
        [package]
        name = "ui_demo"
        version = "0.1.0"
        description = "Uploaded from UI"
        repository = "https://example.test/ui_demo"
        keywords = ["cargo", "ui"]

        [dependencies]
        serde = { version = "1", features = ["derive"], optional = true, default-features = false }
        tokio = "1"

        [dev-dependencies]
        tempfile = "3"

        [target.'cfg(unix)'.dependencies]
        libc = "0.2"

        [features]
        default = ["serde"]
        """);
    Path archive = Files.createTempFile("kkrepo-cargo-test-", ".crate");
    Files.write(archive, crate);
    try {
      CargoPublishPayload.CargoPackageMetadata metadata =
          CargoPublishPayload.CargoPackageMetadata.fromManifest(CargoCrateInspector.inspect(archive));
      Map<String, Object> entry = metadata.indexEntry("abc123", false);

      assertEquals("ui_demo", entry.get("name"));
      assertEquals("0.1.0", entry.get("vers"));
      assertEquals("Uploaded from UI", metadata.description());
      assertEquals("https://example.test/ui_demo", metadata.publishJson().get("repository"));
      assertEquals(Map.of("default", List.of("serde")), entry.get("features"));
      List<Map<String, Object>> deps = (List<Map<String, Object>>) entry.get("deps");
      assertEquals(4, deps.size());
      assertEquals("serde", deps.get(0).get("name"));
      assertEquals(false, deps.get(0).get("default_features"));
      assertEquals(true, deps.get(0).get("optional"));
      assertEquals("dev", deps.get(2).get("kind"));
      assertEquals("cfg(unix)", deps.get(3).get("target"));
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void crateInspectorReadsPackageDependencyTablesForUiUpload() throws Exception {
    byte[] crate = crateArchiveWithManifest("table_demo", "0.1.0", """
        [package]
        name = "table_demo"
        version = "0.1.0"

        [dependencies.serde]
        version = "1"
        features = ["derive"]
        optional = true

        [dev-dependencies.tempfile]
        version = "3"

        [target.'cfg(windows)'.dependencies.windows-sys]
        version = "0.52"
        default-features = false
        """);
    Path archive = Files.createTempFile("kkrepo-cargo-test-", ".crate");
    Files.write(archive, crate);
    try {
      CargoPublishPayload.CargoPackageMetadata metadata =
          CargoPublishPayload.CargoPackageMetadata.fromManifest(CargoCrateInspector.inspect(archive));
      List<Map<String, Object>> deps = (List<Map<String, Object>>) metadata.indexEntry("abc123", false).get("deps");

      assertEquals(3, deps.size());
      assertEquals("serde", deps.get(0).get("name"));
      assertEquals(List.of("derive"), deps.get(0).get("features"));
      assertEquals(true, deps.get(0).get("optional"));
      assertEquals("dev", deps.get(1).get("kind"));
      assertEquals("tempfile", deps.get(1).get("name"));
      assertEquals("cfg(windows)", deps.get(2).get("target"));
      assertEquals(false, deps.get(2).get("default_features"));
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  @Test
  void crateInspectorRejectsOversizedManifest() throws Exception {
    byte[] crate = crateArchiveWithManifest("large_demo", "0.1.0",
        "a".repeat(CargoCrateInspector.MAX_MANIFEST_BYTES + 1));
    Path archive = Files.createTempFile("kkrepo-cargo-test-", ".crate");
    Files.write(archive, crate);
    try {
      assertThrows(CargoExceptions.BadRequestException.class, () -> CargoCrateInspector.inspect(archive));
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  private static Map<String, Object> publishMetadata(String name, String version) {
    Map<String, Object> dependency = new LinkedHashMap<>();
    dependency.put("name", "serde");
    dependency.put("version_req", "^1");
    dependency.put("features", List.of("derive"));
    dependency.put("optional", false);
    dependency.put("default_features", true);
    dependency.put("kind", "normal");

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("vers", version);
    metadata.put("deps", List.of(dependency));
    metadata.put("features", Map.of("default", List.of()));
    metadata.put("description", "Test crate");
    return metadata;
  }

  private static byte[] publishBody(Map<String, Object> metadata, byte[] crate) throws IOException {
    byte[] json = OBJECT_MAPPER.writeValueAsBytes(metadata);
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeU32Le(body, json.length);
    body.write(json);
    writeU32Le(body, crate.length);
    body.write(crate);
    return body.toByteArray();
  }

  private static byte[] crateArchive(String name, String version) throws IOException {
    String manifest = "[package]\n"
        + "name = \"" + name + "\"\n"
        + "version = \"" + version + "\"\n";
    return crateArchiveWithManifest(name, version, manifest);
  }

  private static byte[] crateArchiveWithManifest(String name, String version, String manifestText) throws IOException {
    String dir = name + "-" + version + "/";
    byte[] manifest = manifestText.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry entry = new TarArchiveEntry(dir + "Cargo.toml");
      entry.setSize(manifest.length);
      tar.putArchiveEntry(entry);
      tar.write(manifest);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static void writeU32Le(ByteArrayOutputStream out, int value) {
    out.write(value & 0xff);
    out.write((value >>> 8) & 0xff);
    out.write((value >>> 16) & 0xff);
    out.write((value >>> 24) & 0xff);
  }
}
