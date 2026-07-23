package com.github.klboke.kkrepo.server.ansible;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

final class AnsibleCollectionTestArchive {
  private static final ObjectMapper JSON = new ObjectMapper();

  private AnsibleCollectionTestArchive() {
  }

  static byte[] valid(String namespace, String name, String version) throws Exception {
    return valid(namespace, name, version, Map.of("acme.base", ">=1.0.0"), ">=2.15");
  }

  static byte[] valid(
      String namespace,
      String name,
      String version,
      Map<String, String> dependencies,
      String requiresAnsible) throws Exception {
    LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    files.put("README.md", ("# " + namespace + "." + name + "\n").getBytes(StandardCharsets.UTF_8));
    files.put("meta/runtime.yml",
        ("requires_ansible: '" + requiresAnsible + "'\n").getBytes(StandardCharsets.UTF_8));
    return archive(namespace, name, version, dependencies, files, false, false);
  }

  static byte[] checksumMismatch() throws Exception {
    return archive("acme", "tools", "1.0.0", Map.of(), defaultFiles(), true, false);
  }

  static byte[] unsafePath() throws Exception {
    return archive("acme", "tools", "1.0.0", Map.of(), defaultFiles(), false, true);
  }

  static byte[] withSymbolicLink() throws Exception {
    return symbolicLinkArchive("../COPYING");
  }

  static byte[] escapingSymbolicLink() throws Exception {
    return symbolicLinkArchive("../../outside");
  }

  private static LinkedHashMap<String, byte[]> defaultFiles() {
    LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
    files.put("README.md", "# tools\n".getBytes(StandardCharsets.UTF_8));
    return files;
  }

  private static byte[] archive(
      String namespace,
      String name,
      String version,
      Map<String, String> dependencies,
      LinkedHashMap<String, byte[]> files,
      boolean corruptChecksum,
      boolean unsafePath) throws Exception {
    List<Map<String, Object>> inventory = new ArrayList<>();
    inventory.add(Map.of("name", ".", "ftype", "dir"));
    if (files.keySet().stream().anyMatch(path -> path.startsWith("meta/"))) {
      inventory.add(Map.of("name", "meta", "ftype", "dir"));
    }
    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      String checksum = sha256(entry.getValue());
      if (corruptChecksum && "README.md".equals(entry.getKey())) {
        checksum = "0".repeat(64);
      }
      inventory.add(Map.of(
          "name", entry.getKey(),
          "ftype", "file",
          "chksum_type", "sha256",
          "chksum_sha256", checksum));
    }
    byte[] filesJson = JSON.writeValueAsBytes(Map.of("files", inventory, "format", 1));
    LinkedHashMap<String, Object> collectionInfo = new LinkedHashMap<>();
    collectionInfo.put("namespace", namespace);
    collectionInfo.put("name", name);
    collectionInfo.put("version", version);
    collectionInfo.put("authors", List.of("kkRepo Test"));
    collectionInfo.put("description", "Ansible collection fixture");
    collectionInfo.put("license", List.of("Apache-2.0"));
    collectionInfo.put("tags", List.of("test"));
    collectionInfo.put("dependencies", dependencies);
    byte[] manifestJson = JSON.writeValueAsBytes(Map.of(
        "collection_info", collectionInfo,
        "file_manifest_file", Map.of(
            "name", "FILES.json",
            "ftype", "file",
            "chksum_type", "sha256",
            "chksum_sha256", sha256(filesJson)),
        "format", 1));

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      writeFile(tar, "MANIFEST.json", manifestJson);
      writeFile(tar, "FILES.json", filesJson);
      if (files.keySet().stream().anyMatch(path -> path.startsWith("meta/"))) {
        TarArchiveEntry directory = new TarArchiveEntry("meta/");
        directory.setSize(0);
        directory.setMode(0755);
        tar.putArchiveEntry(directory);
        tar.closeArchiveEntry();
      }
      for (Map.Entry<String, byte[]> entry : files.entrySet()) {
        writeFile(tar, entry.getKey(), entry.getValue());
      }
      if (unsafePath) {
        writeFile(tar, "../outside", new byte[] {1});
      }
    }
    return output.toByteArray();
  }

  private static byte[] symbolicLinkArchive(String linkTarget) throws Exception {
    byte[] target = "license text\n".getBytes(StandardCharsets.UTF_8);
    String targetHash = sha256(target);
    List<Map<String, Object>> inventory = new ArrayList<>();
    inventory.add(Map.of("name", ".", "ftype", "dir"));
    inventory.add(Map.of("name", "LICENSES", "ftype", "dir"));
    inventory.add(Map.of(
        "name", "COPYING",
        "ftype", "file",
        "chksum_type", "sha256",
        "chksum_sha256", targetHash));
    inventory.add(Map.of(
        "name", "LICENSES/COPYING",
        "ftype", "file",
        "chksum_type", "sha256",
        "chksum_sha256", targetHash));
    byte[] filesJson = JSON.writeValueAsBytes(Map.of("files", inventory, "format", 1));
    byte[] manifestJson = JSON.writeValueAsBytes(Map.of(
        "collection_info", Map.of(
            "namespace", "acme",
            "name", "tools",
            "version", "1.0.0",
            "dependencies", Map.of()),
        "file_manifest_file", Map.of(
            "name", "FILES.json",
            "ftype", "file",
            "chksum_type", "sha256",
            "chksum_sha256", sha256(filesJson)),
        "format", 1));

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      writeFile(tar, "MANIFEST.json", manifestJson);
      writeFile(tar, "FILES.json", filesJson);
      TarArchiveEntry directory = new TarArchiveEntry("LICENSES/");
      directory.setMode(0755);
      tar.putArchiveEntry(directory);
      tar.closeArchiveEntry();
      writeFile(tar, "COPYING", target);
      TarArchiveEntry link = new TarArchiveEntry("LICENSES/COPYING", TarConstants.LF_SYMLINK);
      link.setLinkName(linkTarget);
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
    }
    return output.toByteArray();
  }

  private static void writeFile(TarArchiveOutputStream tar, String name, byte[] body) throws Exception {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(body.length);
    tar.putArchiveEntry(entry);
    tar.write(body);
    tar.closeArchiveEntry();
  }

  static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
