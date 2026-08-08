package com.github.klboke.kkrepo.server.conda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

final class CondaTestArchive {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CondaTestArchive() {
  }

  static byte[] legacy(
      String name, String version, String build, long buildNumber, String subdir)
      throws IOException {
    return legacy(index(name, version, build, buildNumber, subdir), List.of());
  }

  static byte[] legacy(byte[] index, List<TarItem> additionalEntries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip2)) {
      putFile(tar, "info/index.json", index);
      for (TarItem item : additionalEntries) put(tar, item);
      tar.finish();
    }
    return bytes.toByteArray();
  }

  static byte[] modern(
      String name, String version, String build, long buildNumber, String subdir)
      throws IOException {
    String identity = name + "-" + version + "-" + build;
    return modern(identity, identity, true, true,
        index(name, version, build, buildNumber, subdir));
  }

  static byte[] modern(
      String infoIdentity,
      String packageIdentity,
      boolean stored,
      boolean includePackage,
      byte[] index) throws IOException {
    byte[] info = zstdTar(List.of(TarItem.file("info/index.json", index)));
    byte[] payload = zstdTar(List.of(TarItem.file(
        "bin/demo", "payload".getBytes(StandardCharsets.UTF_8))));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      putZip(zip, "metadata.json",
          "{\"conda_pkg_format_version\":2}".getBytes(StandardCharsets.UTF_8), stored);
      putZip(zip, "info-" + infoIdentity + ".tar.zst", info, stored);
      if (includePackage) {
        putZip(zip, "pkg-" + packageIdentity + ".tar.zst", payload, stored);
      }
    }
    return bytes.toByteArray();
  }

  static byte[] index(
      String name, String version, String build, long buildNumber, String subdir)
      throws IOException {
    LinkedHashMap<String, Object> index = new LinkedHashMap<>();
    index.put("name", name);
    index.put("version", version);
    index.put("build", build);
    index.put("build_number", buildNumber);
    index.put("subdir", subdir);
    index.put("depends", List.of("python >=3.12"));
    index.put("license", "BSD-3-Clause");
    index.put("base_url", "https://should-not-leak.example.invalid/channel");
    index.put("download_url", "https://should-not-leak.example.invalid/package");
    return MAPPER.writeValueAsBytes(index);
  }

  private static byte[] zstdTar(List<TarItem> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZstdOutputStream zstd = new ZstdOutputStream(bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
      for (TarItem entry : entries) put(tar, entry);
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static void put(TarArchiveOutputStream tar, TarItem item) throws IOException {
    if (item.linkTarget() == null) {
      putFile(tar, item.name(), item.content());
      return;
    }
    TarArchiveEntry entry = new TarArchiveEntry(
        item.name(), item.symbolicLink() ? TarConstants.LF_SYMLINK : TarConstants.LF_LINK);
    entry.setLinkName(item.linkTarget());
    tar.putArchiveEntry(entry);
    tar.closeArchiveEntry();
  }

  private static void putFile(TarArchiveOutputStream tar, String name, byte[] content)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    tar.putArchiveEntry(entry);
    tar.write(content);
    tar.closeArchiveEntry();
  }

  private static void putZip(
      ZipOutputStream zip, String name, byte[] content, boolean stored) throws IOException {
    ZipEntry entry = new ZipEntry(name);
    if (stored) {
      CRC32 crc = new CRC32();
      crc.update(content);
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(content.length);
      entry.setCompressedSize(content.length);
      entry.setCrc(crc.getValue());
    }
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }

  record TarItem(
      String name, byte[] content, String linkTarget, boolean symbolicLink) {
    TarItem {
      content = content == null ? new byte[0] : content.clone();
    }

    static TarItem file(String name, byte[] content) {
      return new TarItem(name, content, null, false);
    }

    static TarItem symlink(String name, String target) {
      return new TarItem(name, new byte[0], target, true);
    }

    static TarItem hardlink(String name, String target) {
      return new TarItem(name, new byte[0], target, false);
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }
}
