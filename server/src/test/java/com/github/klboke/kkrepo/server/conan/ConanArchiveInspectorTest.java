package com.github.klboke.kkrepo.server.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
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
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.manifestEntries(
        new ByteArrayInputStream(duplicate), duplicate.length, "conan_package.tgz", "", 3));

    byte[] link = archive(
        CompressorStreamFactory.GZIP, List.of(Item.symlink("bin/tool", "../../outside")));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(link), link.length, "conan_package.tgz"));
  }

  @Test
  void classifiesOnlyCanonicalConanArchiveNames() {
    assertFalse(inspector.archive(null));
    assertFalse(inspector.archive("conan_package.zip"));
    assertTrue(inspector.archive("nested/conan_export.tgz"));
    assertTrue(inspector.exportArchive("nested/conan_export.txz"));
    assertFalse(inspector.exportArchive("nested/conan_sources.txz"));
    assertTrue(inspector.packageArchive("nested/conan_package.tzst"));
    assertFalse(inspector.packageArchive("nested/conan_export.tzst"));
    inspector.inspect(InputStream.nullInputStream(), 0, "ordinary.txt");
    assertEquals(Map.of(), inspector.manifestEntries(
        InputStream.nullInputStream(), 0, "ordinary.txt", null, 0));
  }

  @Test
  void reconstructsDirectoriesAndSafeLinksAndEnforcesManifestBounds() throws Exception {
    byte[] archive = archive(CompressorStreamFactory.GZIP, List.of(
        Item.directory("bin/"),
        Item.file("bin/tool", "tool".getBytes(StandardCharsets.UTF_8)),
        Item.symlink("current", "bin/tool")));

    inspector.inspect(new ByteArrayInputStream(archive), archive.length, "conan_package.tgz");
    Map<String, String> entries = inspector.manifestEntries(
        new ByteArrayInputStream(archive), archive.length, "conan_package.tgz", null, 3);
    assertEquals(2, entries.size());
    assertEquals("d90eefbd443621dcbe2063b2d551074a", entries.get("current"));
    assertThrows(ConanExceptions.ContentTooLarge.class, () -> inspector.manifestEntries(
        new ByteArrayInputStream(archive), archive.length, "conan_package.tgz", "", 1));
    assertThrows(ConanExceptions.Busy.class, () -> inspector.manifestEntries(
        new ByteArrayInputStream(archive), archive.length, "conan_package.tgz", "", 0));
  }

  @Test
  void rejectsUnsafePathsUnsupportedEntriesAndExpansionBombs() throws Exception {
    for (String unsafe : List.of(
        "/absolute", "back\\slash", "control\u0001path", "a/../b", "./same")) {
      assertUnsafePath(unsafe);
    }
    String deep = String.join("/", java.util.Collections.nCopies(129, "x"));
    assertUnsafePath(deep);

    byte[] fifo = archive(
        CompressorStreamFactory.GZIP, List.of(Item.special("pipe", TarConstants.LF_FIFO)));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(fifo), fifo.length, "conan_package.tgz"));
    byte[] expanded = archive(
        CompressorStreamFactory.GZIP, List.of(Item.file("large", new byte[1001])));
    assertThrows(ConanExceptions.ContentTooLarge.class, () -> inspector.inspect(
        new ByteArrayInputStream(expanded), 1, "conan_package.tgz"));
    assertThrows(ConanExceptions.ContentTooLarge.class, () -> inspector.manifestEntries(
        new ByteArrayInputStream(expanded), 1, "conan_package.tgz", "", 2));

    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.inspect(
        new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "conan_package.tgz"));
    assertThrows(ConanExceptions.BadRequest.class, () -> inspector.manifestEntries(
        new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "conan_package.tgz", "", 2));
  }

  @Test
  void rejectsMissingLinkTargetsAndFailsFastWhenInspectionCapacityIsExhausted()
      throws Exception {
    var validateLink = ConanArchiveInspector.class.getDeclaredMethod(
        "validateLink", String.class, String.class);
    validateLink.setAccessible(true);
    InvocationTargetException unsafe = assertThrows(
        InvocationTargetException.class, () -> validateLink.invoke(null, "bin/tool", null));
    assertTrue(unsafe.getCause() instanceof ConanExceptions.BadRequest);

    Field slotsField = ConanArchiveInspector.class.getDeclaredField("INSPECTION_SLOTS");
    slotsField.setAccessible(true);
    Semaphore slots = (Semaphore) slotsField.get(null);
    int permits = slots.availablePermits();
    slots.acquire(permits);
    try {
      assertThrows(ConanExceptions.Busy.class, () -> inspector.inspect(
          InputStream.nullInputStream(), 0, "conan_package.tgz"));
    } finally {
      slots.release(permits);
    }
  }

  private static byte[] archive(String compressor, List<Item> items) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream compressed = new CompressorStreamFactory()
             .createCompressorOutputStream(compressor, bytes);
         TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Item item : items) {
        TarArchiveEntry entry = item.type == null
            ? new TarArchiveEntry(item.name)
            : new TarArchiveEntry(item.name, item.type);
        if (item.link != null) {
          entry.setLinkName(item.link);
          entry.setSize(0);
        } else if (!entry.isDirectory()) {
          entry.setSize(item.content.length);
        }
        tar.putArchiveEntry(entry);
        if (item.link == null && !entry.isDirectory()) tar.write(item.content);
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static void assertUnsafePath(String value) throws Exception {
    var method = ConanArchiveInspector.class.getDeclaredMethod("normalizedEntry", String.class);
    method.setAccessible(true);
    InvocationTargetException failure = assertThrows(
        InvocationTargetException.class, () -> method.invoke(null, value), value);
    assertTrue(failure.getCause() instanceof ConanExceptions.BadRequest);
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

  private record Item(String name, byte[] content, String link, Byte type) {
    static Item file(String name, byte[] content) {
      return new Item(name, content, null, null);
    }

    static Item symlink(String name, String target) {
      return new Item(name, new byte[0], target, TarConstants.LF_SYMLINK);
    }

    static Item directory(String name) {
      return new Item(name, new byte[0], null, TarConstants.LF_DIR);
    }

    static Item special(String name, byte type) {
      return new Item(name, new byte[0], null, type);
    }
  }
}
