package com.github.klboke.kkrepo.compat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

record OhpmFixture(String name, String version, Path archive, byte[] har, byte[] hsp) {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static OhpmFixture create(Path directory, String name, String version, boolean hsp, boolean large)
      throws IOException {
    ObjectNode manifest = MAPPER.createObjectNode();
    manifest.put("name", name);
    manifest.put("version", version);
    manifest.put("description", "Generated OHPM compatibility fixture");
    manifest.put("license", "Apache-2.0");
    manifest.put("main", "Index.ets");
    manifest.putObject("author").put("name", "kkRepo");
    manifest.putObject("dependencies");
    if (hsp) {
      manifest.put("packageType", "InterfaceHar");
      manifest.put("types", "Index.d.ets");
    }
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("package/oh-package.json5", MAPPER.writeValueAsBytes(manifest));
    files.put("package/Index.ets", "export const value = 42;\n".getBytes(StandardCharsets.UTF_8));
    if (hsp) files.put("package/Index.d.ets", "export declare const value: number;\n".getBytes(StandardCharsets.UTF_8));
    if (large) {
      byte[] content = new byte[6 * 1024 * 1024];
      new Random(492).nextBytes(content);
      files.put("package/resources/payload.bin", content);
    }
    byte[] har = tar(files);
    byte[] hspBytes = hsp ? hspArchive() : null;
    Path archive = directory.resolve(hsp ? "fixture.tgz" : "fixture.har");
    Files.write(archive, hsp ? tar(Map.of("fixture.har", har, "fixture.hsp", hspBytes)) : har);
    return new OhpmFixture(name, version, archive, har, hspBytes);
  }

  private static byte[] tar(Map<String, byte[]> files) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      for (var file : files.entrySet()) {
        TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
        entry.setSize(file.getValue().length);
        entry.setModTime(0);
        archive.putArchiveEntry(entry);
        archive.write(file.getValue());
        archive.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] hspArchive() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
      ZipEntry entry = new ZipEntry("module.json");
      entry.setTime(0);
      archive.putNextEntry(entry);
      archive.write("{\"module\":{\"name\":\"fixture\",\"type\":\"shared\"}}".getBytes(StandardCharsets.UTF_8));
      archive.closeEntry();
    }
    return bytes.toByteArray();
  }
}
