package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;

class TerraformArchiveInspectorTest {
  private final TerraformArchiveInspector inspector = new TerraformArchiveInspector();

  @Test
  void acceptsEveryNexusDocumentedTarCompressionVariant() throws Exception {
    byte[] source = "terraform {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    for (String suffix : List.of(".tar.gz", ".tgz", ".tar.xz", ".txz", ".xz", ".tar.bz2", ".tbz2")) {
      Path file = inspector.bufferAndInspect(
          new ByteArrayInputStream(tar(source, suffix)), "module" + suffix, true, null);
      assertTrue(Files.size(file) > 0, suffix);
      Files.delete(file);
    }
  }

  @Test
  void rejectsTraversalAndUnsupportedCompression() throws Exception {
    byte[] traversal = tarEntry("../escape.tf", "terraform {}".getBytes(), ".tar.gz");
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(new ByteArrayInputStream(traversal), "module.tar.gz", true, null));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(new ByteArrayInputStream(new byte[0]), "module.tar", true, null));
  }

  @Test
  void rejectsArchiveWithBombLikeExpansionRatio() throws Exception {
    byte[] zeros = new byte[2 * 1024 * 1024];
    byte[] archive = tar(zeros, ".tar.gz");
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(new ByteArrayInputStream(archive), "module.tar.gz", true, null));
  }

  @Test
  void acceptsZipModulesAndProviders() throws Exception {
    Path module = null;
    Path provider = null;
    try {
      module = inspector.bufferAndInspect(
          new ByteArrayInputStream(zipEntry(
              "fixture/main.tf", "terraform {}".getBytes(StandardCharsets.UTF_8))),
          "module.zip", true, null);
      provider = inspector.bufferAndInspect(
          new ByteArrayInputStream(zipEntry(
              "terraform-provider-fixture_v1.2.3", "binary".getBytes(StandardCharsets.UTF_8))),
          "provider.zip", false, "fixture");

      assertTrue(Files.size(module) > 0);
      assertTrue(Files.size(provider) > 0);
    } finally {
      if (module != null) Files.deleteIfExists(module);
      if (provider != null) Files.deleteIfExists(provider);
    }
  }

  @Test
  void rejectsArchivesWithoutExpectedContentAndUnsafeZipEntries() throws Exception {
    byte[] noModule = zipEntry("fixture/README.md", "readme".getBytes(StandardCharsets.UTF_8));
    byte[] wrongProvider = zipEntry(
        "terraform-provider-other", "binary".getBytes(StandardCharsets.UTF_8));
    byte[] traversal = zipEntry("../escape.tf", "terraform {}".getBytes(StandardCharsets.UTF_8));

    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(new ByteArrayInputStream(noModule), "module.zip", true, null));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(
            new ByteArrayInputStream(wrongProvider), "provider.zip", false, "fixture"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(new ByteArrayInputStream(traversal), "module.zip", true, null));
  }

  @Test
  void rejectsDuplicateLinkAndDeviceEntries() throws Exception {
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(
            new ByteArrayInputStream(tarEntries(List.of(
                entry("fixture/main.tf", "terraform {}".getBytes(StandardCharsets.UTF_8)),
                entry("fixture/main.tf", "terraform {}".getBytes(StandardCharsets.UTF_8))), ".tar.gz")),
            "module.tar.gz", true, null));
    for (byte type : List.of(TarConstants.LF_SYMLINK, TarConstants.LF_FIFO)) {
      assertThrows(MavenExceptions.BadRequestException.class,
          () -> inspector.bufferAndInspect(
              new ByteArrayInputStream(tarSpecialEntry("fixture/main.tf", type, ".tar.gz")),
              "module.tar.gz", true, null));
    }
  }

  @Test
  void rejectsUnreadableUploadAndInvalidFilename() {
    InputStream unreadable = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("broken stream");
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        throw new IOException("broken stream");
      }
    };
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(unreadable, "module.zip", true, null));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.bufferAndInspect(new ByteArrayInputStream(new byte[0]), null, true, null));
  }

  private static byte[] tar(byte[] content, String suffix) throws IOException {
    return tarEntry("fixture/main.tf", content, suffix);
  }

  private static byte[] tarEntry(String name, byte[] content, String suffix) throws IOException {
    return tarEntries(List.of(entry(name, content)), suffix);
  }

  private static byte[] tarEntries(List<ArchiveEntry> entries, String suffix) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream compressed = compressor(bytes, suffix);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
      for (ArchiveEntry item : entries) {
        TarArchiveEntry entry = new TarArchiveEntry(item.name());
        entry.setSize(item.content().length);
        tar.putArchiveEntry(entry);
        tar.write(item.content());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static byte[] tarSpecialEntry(String name, byte type, String suffix) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream compressed = compressor(bytes, suffix);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
      TarArchiveEntry entry = new TarArchiveEntry(name, type);
      if (type == TarConstants.LF_SYMLINK) entry.setLinkName("fixture/target.tf");
      tar.putArchiveEntry(entry);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static byte[] zipEntry(String name, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static ArchiveEntry entry(String name, byte[] content) {
    return new ArchiveEntry(name, content);
  }

  private static OutputStream compressor(OutputStream out, String suffix) throws IOException {
    if (suffix.endsWith("gz") || suffix.endsWith("tgz")) return new GZIPOutputStream(out);
    if (suffix.endsWith("xz") || suffix.endsWith("txz")) return new XZCompressorOutputStream(out);
    return new BZip2CompressorOutputStream(out);
  }

  private record ArchiveEntry(String name, byte[] content) {}
}
