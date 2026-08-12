package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConanPackageCatalogerTest {
  @TempDir Path temporary;
  private final ConanPackageCataloger cataloger = new ConanPackageCataloger();

  @Test
  void stagesOnlyRegularFilesAndTheExternalCanonicalConanInfo() throws IOException {
    Path archive = archive(
        entry("bin/", new byte[0], EntryKind.DIRECTORY),
        entry("bin/demo", "payload".getBytes(StandardCharsets.UTF_8), EntryKind.FILE),
        entry("bin/link", new byte[0], EntryKind.SYMLINK));
    Path conanInfo = Files.writeString(
        temporary.resolve("conaninfo.txt"), "[settings]\nos=Linux\n");

    ConanPackageCataloger.Prepared prepared = cataloger.prepare(
        archive, conanInfo, limits(1024, 1024), temporary.resolve("work"),
        new ScanDeadline(30));

    assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(prepared.scanRoot().resolve("bin/demo")));
    assertFalse(Files.exists(prepared.scanRoot().resolve("bin/link")));
    assertEquals(Files.size(conanInfo), prepared.conanInfoBytes());
    assertEquals("[settings]\nos=Linux\n",
        Files.readString(prepared.scanRoot().resolve("conaninfo.txt")));
  }

  @Test
  void rejectsUnsupportedEscapingAndDuplicateArchiveEntries() throws IOException {
    Path unsupported = Files.writeString(temporary.resolve("plain.bin"), "not compressed");
    assertCode("CONAN_ARCHIVE_INVALID", () -> prepare(unsupported, "valid", "unsupported"));

    assertCode("CONAN_ARCHIVE_INVALID", () -> prepare(
        archive(entry("../escape", new byte[] {1}, EntryKind.FILE)), "valid", "escape"));
    assertCode("CONAN_ARCHIVE_INVALID", () -> prepare(
        archive(
            entry("same", new byte[] {1}, EntryKind.FILE),
            entry("same", new byte[] {2}, EntryKind.FILE)),
        "valid", "duplicate"));
  }

  @Test
  void enforcesPerFileAndAggregateExpansionBudgets() throws IOException {
    Path one = archive(entry("large", new byte[] {1, 2, 3, 4}, EntryKind.FILE));
    assertCode("CONAN_ARCHIVE_LIMIT", () -> cataloger.prepare(
        one, info("one"), limits(3, 100), temporary.resolve("one-work"),
        new ScanDeadline(30)));

    Path aggregate = archive(
        entry("one", new byte[] {1, 2, 3}, EntryKind.FILE),
        entry("two", new byte[] {4, 5, 6}, EntryKind.FILE));
    assertCode("CONAN_ARCHIVE_LIMIT", () -> cataloger.prepare(
        aggregate, info("aggregate"), limits(4, 5), temporary.resolve("aggregate-work"),
        new ScanDeadline(30)));
  }

  @Test
  void rejectsEmbeddedDuplicateAndMalformedConanInfo() throws IOException {
    Path embedded = archive(entry(
        "conaninfo.txt", "inside".getBytes(StandardCharsets.UTF_8), EntryKind.FILE));
    assertCode("CONAN_INFO_DUPLICATE", () -> prepare(embedded, "external", "embedded"));

    Path invalidUtf8 = Files.write(
        temporary.resolve("invalid-utf8.txt"), new byte[] {(byte) 0xc3, 0x28});
    assertCode("CONAN_INFO_INVALID", () -> cataloger.prepare(
        archive(entry("file", new byte[] {1}, EntryKind.FILE)), invalidUtf8,
        limits(1024, 1024), temporary.resolve("utf8-work"), new ScanDeadline(30)));

    Path nul = Files.write(
        temporary.resolve("nul.txt"), new byte[] {'a', 0, 'b'});
    assertCode("CONAN_INFO_INVALID", () -> cataloger.prepare(
        archive(entry("file", new byte[] {1}, EntryKind.FILE)), nul,
        limits(1024, 1024), temporary.resolve("nul-work"), new ScanDeadline(30)));

    String tooManyLines = "x\n".repeat(16_385);
    Path lines = Files.writeString(temporary.resolve("lines.txt"), tooManyLines);
    assertCode("CONAN_INFO_INVALID", () -> cataloger.prepare(
        archive(entry("file", new byte[] {1}, EntryKind.FILE)), lines,
        limits(1024, 1024), temporary.resolve("lines-work"), new ScanDeadline(30)));
  }

  @Test
  void mapsFilesystemFailuresToRetryableStagingError() throws IOException {
    Path workspaceFile = Files.writeString(temporary.resolve("not-a-directory"), "x");
    ScannerRequestException workspaceFailure = assertCode(
        "CONAN_STAGE_IO",
        () -> cataloger.prepare(
            archive(entry("file", new byte[] {1}, EntryKind.FILE)), info("workspace"),
            limits(1024, 1024), workspaceFile, new ScanDeadline(30)));
    assertEquals(503, workspaceFailure.status());
    assertTrue(workspaceFailure.retryable());

    assertCode("CONAN_STAGE_IO", () -> cataloger.prepare(
        temporary.resolve("missing.tgz"), info("missing"), limits(1024, 1024),
        temporary.resolve("missing-work"), new ScanDeadline(30)));
  }

  private ConanPackageCataloger.Prepared prepare(
      Path archive, String conanInfo, String workspace) {
    try {
      return cataloger.prepare(
          archive, info(workspace + "-info", conanInfo), limits(1024, 1024),
          temporary.resolve(workspace), new ScanDeadline(30));
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }

  private Path info(String name) throws IOException {
    return info(name, "[settings]\nos=Linux\n");
  }

  private Path info(String name, String value) throws IOException {
    return Files.writeString(temporary.resolve(name + ".txt"), value);
  }

  private Path archive(Entry... entries) throws IOException {
    Path output = Files.createTempFile(temporary, "conan-", ".tgz");
    try (OutputStream file = Files.newOutputStream(output);
        GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Entry value : entries) {
        TarArchiveEntry entry = switch (value.kind()) {
          case FILE -> new TarArchiveEntry(value.name());
          case DIRECTORY -> new TarArchiveEntry(value.name().endsWith("/")
              ? value.name() : value.name() + "/");
          case SYMLINK -> new TarArchiveEntry(value.name(), TarConstants.LF_SYMLINK);
          case FIFO -> new TarArchiveEntry(value.name(), TarConstants.LF_FIFO);
        };
        if (value.kind() == EntryKind.SYMLINK) entry.setLinkName("demo");
        entry.setSize(value.kind() == EntryKind.FILE ? value.bytes().length : 0);
        tar.putArchiveEntry(entry);
        if (value.kind() == EntryKind.FILE) tar.write(value.bytes());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return output;
  }

  private static Entry entry(String name, byte[] bytes, EntryKind kind) {
    return new Entry(name, bytes, kind);
  }

  private static ResourceLimits limits(long singleFile, long expanded) {
    return new ResourceLimits(1024 * 1024, 100, expanded, singleFile, 2, 30);
  }

  private static ScannerRequestException assertCode(String code, ThrowingRunnable invocation) {
    ScannerRequestException failure = assertThrows(ScannerRequestException.class, invocation::run);
    assertEquals(code, failure.code());
    return failure;
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private enum EntryKind { FILE, DIRECTORY, SYMLINK, FIFO }

  private record Entry(String name, byte[] bytes, EntryKind kind) {}
}
