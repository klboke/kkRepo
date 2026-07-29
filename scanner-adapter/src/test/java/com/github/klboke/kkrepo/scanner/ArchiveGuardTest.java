package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveGuardTest {
  @TempDir Path temporary;

  private final ArchiveGuard guard = new ArchiveGuard();

  @Test
  void acceptsBoundedArchiveWithoutExtractingIt() throws IOException {
    Path archive = zip("safe.zip", "lib/package.json", "{}".getBytes());

    ArchiveGuard.Inspection inspection =
        guard.inspect(archive, limits(10, 1024), temporary, deadline());

    assertThat(inspection.entries()).isEqualTo(1);
    assertThat(inspection.expandedBytes()).isEqualTo(2);
  }

  @Test
  void rejectsTraversalAndMixedSeparators() throws IOException {
    Path traversal = zip("traversal.zip", "../escape", new byte[] {1});
    Path mixed = zip("mixed.zip", "safe\\..\\escape", new byte[] {1});

    assertThatThrownBy(
            () -> guard.inspect(traversal, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_PATH_ESCAPE");
    assertThatThrownBy(
            () -> guard.inspect(mixed, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_PATH_ESCAPE");
  }

  @Test
  void rejectsEntryAndExpandedSizeLimits() throws IOException {
    Path tooMany = temporary.resolve("many.zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(tooMany))) {
      output.putNextEntry(new ZipEntry("one"));
      output.write(1);
      output.closeEntry();
      output.putNextEntry(new ZipEntry("two"));
      output.write(2);
      output.closeEntry();
    }
    Path tooLarge = zip("large.zip", "large.bin", new byte[65]);

    assertThatThrownBy(
            () -> guard.inspect(tooMany, limits(1, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_ENTRY_LIMIT");
    assertThatThrownBy(
            () -> guard.inspect(tooLarge, limits(10, 64), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_ENTRY_TOO_LARGE");
  }

  @Test
  void rejectsUnsafeGlobalExpansionRatioForSingleCompressedFiles() throws IOException {
    Path compressed = temporary.resolve("bomb.gz");
    try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(compressed))) {
      output.write(new byte[2 * 1024 * 1024]);
    }
    ResourceLimits limits =
        new ResourceLimits(4 * 1024 * 1024, 10, 4 * 1024 * 1024, 4 * 1024 * 1024, 1, 30);

    assertThatThrownBy(() -> guard.inspect(compressed, limits, temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_EXPANSION_RATIO");
  }

  @Test
  void rejectsUnicodeAndAbsolutePathsBeforeFilesystemResolution() {
    assertThatThrownBy(() -> ArchiveGuard.validatePath("/etc/passwd"))
        .isInstanceOf(ScannerRequestException.class);
    assertThatThrownBy(() -> ArchiveGuard.validatePath("C:\\Windows\\system.ini"))
        .isInstanceOf(ScannerRequestException.class);
    assertThatThrownBy(() -> ArchiveGuard.validatePath("safe\u2215..\u2215escape"))
        .isInstanceOf(ScannerRequestException.class);
  }

  @Test
  void acceptsRawAndCompressedFilesAndRecursivelyInspectsNestedArchives()
      throws IOException {
    Path raw = temporary.resolve("plain.bin");
    Files.writeString(raw, "plain");
    assertThat(guard.inspect(raw, limits(10, 1024), temporary, deadline()))
        .isEqualTo(new ArchiveGuard.Inspection(0, 0, 0));

    Path gzip = temporary.resolve("plain.gz");
    try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(gzip))) {
      output.write("expanded".getBytes());
    }
    assertThat(
            guard.inspect(gzip, limits(10, 1024), temporary, deadline()).expandedBytes())
        .isEqualTo(8);

    ByteArrayOutputStream nestedBytes = new ByteArrayOutputStream();
    try (ZipOutputStream output = new ZipOutputStream(nestedBytes)) {
      output.putNextEntry(new ZipEntry("inside.txt"));
      output.write("nested".getBytes());
      output.closeEntry();
    }
    Path nested = zip("outer.zip", "nested.jar", nestedBytes.toByteArray());
    ArchiveGuard.Inspection inspection =
        guard.inspect(nested, limits(10, 1024), temporary, deadline());
    assertThat(inspection.entries()).isEqualTo(2);
    assertThat(inspection.nestedArchives()).isEqualTo(1);
    assertThatThrownBy(() -> guard.inspect(
            nested,
            new ResourceLimits(1024 * 1024, 10, 1024 * 1024, 1024, 0, 30),
            temporary,
            deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_NESTING_LIMIT");
  }

  @Test
  void rejectsArchiveLinksAndInvalidNames() throws IOException {
    Path tar = temporary.resolve("links.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(tar))) {
      TarArchiveEntry link = new TarArchiveEntry("link", TarConstants.LF_SYMLINK);
      link.setLinkName("../../target");
      output.putArchiveEntry(link);
      output.closeArchiveEntry();
    }
    assertThatThrownBy(
            () -> guard.inspect(tar, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_SPECIAL_FILE_REJECTED");

    for (String name : new String[] {
        "", "\0bad", "..", "safe/../escape", "safe\uff0f..\uff0fescape"
    }) {
      assertThatThrownBy(() -> ArchiveGuard.validatePath(name))
          .isInstanceOf(ScannerRequestException.class);
    }
    ArchiveGuard.validatePath("safe/path/file.jar");
  }

  @Test
  void stopsArchiveTraversalWhenTheSharedDeadlineExpires() throws IOException {
    Path archive = zip("deadline.zip", "entry.txt", "content".getBytes());
    AtomicLong clock = new AtomicLong();

    assertThatThrownBy(() -> guard.inspect(
            archive,
            limits(10, 1024),
            temporary,
            new ScanDeadline(Duration.ofNanos(5), clock::getAndIncrement)))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("SCANNER_TIMEOUT");
  }

  private Path zip(String fileName, String entryName, byte[] content) throws IOException {
    Path archive = temporary.resolve(fileName);
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry(entryName));
      output.write(content);
      output.closeEntry();
    }
    return archive;
  }

  private static ResourceLimits limits(int entries, long singleFile) {
    return new ResourceLimits(
        1024 * 1024,
        entries,
        1024 * 1024,
        singleFile,
        1,
        30);
  }

  private static ScanDeadline deadline() {
    return new ScanDeadline(30);
  }
}
