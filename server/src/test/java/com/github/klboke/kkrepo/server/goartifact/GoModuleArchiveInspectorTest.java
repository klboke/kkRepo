package com.github.klboke.kkrepo.server.goartifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoModuleArchiveInspectorTest {
  @TempDir
  Path temp;

  @Test
  void validatesCanonicalLayoutAndExtractsGoMod() throws Exception {
    byte[] goMod = "module example.com/Acme/demo/v2\n\ngo 1.25\n"
        .getBytes(StandardCharsets.UTF_8);
    Path archive = zip(Map.of(
        "example.com/Acme/demo/v2@v2.1.0/go.mod", goMod,
        "example.com/Acme/demo/v2@v2.1.0/demo.go", "package demo\n".getBytes(StandardCharsets.UTF_8)));

    GoModuleArchiveInspector.Inspected inspected = new GoModuleArchiveInspector().inspect(archive, "v2.1.0");

    assertEquals("example.com/Acme/demo/v2", inspected.module());
    assertEquals("example.com/!acme/demo/v2", inspected.escapedModule());
    assertArrayEquals(goMod, inspected.goMod());
  }

  @Test
  void synthesizesGoModWhenArchiveDoesNotContainOne() throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("example.com/acme/demo@v1.0.0/", new byte[0]);
    entries.put(
        "example.com/acme/demo@v1.0.0/demo.go",
        "package demo\n".getBytes(StandardCharsets.UTF_8));
    Path archive = zip(entries);

    GoModuleArchiveInspector.Inspected inspected = new GoModuleArchiveInspector().inspect(archive, "v1.0.0");

    assertEquals("module example.com/acme/demo\n",
        new String(inspected.goMod(), StandardCharsets.UTF_8));
  }

  @Test
  void acceptsRootDirectoryForSingleSegmentModulePath() throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("example.com@v1.0.0/", new byte[0]);
    entries.put(
        "example.com@v1.0.0/go.mod",
        "module example.com\n".getBytes(StandardCharsets.UTF_8));
    Path archive = zip(entries);

    GoModuleArchiveInspector.Inspected inspected =
        new GoModuleArchiveInspector().inspect(archive, "v1.0.0");

    assertEquals("example.com", inspected.module());
  }

  @Test
  void validatesDirectoryEntriesAndTheirCaseCollisions() throws Exception {
    Path outsideRoot = zip(Map.of(
        "example.com/", new byte[0],
        "example.com/acme/demo@v1.0.0/demo.go", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(outsideRoot, "v1.0.0"));

    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("example.com/acme/demo@v1.0.0/Assets/", new byte[0]);
    entries.put("example.com/acme/demo@v1.0.0/assets/demo.go", new byte[] {1});
    Path collision = zip(entries);
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(collision, "v1.0.0"));

    Path repeatedTrailingSlash = zip(Map.of(
        "example.com/acme/demo@v1.0.0//", new byte[0],
        "example.com/acme/demo@v1.0.0/demo.go", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(repeatedTrailingSlash, "v1.0.0"));
  }

  @Test
  void rejectsVersionMismatchNestedModulesAndCaseCollisions() throws Exception {
    Path mismatch = zip(Map.of(
        "example.com/acme/demo@v1.0.1/go.mod",
        "module example.com/acme/demo\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(mismatch, "v1.0.0"));

    Path nested = zip(Map.of(
        "example.com/acme/demo@v1.0.0/go.mod",
        "module example.com/acme/demo\n".getBytes(StandardCharsets.UTF_8),
        "example.com/acme/demo@v1.0.0/sub/go.mod",
        "module example.com/acme/sub\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(nested, "v1.0.0"));

    Map<String, byte[]> collisionEntries = new LinkedHashMap<>();
    collisionEntries.put("example.com/acme/demo@v1.0.0/go.mod",
        "module example.com/acme/demo\n".getBytes(StandardCharsets.UTF_8));
    collisionEntries.put("example.com/acme/demo@v1.0.0/README", new byte[] {1});
    collisionEntries.put("example.com/acme/demo@v1.0.0/readme", new byte[] {2});
    Path collision = zip(collisionEntries);
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(collision, "v1.0.0"));
  }

  @Test
  void enforcesExpandedAndGoModLimits() throws Exception {
    Path oversizedMod = zip(Map.of(
        "example.com/acme/demo@v1.0.0/go.mod",
        "module example.com/acme/demo\n".getBytes(StandardCharsets.UTF_8)));
    GoModuleArchiveInspector tiny = new GoModuleArchiveInspector(1024 * 1024, 1024 * 1024, 8, 10);
    assertThrows(IllegalArgumentException.class, () -> tiny.inspect(oversizedMod, "v1.0.0"));

    Path oversizedTree = zip(Map.of(
        "example.com/acme/demo@v1.0.0/a.go", new byte[] {1, 2, 3, 4},
        "example.com/acme/demo@v1.0.0/b.go", new byte[] {5, 6, 7, 8}));
    GoModuleArchiveInspector smallTree =
        new GoModuleArchiveInspector(1024 * 1024, 7, 1024, 10);
    assertThrows(IllegalArgumentException.class,
        () -> smallTree.inspect(oversizedTree, "v1.0.0"));
  }

  @Test
  void rejectsInvalidGoFilePathsAndIncorrectGoModCase() throws Exception {
    Path windowsName = zip(Map.of(
        "example.com/acme/demo@v1.0.0/CON.txt", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(windowsName, "v1.0.0"));

    Path badCharacter = zip(Map.of(
        "example.com/acme/demo@v1.0.0/bad?.go", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(badCharacter, "v1.0.0"));

    Path wrongCase = zip(Map.of(
        "example.com/acme/demo@v1.0.0/Go.Mod",
        "module example.com/acme/demo\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(wrongCase, "v1.0.0"));

    Map<String, byte[]> fileDirectoryCollision = new LinkedHashMap<>();
    fileDirectoryCollision.put("example.com/acme/demo@v1.0.0/a", new byte[] {1});
    fileDirectoryCollision.put("example.com/acme/demo@v1.0.0/a/b.go", new byte[] {2});
    Path collision = zip(fileDirectoryCollision);
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(collision, "v1.0.0"));
  }

  private Path zip(Map<String, byte[]> entries) throws IOException {
    Path file = Files.createTempFile(temp, "module-", ".zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue());
        output.closeEntry();
      }
    }
    return file;
  }
}
