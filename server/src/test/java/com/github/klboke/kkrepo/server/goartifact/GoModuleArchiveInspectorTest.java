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

    Path dotSegment = zip(Map.of(
        "example.com/acme/demo@v1.0.0/./demo.go", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(dotSegment, "v1.0.0"));

    Path trailingDot = zip(Map.of(
        "example.com/acme/demo@v1.0.0/demo.", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(trailingDot, "v1.0.0"));

    Path unicodeLetter = zip(Map.of(
        "example.com/acme/demo@v1.0.0/café.go", new byte[] {1}));
    assertEquals("example.com/acme/demo",
        new GoModuleArchiveInspector().inspect(unicodeLetter, "v1.0.0").module());
  }

  @Test
  void rejectsUnreadableEmptyOversizedAndEntrylessArchives() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(temp.resolve("missing.zip"), "v1.0.0"));

    Path emptyFile = Files.createTempFile(temp, "empty-", ".zip");
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(emptyFile, "v1.0.0"));

    Path archive = zip(Map.of(
        "example.com/acme/demo@v1.0.0/demo.go", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector(1, 1024, 1024, 10).inspect(archive, "v1.0.0"));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(archive, "not-semver"));

    Path entryless = zip(Map.of());
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(entryless, "v1.0.0"));
  }

  @Test
  void rejectsEntryCountRootAndCoordinateViolations() throws Exception {
    Map<String, byte[]> twoEntries = new LinkedHashMap<>();
    twoEntries.put("example.com/acme/demo@v1.0.0/a.go", new byte[] {1});
    twoEntries.put("example.com/acme/demo@v1.0.0/b.go", new byte[] {2});
    Path tooMany = zip(twoEntries);
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector(1024 * 1024, 1024, 1024, 1)
            .inspect(tooMany, "v1.0.0"));

    Path rootAsFile = zip(Map.of(
        "example.com/acme/demo@v1.0.0", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(rootAsFile, "v1.0.0"));

    Path multipleRoots = zip(Map.of(
        "example.com/acme/one@v1.0.0/a.go", new byte[] {1},
        "example.com/acme/two@v1.0.0/b.go", new byte[] {2}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(multipleRoots, "v1.0.0"));

    Path missingVersion = zip(Map.of(
        "example.com/acme/demo@/", new byte[0]));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(missingVersion, "v1.0.0"));

    Path absolute = zip(Map.of(
        "/example.com/acme/demo@v1.0.0/a.go", new byte[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(absolute, "v1.0.0"));
  }

  @Test
  void rejectsMalformedOrMismatchedGoModAndOversizedLicense() throws Exception {
    Path invalidUtf8 = zip(Map.of(
        "example.com/acme/demo@v1.0.0/go.mod", new byte[] {(byte) 0xc3, 0x28}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(invalidUtf8, "v1.0.0"));

    Path missingDirective = zip(Map.of(
        "example.com/acme/demo@v1.0.0/go.mod",
        "go 1.25\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(missingDirective, "v1.0.0"));

    Path mismatchedDirective = zip(Map.of(
        "example.com/acme/demo@v1.0.0/go.mod",
        "module example.com/acme/other\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector().inspect(mismatchedDirective, "v1.0.0"));

    Path license = zip(Map.of(
        "example.com/acme/demo@v1.0.0/LICENSE", new byte[] {1, 2, 3}));
    assertThrows(IllegalArgumentException.class,
        () -> new GoModuleArchiveInspector(1024 * 1024, 1024, 2, 10)
            .inspect(license, "v1.0.0"));
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
