package com.github.klboke.kkrepo.server.terraform;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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

  private static byte[] tar(byte[] content, String suffix) throws IOException {
    return tarEntry("fixture/main.tf", content, suffix);
  }

  private static byte[] tarEntry(String name, byte[] content, String suffix) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream compressed = compressor(bytes, suffix);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
      TarArchiveEntry entry = new TarArchiveEntry(name);
      entry.setSize(content.length);
      tar.putArchiveEntry(entry);
      tar.write(content);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static OutputStream compressor(OutputStream out, String suffix) throws IOException {
    if (suffix.endsWith("gz") || suffix.endsWith("tgz")) return new GZIPOutputStream(out);
    if (suffix.endsWith("xz") || suffix.endsWith("txz")) return new XZCompressorOutputStream(out);
    return new BZip2CompressorOutputStream(out);
  }
}
