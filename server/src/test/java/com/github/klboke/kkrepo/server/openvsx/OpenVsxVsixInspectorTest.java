package com.github.klboke.kkrepo.server.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenVsxVsixInspectorTest {
  @TempDir
  Path tempDir;

  @Test
  void readsCanonicalPackageJson() throws Exception {
    Path vsix = tempDir.resolve("demo.vsix");
    Files.write(vsix, vsix("""
        {"publisher":"acme","name":"demo","version":"1.2.3","displayName":"Demo",
         "description":"Demo extension","categories":["Programming Languages"]}
        """));

    OpenVsxVsixInspector.Inspected inspected =
        new OpenVsxVsixInspector(new ObjectMapper()).inspect(vsix, null);

    assertEquals("acme", inspected.namespace());
    assertEquals("demo", inspected.name());
    assertEquals("1.2.3", inspected.version());
    assertEquals("Demo", inspected.displayName());
    assertFalse(inspected.prerelease());
    assertEquals("acme.demo-1.2.3.vsix", inspected.fileName());
  }

  @Test
  void platformSpecificPackagesGetDistinctNames() throws Exception {
    Path vsix = tempDir.resolve("demo-linux.vsix");
    Files.write(vsix, vsix("""
        {"publisher":"acme","name":"demo","version":"1.2.3","targetPlatform":"linux-x64"}
        """));

    OpenVsxVsixInspector.Inspected inspected =
        new OpenVsxVsixInspector(new ObjectMapper()).inspect(vsix, null);

    assertEquals("linux-x64", inspected.targetPlatform());
    assertEquals("acme.demo-1.2.3-linux-x64.vsix", inspected.fileName());
  }

  @Test
  void acceptsLegitimateNamesContainingDotDot() throws Exception {
    Path archive = tempDir.resolve("dots-archive.vsix");
    Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
    entries.put("extension/package.json", """
        {"publisher":"acme","name":"demo","version":"1.2.3"}
        """.getBytes(StandardCharsets.UTF_8));
    // A file name containing ".." is legal as long as it is not a traversal segment.
    entries.put("extension/foo..bar.js", "content".getBytes(StandardCharsets.UTF_8));
    Files.write(archive, zip(entries));

    OpenVsxVsixInspector.Inspected inspected =
        new OpenVsxVsixInspector(new ObjectMapper()).inspect(archive, null);
    assertEquals("demo", inspected.name());
  }

  @Test
  void rejectsUnsignedOrInvalidArchive() throws Exception {
    Path vsix = tempDir.resolve("bad.vsix");
    Files.write(vsix, vsix("""
        {"publisher":"acme","name":"demo","version":"not a version"}
        """));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> new OpenVsxVsixInspector(new ObjectMapper()).inspect(vsix, null));

    Path empty = tempDir.resolve("empty.vsix");
    Files.write(empty, vsixWithoutPackageJson());
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> new OpenVsxVsixInspector(new ObjectMapper()).inspect(empty, null));
  }

  private static byte[] vsix(String packageJson) throws Exception {
    return zip("extension/package.json", packageJson.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] vsixWithoutPackageJson() throws Exception {
    return zip("extension/README.md", "hello".getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] zip(String name, byte[] content) throws Exception {
    return zip(Map.of(name, content));
  }

  private static byte[] zip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
