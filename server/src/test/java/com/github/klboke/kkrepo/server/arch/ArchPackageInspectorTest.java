package com.github.klboke.kkrepo.server.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchPackageInspectorTest {
  @TempDir
  Path tempDir;

  @Test
  void readsPkginfoAndValidatesFilenameIdentity() throws Exception {
    Path packageFile = tempDir.resolve("demo-1.0.0-1-x86_64.pkg.tar");
    Files.write(packageFile, packageWithPkginfo("""
        pkgname = demo
        pkgbase = demo
        pkgver = 1.0.0-1
        pkgdesc = Demo package
        url = https://example.test/demo
        builddate = 1700000000
        packager = Demo Packager
        size = 5678
        arch = x86_64
        license = MIT
        depend = glibc
        optdepend = bash: completion
        provides = demo-api=1.0
        """));

    ArchPackageInspector.Inspected inspected = new ArchPackageInspector().inspect(
        packageFile, "demo-1.0.0-1-x86_64.pkg.tar", 1234, "md5", "sha256");

    assertEquals("demo", inspected.desc().name());
    assertEquals("1.0.0-1", inspected.desc().version());
    assertEquals("x86_64", inspected.desc().architecture());
    assertEquals(1234, inspected.desc().compressedSize());
    assertEquals(5678, inspected.desc().installedSize());
    assertEquals("md5", inspected.desc().md5());
    assertEquals("sha256", inspected.desc().sha256());
    assertEquals(java.util.List.of("glibc"), inspected.desc().depends());
    assertEquals(java.util.List.of("bash: completion"), inspected.desc().optionalDepends());
  }

  @Test
  void rejectsFilenameDriftAndMissingPkginfo() throws Exception {
    Path packageFile = tempDir.resolve("demo-1.0.0-1-x86_64.pkg.tar");
    Files.write(packageFile, packageWithPkginfo("""
        pkgname = other
        pkgver = 1.0.0-1
        arch = x86_64
        """));

    assertThrows(MavenExceptions.BadRequestException.class,
        () -> new ArchPackageInspector().inspect(
            packageFile, "demo-1.0.0-1-x86_64.pkg.tar", 1, "md5", "sha256"));

    Path empty = tempDir.resolve("empty-1.0.0-1-any.pkg.tar");
    Files.write(empty, tarWithoutPkginfo());
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> new ArchPackageInspector().inspect(
            empty, "empty-1.0.0-1-any.pkg.tar", 1, "md5", "sha256"));
  }

  private static byte[] packageWithPkginfo(String pkginfo) throws Exception {
    return tar(entry(".PKGINFO", pkginfo.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] tarWithoutPkginfo() throws Exception {
    return tar(entry("usr/bin/demo", "demo".getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] tar(Entry... entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
      for (Entry entry : entries) {
        TarArchiveEntry archiveEntry = new TarArchiveEntry(entry.name());
        archiveEntry.setSize(entry.content().length);
        tar.putArchiveEntry(archiveEntry);
        tar.write(entry.content());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return bytes.toByteArray();
  }

  private static Entry entry(String name, byte[] content) {
    return new Entry(name, content);
  }

  private record Entry(String name, byte[] content) {
  }
}
