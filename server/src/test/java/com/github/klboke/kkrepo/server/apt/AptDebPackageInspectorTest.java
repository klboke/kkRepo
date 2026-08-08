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
    for (String compression : List.of("gz", "xz", "zst", "bz2", "none")) {
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

  @Test
  void validatesBodyFilenameArOrderingAndDataMembers() throws Exception {
    byte[] control = AptTestPackage.tar("gz", List.of(new AptTestPackage.Item(
        "./control", AptTestPackage.control("demo", "1.0", "all").getBytes())));
    byte[] data = AptTestPackage.tar("gz", List.of(
        new AptTestPackage.Item("./usr/share/demo", new byte[] {1})));
    byte[] valid = AptTestPackage.withMembers(List.of(
        new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
        new AptTestPackage.Item("control.tar.gz", control),
        new AptTestPackage.Item("data.tar.gz", data)));

    for (String filename : new String[] {"", " ", "demo.zip", "../demo.deb", "a\\demo.deb", ".", ".."}) {
      assertThrows(MavenExceptions.BadRequestException.class,
          () -> inspector.inspect(new ByteArrayInputStream(valid), filename), filename);
    }
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(null, "demo.deb"));

    byte[] withPrivateMember = AptTestPackage.withMembers(List.of(
        new AptTestPackage.Item("_gpgorigin", "signature".getBytes()),
        new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
        new AptTestPackage.Item("control.tar.gz", control),
        new AptTestPackage.Item("data.tar.gz", data)));
    try (AptDebPackageInspector.InspectedPackage ignored = inspector.inspect(
        new ByteArrayInputStream(withPrivateMember), null)) {
      assertTrue(Files.exists(ignored.file()));
    }

    for (byte[] invalid : List.of(
        AptTestPackage.withMembers(List.of(
            new AptTestPackage.Item("debian-binary", "1.0\n".getBytes()),
            new AptTestPackage.Item("control.tar.gz", control),
            new AptTestPackage.Item("data.tar.gz", data))),
        AptTestPackage.withMembers(List.of(
            new AptTestPackage.Item("control.tar.gz", control),
            new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
            new AptTestPackage.Item("data.tar.gz", data))),
        AptTestPackage.withMembers(List.of(
            new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
            new AptTestPackage.Item("unexpected", new byte[] {1}),
            new AptTestPackage.Item("control.tar.gz", control),
            new AptTestPackage.Item("data.tar.gz", data))),
        AptTestPackage.withMembers(List.of(
            new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
            new AptTestPackage.Item("control.tar.gz", control),
            new AptTestPackage.Item("data.tar.foo", data))),
        AptTestPackage.withMembers(List.of(
            new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()),
            new AptTestPackage.Item("control.tar.gz", control),
            new AptTestPackage.Item("data.tar.gz", new byte[0]))))) {
      assertThrows(MavenExceptions.BadRequestException.class,
          () -> inspector.inspect(new ByteArrayInputStream(invalid), "demo.deb"));
    }

    java.util.ArrayList<AptTestPackage.Item> tooMany = new java.util.ArrayList<>();
    tooMany.add(new AptTestPackage.Item("debian-binary", "2.0\n".getBytes()));
    for (int index = 0; index < 16; index++) {
      tooMany.add(new AptTestPackage.Item("_member" + index, new byte[] {1}));
    }
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AptTestPackage.withMembers(tooMany)), "demo.deb"));
  }

  @Test
  void validatesControlArchiveEntriesAndMetadata() throws Exception {
    byte[] validControl = AptTestPackage.control("demo", "1.0", "all").getBytes();
    for (List<AptTestPackage.Item> entries : List.of(
        List.of(new AptTestPackage.Item("./postinst", new byte[] {1})),
        List.of(
            new AptTestPackage.Item("./control", validControl),
            new AptTestPackage.Item("control", validControl)),
        List.of(new AptTestPackage.Item("./control", validControl, true)),
        List.of(new AptTestPackage.Item("../control", validControl)),
        List.of(new AptTestPackage.Item("bad\\control", validControl)),
        List.of(new AptTestPackage.Item("./control", new byte[] {(byte) 0xc3, 0x28})),
        List.of(new AptTestPackage.Item("./control", "Package: demo\n".getBytes())))) {
      byte[] archive = AptTestPackage.deb("gz", entries);
      assertThrows(MavenExceptions.BadRequestException.class,
          () -> inspector.inspect(new ByteArrayInputStream(archive), "demo.deb"), entries.toString());
    }

    byte[] twoEntries = AptTestPackage.deb("gz", List.of(
        new AptTestPackage.Item("./control", validControl),
        new AptTestPackage.Item("./postinst", new byte[] {1})));
    AptDebPackageInspector oneEntry = new AptDebPackageInspector(
        twoEntries.length + 1L, 1024 * 1024, 1, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> oneEntry.inspect(new ByteArrayInputStream(twoEntries), "demo.deb"));
  }
}
