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
      List<Map<String, Object>> deps = (List<Map<String, Object>>) entry.get("deps");
      assertEquals("serde", deps.get(0).get("name"));
      assertEquals("^1", deps.get(0).get("req"));
      assertEquals(List.of("derive"), deps.get(0).get("features"));
    }

    assertFalse(Files.exists(crateFile));
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
    String dir = name + "-" + version + "/";
    byte[] manifest = ("[package]\n"
        + "name = \"" + name + "\"\n"
        + "version = \"" + version + "\"\n").getBytes(StandardCharsets.UTF_8);
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
