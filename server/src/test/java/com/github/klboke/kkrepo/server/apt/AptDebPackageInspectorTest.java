package com.github.klboke.kkrepo.server.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class AptDebPackageInspectorTest {
  private final AptDebPackageInspector inspector = new AptDebPackageInspector();

  @Test
  void acceptsGzipXzZstdAndUncompressedControlArchives() throws Exception {
    for (String compression : List.of("gz", "xz", "zst", "none")) {
      byte[] archive = AptTestPackage.deb(
          compression, AptTestPackage.control("demo", "1:2.0~rc1-3", "amd64"));
      try (AptDebPackageInspector.InspectedPackage inspected = inspector.inspect(
          new ByteArrayInputStream(archive), "demo_2.0~rc1-3_amd64.deb")) {
        assertEquals("demo", inspected.control().packageName());
        assertEquals("1:2.0~rc1-3", inspected.control().version());
        assertEquals("amd64", inspected.control().architecture());
        assertEquals(archive.length, inspected.size());
        assertEquals(32, inspected.md5().length());
        assertEquals(40, inspected.sha1().length());
        assertEquals(64, inspected.sha256().length());
        assertTrue(Files.exists(inspected.file()));
      }
    }
  }

  @Test
  void rejectsUnsafeOrMalformedArchives() throws Exception {
    byte[] valid = AptTestPackage.deb("gz", AptTestPackage.control("demo", "1.0", "all"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(valid), "../demo.deb"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(valid), "demo.zip"));
    try (AptDebPackageInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(valid), "other_1.0_all.deb")) {
      assertEquals("demo_1.0_all.deb", inspected.filename());
    }
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(new byte[0]), "demo.deb"));

    byte[] oversizedFilename = AptTestPackage.deb(
        "gz", AptTestPackage.control("demo", "1." + "a".repeat(250), "amd64"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(oversizedFilename), "demo.deb"));

    byte[] missingData = AptTestPackage.withMembers(List.of(
        new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
        new AptTestPackage.Item("control.tar.gz", new byte[] {1, 2, 3})));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(missingData), "demo.deb"));

    byte[] unsafeControl = AptTestPackage.deb("gz", List.of(
        new AptTestPackage.Item("../control", AptTestPackage.control("demo", "1", "all").getBytes())));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(unsafeControl), "demo.deb"));
  }

  @Test
  void enforcesCompressedAndExpandedLimits() throws Exception {
    byte[] archive = AptTestPackage.deb("gz", AptTestPackage.control("demo", "1.0", "amd64"));
    AptDebPackageInspector tinyCompressed = new AptDebPackageInspector(
        archive.length - 1L, 1024 * 1024, 100, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> tinyCompressed.inspect(new ByteArrayInputStream(archive), "demo_1.0_amd64.deb"));

    AptDebPackageInspector tinyExpanded = new AptDebPackageInspector(
        archive.length + 1L, 16, 100, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> tinyExpanded.inspect(new ByteArrayInputStream(archive), "demo_1.0_amd64.deb"));
  }
}
