package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.junit.jupiter.api.Test;

class ConanArchiveInspectorTest {
  private final ConanArchiveInspector inspector = new ConanArchiveInspector();

  @Test
  void validatesEverySupportedCompressorAndReconstructsTheManifestTree() throws Exception {
    byte[] content = "#include <kkrepo/conan.h>\n".getBytes(StandardCharsets.UTF_8);
    for (ArchiveKind kind : ArchiveKind.values()) {
      byte[] archive = archive(kind.compressor, List.of(Item.file("include/conan.h", content)));

      inspector.inspect(new ByteArrayInputStream(archive), archive.length, "conan_sources." + kind.extension);
      Map<String, String> projected = inspector.manifestEntries(
          new ByteArrayInputStream(archive), archive.length, "conan_sources." + kind.extension,
          "export_source/", 16);

      assertEquals(
          "509f9c8d4b07d6eafa41670712365089",
          projected.get("export_source/include/conan.h"));
    }
  }

  @Test
  void acceptsAnEmptyPackageArchiveButNotAnEmptyRecipeArchive() throws Exception {
    byte[] archive = archive(CompressorStreamFactory.GZIP, List.of());

    inspector.inspect(new ByteArrayInputStream(archive), archive.length, "conan_package.tgz");
    assertEquals(Map.of(), inspector.manifestEntries(
        new ByteArrayInputStream(archive), archive.length, "conan_package.tgz", "", 1));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(archive), archive.length, "conan_export.tgz"));
  }

  @Test
  void rejectsCompressorSpoofingTraversalDuplicateEntriesAndEscapingLinks() throws Exception {
    byte[] xz = archive(CompressorStreamFactory.XZ, List.of(Item.file("safe", new byte[] {1})));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(xz), xz.length, "conan_package.tgz"));

    byte[] traversal = archive(
        CompressorStreamFactory.GZIP, List.of(Item.file("../escape", new byte[] {1})));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(traversal), traversal.length, "conan_package.tgz"));

    byte[] duplicate = archive(CompressorStreamFactory.GZIP, List.of(
        Item.file("same", new byte[] {1}), Item.file("same", new byte[] {2})));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(duplicate), duplicate.length, "conan_package.tgz"));

    byte[] link = archive(
        CompressorStreamFactory.GZIP, List.of(Item.symlink("bin/tool", "../../outside")));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(link), link.length, "conan_package.tgz"));
  }

  private static byte[] archive(String compressor, List<Item> items) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream compressed = new CompressorStreamFactory()
             .createCompressorOutputStream(compressor, bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Item item : items) {
        TarArchiveEntry entry = item.link == null
            ? new TarArchiveEntry(item.name)
            : new TarArchiveEntry(item.name, TarConstants.LF_SYMLINK);
        if (item.link != null) {
          entry.setLinkName(item.link);
          entry.setSize(0);
        } else {
          entry.setSize(item.content.length);
        }
        tar.putArchiveEntry(entry);
        if (item.link == null) tar.write(item.content);
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private enum ArchiveKind {
    GZIP(CompressorStreamFactory.GZIP, "tgz"),
    XZ(CompressorStreamFactory.XZ, "txz"),
    ZSTD(CompressorStreamFactory.ZSTANDARD, "tzst");

    private final String compressor;
    private final String extension;

    ArchiveKind(String compressor, String extension) {
      this.compressor = compressor;
      this.extension = extension;
    }
  }

  private record Item(String name, byte[] content, String link) {
    static Item file(String name, byte[] content) {
      return new Item(name, content, null);
    }

    static Item symlink(String name, String target) {
      return new Item(name, new byte[0], target);
    }
  }
}
