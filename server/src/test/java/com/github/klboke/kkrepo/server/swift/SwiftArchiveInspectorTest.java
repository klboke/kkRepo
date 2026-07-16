package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SwiftArchiveInspectorTest {

  @Test
  void inspectsPackageRootAndVersionedManifestsWithoutExecutingSource() throws Exception {
    byte[] defaultManifest = manifest("5.7");
    byte[] versionedManifest = manifest("5.9.1");
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("fixture/Package.swift", defaultManifest);
    entries.put("fixture/Package@swift-5.9.swift", versionedManifest);
    entries.put("fixture/Sources/Fixture/Fixture.swift",
        "public struct Fixture {}".getBytes(StandardCharsets.UTF_8));
    byte[] archive = zip(entries);
    SwiftArchiveInspector inspector = new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20);

    SwiftArchiveInspector.InspectedArchive inspected =
        inspector.inspect(new ByteArrayInputStream(archive));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(archive);
      assertEquals(archive.length, inspected.size());
      assertEquals(HexFormat.of().formatHex(digest), inspected.sha256Hex());
      assertEquals(Base64.getEncoder().encodeToString(digest), inspected.sha256Base64());
      assertEquals(2, inspected.manifests().size());

      SwiftArchiveInspector.ManifestEntry defaultEntry = inspected.manifests().get(0);
      assertEquals("Package.swift", defaultEntry.filename());
      assertEquals("", defaultEntry.lookupToolsVersion());
      assertEquals("5.7", defaultEntry.declaredToolsVersion());
      assertEquals(new String(defaultManifest, StandardCharsets.UTF_8),
          new String(defaultEntry.bytes(), StandardCharsets.UTF_8));

      SwiftArchiveInspector.ManifestEntry versionedEntry = inspected.manifests().get(1);
      assertEquals("Package@swift-5.9.swift", versionedEntry.filename());
      assertEquals("5.9", versionedEntry.lookupToolsVersion());
      assertEquals("5.9.1", versionedEntry.declaredToolsVersion());
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  @Test
  void requiresPackageManifestAtTheSoleTopLevelDirectoryRoot() throws Exception {
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("outer/nested/Package.swift", manifest("5.6"));
    entries.put("outer/Package.swift", manifest("5.9"));
    entries.put("outer/Package@swift-6.0.swift", manifest("6.0"));
    SwiftArchiveInspector inspector = new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20);

    SwiftArchiveInspector.InspectedArchive inspected =
        inspector.inspect(new ByteArrayInputStream(zip(entries)));
    try {
      assertEquals(2, inspected.manifests().size());
      assertTrue(inspected.manifests().stream()
          .allMatch(manifest -> !"5.6".equals(manifest.declaredToolsVersion())));
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  @Test
  void rejectsRootlessMultipleRootAndNestedOnlyPackageLayouts() throws Exception {
    SwiftArchiveInspector inspector = new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20);

    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(Map.of(
            "Package.swift", manifest("5.9"))))));

    LinkedHashMap<String, byte[]> multipleRoots = new LinkedHashMap<>();
    multipleRoots.put("first/Package.swift", manifest("5.9"));
    multipleRoots.put("second/README.md", "second root".getBytes(StandardCharsets.UTF_8));
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(multipleRoots))));

    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(Map.of(
            "fixture/nested/Package.swift", manifest("5.9"),
            "fixture/README.md", "nested manifest".getBytes(StandardCharsets.UTF_8))))));
  }

  @Test
  void rejectsUnsafeDuplicateAndMalformedArchives() throws Exception {
    SwiftArchiveInspector inspector = new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20);

    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream("not-a-zip".getBytes(StandardCharsets.UTF_8))));
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(Map.of(
            "fixture/README.md", "missing manifest".getBytes(StandardCharsets.UTF_8))))));
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(Map.of(
            "../Package.swift", manifest("5.9"))))));

    LinkedHashMap<String, byte[]> caseConflict = new LinkedHashMap<>();
    caseConflict.put("fixture/Package.swift", manifest("5.9"));
    caseConflict.put("FIXTURE/package.SWIFT", manifest("5.9"));
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(caseConflict))));
  }

  @Test
  void enforcesUploadAndManifestValidationLimits() throws Exception {
    byte[] archive = zip(Map.of("fixture/Package.swift", manifest("5.9")));
    SwiftArchiveInspector tooSmall = new SwiftArchiveInspector(archive.length - 1L, 1024 * 1024, 20);
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> tooSmall.inspect(new ByteArrayInputStream(archive)));

    SwiftArchiveInspector oneEntry = new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 1);
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> oneEntry.inspect(new ByteArrayInputStream(zip(Map.of(
            "fixture/Package.swift", manifest("5.9"),
            "fixture/README.md", new byte[] {1})))));

    SwiftArchiveInspector inspector = new SwiftArchiveInspector(1024 * 1024, 1024 * 1024, 20);
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(Map.of(
            "fixture/Package.swift", "import PackageDescription\n".getBytes(StandardCharsets.UTF_8))))));
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(Map.of(
            "fixture/Package.swift", manifest("5.9"),
            "fixture/Package@swift-6.0.swift", manifest("5.9"))))));
  }

  @Test
  void enforcesSingleEntryLimitAndRejectsFileDirectoryPrefixConflicts() throws Exception {
    LinkedHashMap<String, byte[]> largeEntry = new LinkedHashMap<>();
    largeEntry.put("fixture/Package.swift", manifest("5.9"));
    largeEntry.put("fixture/Sources/Large.swift", new byte[512]);
    SwiftArchiveInspector bounded = new SwiftArchiveInspector(
        1024 * 1024, 1024 * 1024, 20, 256);
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> bounded.inspect(new ByteArrayInputStream(zip(largeEntry))));

    LinkedHashMap<String, byte[]> conflict = new LinkedHashMap<>();
    conflict.put("fixture/Package.swift", manifest("5.9"));
    conflict.put("fixture/Sources", "not a directory".getBytes(StandardCharsets.UTF_8));
    conflict.put("fixture/sources/Fixture.swift", new byte[] {1});
    SwiftArchiveInspector inspector = new SwiftArchiveInspector(
        1024 * 1024, 1024 * 1024, 20);
    assertThrows(SwiftExceptions.UnprocessableEntity.class,
        () -> inspector.inspect(new ByteArrayInputStream(zip(conflict))));
  }

  @Test
  void boundsRetainedManifestCountAndAggregateBytesBeforePackageRootSelection() throws Exception {
    LinkedHashMap<String, byte[]> manyManifests = new LinkedHashMap<>();
    manyManifests.put("fixture/Package.swift", manifest("5.9"));
    manyManifests.put("fixture/nested-a/Package.swift", manifest("5.9"));
    manyManifests.put("fixture/nested-b/Package.swift", manifest("5.9"));
    SwiftArchiveInspector countBounded = new SwiftArchiveInspector(
        1024 * 1024, 1024 * 1024, 20, 1024 * 1024, 2, 1024 * 1024);
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> countBounded.inspect(new ByteArrayInputStream(zip(manyManifests))));

    LinkedHashMap<String, byte[]> aggregateManifests = new LinkedHashMap<>();
    aggregateManifests.put("fixture/Package.swift", manifest("5.9"));
    aggregateManifests.put("fixture/nested/Package.swift", manifest("5.9"));
    SwiftArchiveInspector byteBounded = new SwiftArchiveInspector(
        1024 * 1024, 1024 * 1024, 20, 1024 * 1024, 10,
        manifest("5.9").length + 1L);
    assertThrows(SwiftExceptions.ContentTooLarge.class,
        () -> byteBounded.inspect(new ByteArrayInputStream(zip(aggregateManifests))));
  }

  @Test
  void acceptsSemverBuildMetadataInTheTopLevelDirectoryName() throws Exception {
    LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("fixture-1.0.0-beta.1+build.5/Package.swift", manifest("5.9"));
    entries.put("fixture-1.0.0-beta.1+build.5/README.md", new byte[] {1});
    SwiftArchiveInspector inspector = new SwiftArchiveInspector(
        1024 * 1024, 1024 * 1024, 20);

    SwiftArchiveInspector.InspectedArchive inspected =
        inspector.inspect(new ByteArrayInputStream(zip(entries)));
    try {
      assertEquals(1, inspected.manifests().size());
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }

  private static byte[] manifest(String toolsVersion) {
    return ("// swift-tools-version: " + toolsVersion + "\n"
        + "import PackageDescription\n"
        + "let package = Package(name: \"Fixture\")\n").getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] zip(Map<String, byte[]> entries) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Map.Entry<String, byte[]> item : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(item.getKey()));
        zip.write(item.getValue());
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
