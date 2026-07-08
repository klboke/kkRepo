package com.github.klboke.kkrepo.server.pub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.BlobObjectMetadata;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PubArchiveInspectorTest {
  @TempDir
  Path tempDir;

  @Test
  void extractsPackageMetadataFromNestedPubspec() throws Exception {
    Path archive = writeArchive(entry("example_package-1.2.3/pubspec.yaml", """
        name: example_package
        version: 1.2.3
        environment:
          sdk: ^3.0.0
        """));

    PubPackageMetadata metadata = PubArchiveInspector.inspect(archive);

    assertEquals("example_package", metadata.packageName());
    assertEquals("1.2.3", metadata.version());
    assertEquals("^3.0.0", ((java.util.Map<?, ?>) metadata.pubspec().get("environment")).get("sdk"));
  }

  @Test
  void acceptsNexusStyleArchiveRootDirectoryEntry() throws Exception {
    Path archive = writeArchive(
        directory("./"),
        entry("./pubspec.yaml", "name: example_package\nversion: 1.0.0\n"),
        directory("./lib/"),
        entry("./lib/example_package.dart", "String message() => 'ok';\n"));

    PubPackageMetadata metadata = PubArchiveInspector.inspect(archive);

    assertEquals("example_package", metadata.packageName());
    assertEquals("1.0.0", metadata.version());
  }

  @Test
  void rejectsUnsafeTarEntryPaths() throws Exception {
    Path archive = writeArchive(
        entry("../evil.txt", "nope"),
        entry("pubspec.yaml", "name: example_package\nversion: 1.0.0\n"));

    PubExceptions.BadRequestException thrown =
        assertThrows(PubExceptions.BadRequestException.class, () -> PubArchiveInspector.inspect(archive));

    assertTrue(thrown.getMessage().contains("unsafe path"));
  }

  @Test
  void rejectsParentDirectorySegmentAtEndOfEntryPath() throws Exception {
    Path archive = writeArchive(
        entry("nested/..", "nope"),
        entry("pubspec.yaml", "name: example_package\nversion: 1.0.0\n"));

    PubExceptions.BadRequestException thrown =
        assertThrows(PubExceptions.BadRequestException.class, () -> PubArchiveInspector.inspect(archive));

    assertTrue(thrown.getMessage().contains("unsafe path"));
  }

  @Test
  void ignoresNestedExamplePubspecFilesWhenRootPubspecExists() throws Exception {
    Path archive = writeArchive(
        entry("pubspec.yaml", "name: example_package\nversion: 1.0.0\n"),
        entry("example/pubspec.yaml", "name: example_app\nversion: 1.0.0\n"),
        entry("example/deeper/pubspec.yaml", "name: deeper_app\nversion: 1.0.0\n"));

    PubPackageMetadata metadata = PubArchiveInspector.inspect(archive);

    assertEquals("example_package", metadata.packageName());
  }

  @Test
  void rejectsMultipleFirstLevelPubspecFilesWhenRootPubspecIsMissing() throws Exception {
    Path archive = writeArchive(
        entry("package-a/pubspec.yaml", "name: package_a\nversion: 1.0.0\n"),
        entry("package-b/pubspec.yaml", "name: package_b\nversion: 1.0.0\n"));

    PubExceptions.BadRequestException thrown =
        assertThrows(PubExceptions.BadRequestException.class, () -> PubArchiveInspector.inspect(archive));

    assertTrue(thrown.getMessage().contains("multiple pubspec.yaml"));
  }

  @Test
  void stagedArchiveValidatesExpectedCoordinatesAndUsesOriginalArchiveSha256() throws Exception {
    byte[] archive = archiveBytes(entry("pubspec.yaml", "name: example_package\nversion: 1.0.0\n"));
    RecordingBlobStorage storage = new RecordingBlobStorage();
    PubAssetWriter writer = new PubAssetWriter(null, null, null, null, null);

    PubAssetWriter.StagedArchive staged = writer.stageArchive(
        runtime(), storage, "session-1", new ByteArrayInputStream(archive), "example_package", "1.0.0");

    assertEquals("example_package", staged.metadata().packageName());
    assertEquals("1.0.0", staged.metadata().version());
    assertEquals(staged.digests().sha256(), storage.reference.sha256());
    assertEquals(archive.length, staged.digests().size());
  }

  @Test
  void stagedArchiveRejectsRequestedPackageMismatchBeforeUploadingBlob() throws Exception {
    byte[] archive = archiveBytes(entry("pubspec.yaml", "name: example_package\nversion: 1.0.0\n"));
    RecordingBlobStorage storage = new RecordingBlobStorage();
    PubAssetWriter writer = new PubAssetWriter(null, null, null, null, null);

    PubExceptions.BadRequestException thrown = assertThrows(
        PubExceptions.BadRequestException.class,
        () -> writer.stageArchive(runtime(), storage, "session-1",
            new ByteArrayInputStream(archive), "other_package", "1.0.0"));

    assertTrue(thrown.getMessage().contains("package name does not match"));
    assertEquals(0, storage.puts);
  }

  private Path writeArchive(ArchiveEntry... entries) throws IOException {
    Path archive = tempDir.resolve("package.tar.gz");
    Files.write(archive, archiveBytes(entries));
    return archive;
  }

  private static byte[] archiveBytes(ArchiveEntry... entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      for (ArchiveEntry entry : entries) {
        TarArchiveEntry tarEntry = new TarArchiveEntry(entry.name());
        if (entry.directory()) {
          tarEntry.setSize(0);
          tarEntry.setMode(0755);
        } else {
          byte[] bytes = entry.body().getBytes(StandardCharsets.UTF_8);
          tarEntry.setSize(bytes.length);
        }
        tar.putArchiveEntry(tarEntry);
        if (!entry.directory()) {
          tar.write(entry.body().getBytes(StandardCharsets.UTF_8));
        }
        tar.closeArchiveEntry();
      }
    }
    return out.toByteArray();
  }

  private static ArchiveEntry entry(String name, String body) {
    return new ArchiveEntry(name, body, false);
  }

  private static ArchiveEntry directory(String name) {
    return new ArchiveEntry(name, "", true);
  }

  private static RepositoryRuntime runtime() {
    return new RepositoryRuntime(
        1L,
        "pub-hosted",
        RepositoryFormat.PUB,
        RepositoryType.HOSTED,
        "pub-hosted",
        true,
        1L,
        "ALLOW_ONCE",
        null,
        null,
        true,
        null,
        null,
        null,
        List.of());
  }

  private record ArchiveEntry(String name, String body, boolean directory) {
  }

  private static final class RecordingBlobStorage implements BlobStorage {
    private int puts;
    private BlobReference reference;

    @Override
    public BlobReference put(String repository, String logicalPath, InputStream content, long size, String sha256) {
      puts++;
      reference = new BlobReference(repository, logicalPath, sha256, size);
      return reference;
    }

    @Override
    public Optional<InputStream> get(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public boolean exists(BlobReference reference) {
      return false;
    }

    @Override
    public Optional<BlobObjectMetadata> stat(BlobReference reference) {
      return Optional.empty();
    }

    @Override
    public void delete(BlobReference reference) {
    }
  }
}
