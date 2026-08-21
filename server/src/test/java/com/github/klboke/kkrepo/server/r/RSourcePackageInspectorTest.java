package com.github.klboke.kkrepo.server.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class RSourcePackageInspectorTest {
  private final RSourcePackageInspector inspector = new RSourcePackageInspector();

  @Test
  void validatesDescriptionIdentityAndPreservesOriginalArchive() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.2-3");

    try (RSourcePackageInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(bytes), "demo_1.2-3.tar.gz")) {
      assertEquals("demo", inspected.metadata().packageName());
      assertEquals("1.2-3", inspected.metadata().version());
      assertEquals(bytes.length, inspected.size());
      assertEquals(32, inspected.md5().length());
      assertEquals(40, inspected.sha1().length());
      assertEquals(64, inspected.sha256().length());
      assertTrue(inspected.entryCount() >= 3);
      assertTrue(Files.exists(inspected.file()));
      assertEquals(bytes.length, Files.size(inspected.file()));
    }
  }

  @Test
  void rejectsFilenameDriftMultipleRootsTraversalLinksAndDuplicateDescription() throws Exception {
    byte[] valid = RTestPackage.source("demo", "1.0.0");
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(valid), "other_1.0.0.tar.gz"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(valid), "demo_1.0.0.zip"));

    byte[] multipleRoots = RTestPackage.archive(List.of(
        RTestPackage.Item.file("demo/DESCRIPTION", "Package: demo\n"),
        RTestPackage.Item.file("other/file", "x")));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(multipleRoots), "demo_1.0.0.tar.gz"));

    byte[] traversal = RTestPackage.archive(List.of(
        RTestPackage.Item.file("demo/DESCRIPTION", "Package: demo\n"),
        RTestPackage.Item.file("demo/../outside", "x")));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(traversal), "demo_1.0.0.tar.gz"));

    byte[] linked = RTestPackage.source(
        "demo", "1.0.0", List.of(RTestPackage.Item.link("demo/link", "../../outside")));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(linked), "demo_1.0.0.tar.gz"));

    byte[] duplicate = RTestPackage.source(
        "demo", "1.0.0", List.of(RTestPackage.Item.file(
            "demo/DESCRIPTION", "Package: demo\nVersion: 1.0.0\n")));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(duplicate), "demo_1.0.0.tar.gz"));
  }

  @Test
  void enforcesCompressedExpandedEntryAndCountLimits() throws Exception {
    byte[] bytes = RTestPackage.source("demo", "1.0.0");
    RSourcePackageInspector compressed = new RSourcePackageInspector(
        bytes.length - 1L, 1024 * 1024, 1024 * 1024, 500, 100, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> compressed.inspect(
        new ByteArrayInputStream(bytes), "demo_1.0.0.tar.gz"));

    RSourcePackageInspector expanded = new RSourcePackageInspector(
        bytes.length + 1L, 16, 1024 * 1024, 500, 100, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> expanded.inspect(
        new ByteArrayInputStream(bytes), "demo_1.0.0.tar.gz"));

    RSourcePackageInspector entry = new RSourcePackageInspector(
        bytes.length + 1L, 1024 * 1024, 8, 500, 100, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> entry.inspect(
        new ByteArrayInputStream(bytes), "demo_1.0.0.tar.gz"));

    RSourcePackageInspector count = new RSourcePackageInspector(
        bytes.length + 1L, 1024 * 1024, 1024 * 1024, 500, 1, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> count.inspect(
        new ByteArrayInputStream(bytes), "demo_1.0.0.tar.gz"));
  }

  @Test
  void rejectsEmptyTruncatedAndFailingStreams() throws Exception {
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(null, "demo_1.0.0.tar.gz"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(new byte[0]), "demo_1.0.0.tar.gz"));
    byte[] valid = RTestPackage.source("demo", "1.0.0");
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(java.util.Arrays.copyOf(valid, valid.length - 2)),
        "demo_1.0.0.tar.gz"));
    InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("fixture failure");
      }
    };
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        failing, "demo_1.0.0.tar.gz"));
  }
}
