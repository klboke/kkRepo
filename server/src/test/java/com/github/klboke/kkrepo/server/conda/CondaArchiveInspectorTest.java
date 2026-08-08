package com.github.klboke.kkrepo.server.conda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  @Test
  void rejectsInvalidRequestEnvelopeAndBufferFailures() throws Exception {
    byte[] archive = CondaTestArchive.legacy("demo", "1.0", "0", 0, "linux-64");
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(archive), null, "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(archive), "demo.zip", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(archive), "demo-1.0-0.tar.bz2", "../linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        null, "demo-1.0-0.tar.bz2", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(new byte[0]), "demo-1.0-0.tar.bz2", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream("not-bzip".getBytes(StandardCharsets.UTF_8)),
        "demo-1.0-0.tar.bz2", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream("not-zip".getBytes(StandardCharsets.UTF_8)),
        "demo-1.0-0.conda", "linux-64"));

    CondaArchiveInspector tiny = new CondaArchiveInspector(
        new ObjectMapper(), 1, 1024, 10, 10, 1, 10);
    assertThrows(MavenExceptions.BadRequestException.class, () -> tiny.inspect(
        new ByteArrayInputStream(archive), "demo-1.0-0.tar.bz2", "linux-64"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read failed");
          }
        }, "demo-1.0-0.tar.bz2", "linux-64"));
  }

  @Test
  void boundsConcurrentInspectionAndPreservesInterrupts() throws Exception {
    CondaArchiveInspector single = new CondaArchiveInspector(
        new ObjectMapper(), 1024, 1024, 10, 10, 1, 1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> holder = executor.submit(() -> assertThrows(
          MavenExceptions.BadRequestException.class,
          () -> single.inspect(new BlockingInputStream(entered, release),
              "demo-1.0-0.tar.bz2", "linux-64")));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertThrows(MavenExceptions.WritePolicyDenied.class, () -> single.inspect(
          new ByteArrayInputStream(new byte[] {1}), "demo-1.0-0.tar.bz2", "linux-64"));
      release.countDown();
      holder.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }

    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class, () -> single.inspect(
          new ByteArrayInputStream(new byte[] {1}), "demo-1.0-0.tar.bz2", "linux-64"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void validatesLegacyIndexSchemaFieldsAndArchiveStructure() throws Exception {
    for (String invalid : List.of(
        "{",
        "{\"schema_version\":1.5,\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"bad/name\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"demo\",\"version\":\"1+bad\",\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"demo\",\"version\":\"!\",\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":-1}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0.5}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0,\"depends\":1}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0,\"constrains\":[\"\"]}")) {
      byte[] archive = CondaTestArchive.legacy(
          invalid.getBytes(StandardCharsets.UTF_8), List.of());
      assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
          new ByteArrayInputStream(archive), "demo-1-0.tar.bz2", "linux-64"), invalid);
    }

    byte[] index = CondaTestArchive.index("demo", "1", "0", 0, "linux-64");
    for (CondaTestArchive.TarItem invalid : List.of(
        CondaTestArchive.TarItem.special("device"),
        CondaTestArchive.TarItem.file("C:/escape", new byte[] {1}),
        CondaTestArchive.TarItem.symlink("bin/demo", "/outside"))) {
      byte[] archive = CondaTestArchive.legacy(index, List.of(invalid));
      assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
          new ByteArrayInputStream(archive), "demo-1-0.tar.bz2", "linux-64"), invalid.name());
    }

    byte[] duplicate = CondaTestArchive.legacy(index, List.of(
        CondaTestArchive.TarItem.file("info/index.json", index)));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(duplicate), "demo-1-0.tar.bz2", "linux-64"));

    byte[] oversized = CondaTestArchive.legacy(
        new byte[2 * 1024 * 1024 + 1], List.of());
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(oversized), "demo-1-0.tar.bz2", "linux-64"));

    byte[] expanded = CondaTestArchive.legacy(index, List.of(
        CondaTestArchive.TarItem.file("share/data", new byte[1024])));
    CondaArchiveInspector bounded = new CondaArchiveInspector(
        new ObjectMapper(), expanded.length + 1L, 16, 100, 10, 1, 10);
    assertThrows(MavenExceptions.BadRequestException.class, () -> bounded.inspect(
        new ByteArrayInputStream(expanded), "demo-1-0.tar.bz2", "linux-64"));
  }

  @Test
  void validatesModernMetadataNamesAndOuterEntries() throws Exception {
    String identity = "demo-1.0-0";
    byte[] index = CondaTestArchive.index("demo", "1.0", "0", 0, "linux-64");
    byte[] info = CondaTestArchive.zstdTar(List.of(
        CondaTestArchive.TarItem.file("info/index.json", index)));
    byte[] payload = CondaTestArchive.zstdTar(List.of(
        CondaTestArchive.TarItem.file("bin/demo", new byte[] {1})));
    byte[] validMetadata = "{\"conda_pkg_format_version\":2}"
        .getBytes(StandardCharsets.UTF_8);

    List<List<CondaTestArchive.ZipItem>> invalidContainers = new ArrayList<>();
    invalidContainers.add(List.of(
        CondaTestArchive.ZipItem.stored("metadata.json", validMetadata),
        CondaTestArchive.ZipItem.stored("info-" + identity + ".tar.zst", info),
        CondaTestArchive.ZipItem.stored("pkg-" + identity + ".tar.zst", payload),
        CondaTestArchive.ZipItem.stored("unsupported", new byte[] {1})));
    invalidContainers.add(List.of(
        CondaTestArchive.ZipItem.stored("/metadata.json", validMetadata),
        CondaTestArchive.ZipItem.stored("info-" + identity + ".tar.zst", info),
        CondaTestArchive.ZipItem.stored("pkg-" + identity + ".tar.zst", payload)));
    invalidContainers.add(List.of(
        CondaTestArchive.ZipItem.stored("metadata.json", new byte[64 * 1024 + 1]),
        CondaTestArchive.ZipItem.stored("info-" + identity + ".tar.zst", info),
        CondaTestArchive.ZipItem.stored("pkg-" + identity + ".tar.zst", payload)));
    invalidContainers.add(List.of(
        CondaTestArchive.ZipItem.stored("metadata.json", "{\"conda_pkg_format_version\":1}"
            .getBytes(StandardCharsets.UTF_8)),
        CondaTestArchive.ZipItem.stored("info-" + identity + ".tar.zst", info),
        CondaTestArchive.ZipItem.stored("pkg-" + identity + ".tar.zst", payload)));
    invalidContainers.add(List.of(
        CondaTestArchive.ZipItem.stored("metadata.json", "{".getBytes(StandardCharsets.UTF_8)),
        CondaTestArchive.ZipItem.stored("info-" + identity + ".tar.zst", info),
        CondaTestArchive.ZipItem.stored("pkg-" + identity + ".tar.zst", payload)));
    invalidContainers.add(List.of(
        CondaTestArchive.ZipItem.stored("metadata.json", validMetadata),
        CondaTestArchive.ZipItem.stored("info-.tar.zst", info),
        CondaTestArchive.ZipItem.stored("pkg-.tar.zst", payload)));

    for (List<CondaTestArchive.ZipItem> entries : invalidContainers) {
      byte[] archive = CondaTestArchive.zip(entries);
      assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
          new ByteArrayInputStream(archive), identity + ".conda", "linux-64"));
    }
  }

  @Test
  void deleteIsNullSafeAndBestEffort() throws Exception {
    CondaArchiveInspector.delete(null);
    Path directory = Files.createTempDirectory("conda-delete-test-");
    Path child = Files.writeString(directory.resolve("child"), "content");
    CondaArchiveInspector.delete(directory);
    assertTrue(Files.exists(directory));
    Files.delete(child);
    Files.delete(directory);
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

  private static final class BlockingInputStream extends InputStream {
    private final CountDownLatch entered;
    private final CountDownLatch release;
    private boolean waited;

    private BlockingInputStream(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public int read() throws IOException {
      if (!waited) {
        waited = true;
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException(e);
        }
      }
      return -1;
    }
  }
}
