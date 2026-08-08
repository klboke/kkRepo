package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class CondaArchiveInspectorTest {
  private final CondaArchiveInspector inspector = new CondaArchiveInspector(new ObjectMapper());

  @Test
  void acceptsLegacyAndV2PackagesAndDerivesCanonicalMetadata() throws Exception {
    byte[] legacy = CondaTestArchive.legacy("demo", "1.2.3", "py312_0", 0, "linux-64");
    assertPackage(
        inspector.inspect(
            new ByteArrayInputStream(legacy), "demo-1.2.3-py312_0.tar.bz2", "linux-64"),
        "tar.bz2", legacy.length);

    byte[] modern = CondaTestArchive.modern("demo", "1.2.3", "py312_0", 0, "linux-64");
    assertPackage(
        inspector.inspect(
            new ByteArrayInputStream(modern), "demo-1.2.3-py312_0.conda", "linux-64"),
        "conda", modern.length);
  }

  @Test
  void acceptsSafePackageLinksAndRejectsArchiveEscapes() throws Exception {
    byte[] index = CondaTestArchive.index("demo", "1.0", "0", 0, "linux-64");
    byte[] safe = CondaTestArchive.legacy(index, List.of(
        CondaTestArchive.TarItem.file("lib/demo", new byte[] {1}),
        CondaTestArchive.TarItem.symlink("bin/demo", "../lib/demo"),
        CondaTestArchive.TarItem.hardlink("bin/demo-hard", "lib/demo")));
    CondaArchiveInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(safe), "demo-1.0-0.tar.bz2", "linux-64");
    Files.deleteIfExists(inspected.file());

    byte[] escape = CondaTestArchive.legacy(index, List.of(
        CondaTestArchive.TarItem.symlink("bin/demo", "../../outside")));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(escape), "demo-1.0-0.tar.bz2", "linux-64"));
  }

  @Test
  void rejectsFilenameSubdirAndTraversalMismatches() throws Exception {
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(archive), "other-1.0-0.tar.bz2", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(archive), "demo-1.0-0.tar.bz2", "osx-64"));

    byte[] index = CondaTestArchive.index("demo", "1.0", "0", 0, "linux-64");
    byte[] traversal = CondaTestArchive.legacy(index, List.of(
        CondaTestArchive.TarItem.file("../escape", new byte[] {1})));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(traversal), "demo-1.0-0.tar.bz2", "linux-64"));
  }

  @Test
  void rejectsPackageIdentifiersOutsideCep26() throws Exception {
    byte[] invalidName = CondaTestArchive.legacy(
        CondaTestArchive.index("demo--gpu", "1.0", "0", 0, "linux-64"), List.of());
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(invalidName), "demo--gpu-1.0-0.tar.bz2", "linux-64"));

    byte[] invalidBuild = CondaTestArchive.legacy(
        CondaTestArchive.index("demo", "1.0", "py-0", 0, "linux-64"), List.of());
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(invalidBuild), "demo-1.0-py-0.tar.bz2", "linux-64"));
  }

  @Test
  void requiresPathsMetadataForSchemaV2ButKeepsLegacyV1Compatible() throws Exception {
    byte[] v2Index = """
        {"schema_version":2,"name":"demo","version":"1.0","build":"0",
         "build_number":0,"subdir":"linux-64","depends":[],"constrains":[]}
        """.getBytes(StandardCharsets.UTF_8);
    byte[] missingPaths = CondaTestArchive.legacy(v2Index, List.of());
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(missingPaths), "demo-1.0-0.tar.bz2", "linux-64"));

    byte[] complete = CondaTestArchive.legacy(v2Index, List.of(
        CondaTestArchive.TarItem.file(
            "info/paths.json",
            "{\"paths_version\":1,\"paths\":[]}".getBytes(StandardCharsets.UTF_8))));
    CondaArchiveInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(complete), "demo-1.0-0.tar.bz2", "linux-64");
    Files.deleteIfExists(inspected.file());

    byte[] schemaV3 = v2Index.clone();
    schemaV3 = new String(schemaV3, StandardCharsets.UTF_8)
        .replace("\"schema_version\":2", "\"schema_version\":3")
        .getBytes(StandardCharsets.UTF_8);
    byte[] unsupported = CondaTestArchive.legacy(schemaV3, List.of(
        CondaTestArchive.TarItem.file(
            "info/paths.json",
            "{\"paths_version\":1,\"paths\":[]}".getBytes(StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(unsupported), "demo-1.0-0.tar.bz2", "linux-64"));
  }

  @Test
  void enforcesOfficialV2StoredContainerAndMatchingPayloadNames() throws Exception {
    byte[] index = CondaTestArchive.index("demo", "1.0", "0", 0, "linux-64");
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(CondaTestArchive.modern(
            "demo-1.0-0", "other-1.0-0", true, true, index)),
        "demo-1.0-0.conda", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(CondaTestArchive.modern(
            "other-1.0-0", "other-1.0-0", true, true, index)),
        "demo-1.0-0.conda", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(CondaTestArchive.modern(
            "demo-1.0-0", "demo-1.0-0", false, true, index)),
        "demo-1.0-0.conda", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(CondaTestArchive.modern(
            "demo-1.0-0", "demo-1.0-0", true, false, index)),
        "demo-1.0-0.conda", "linux-64"));
  }

  @Test
  void boundsExpandedInfoBytesWhileTarEntriesAreSkipped() throws Exception {
    byte[] index = CondaTestArchive.index("demo", "1.0", "0", 0, "linux-64");
    byte[] archive = CondaTestArchive.legacy(index, List.of(
        CondaTestArchive.TarItem.file("share/large", new byte[128 * 1024])));
    CondaArchiveInspector bounded = new CondaArchiveInspector(
        new ObjectMapper(), archive.length + 1L, 4096, 100, 120, 1, 1000);

    assertThrows(MavenExceptions.BadRequestException.class, () -> bounded.inspect(
        new ByteArrayInputStream(archive), "demo-1.0-0.tar.bz2", "linux-64"));
  }

  private static void assertPackage(
      CondaArchiveInspector.InspectedPackage inspected, String format, long expectedSize)
      throws Exception {
    try {
      assertEquals("demo", inspected.name());
      assertEquals("1.2.3", inspected.version());
      assertEquals("py312_0", inspected.build());
      assertEquals(0, inspected.buildNumber());
      assertEquals(format, inspected.archiveFormat());
      assertEquals(expectedSize, inspected.size());
      assertEquals(32, inspected.md5().length());
      assertEquals(64, inspected.sha256().length());
      assertEquals("linux-64", inspected.metadata().get("subdir"));
      assertFalse(inspected.metadata().containsKey("base_url"));
      assertFalse(inspected.metadata().containsKey("download_url"));
      assertTrue(Files.size(inspected.file()) > 0);
    } finally {
      Files.deleteIfExists(inspected.file());
    }
  }
}
