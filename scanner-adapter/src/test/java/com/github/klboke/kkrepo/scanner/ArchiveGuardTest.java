package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        guard.inspect(archive, limits(10, 1024), temporary);

    assertThat(inspection.entries()).isEqualTo(1);
    assertThat(inspection.expandedBytes()).isEqualTo(2);
  }

  @Test
  void rejectsTraversalAndMixedSeparators() throws IOException {
    Path traversal = zip("traversal.zip", "../escape", new byte[] {1});
    Path mixed = zip("mixed.zip", "safe\\..\\escape", new byte[] {1});

    assertThatThrownBy(() -> guard.inspect(traversal, limits(10, 1024), temporary))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_PATH_ESCAPE");
    assertThatThrownBy(() -> guard.inspect(mixed, limits(10, 1024), temporary))
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

    assertThatThrownBy(() -> guard.inspect(tooMany, limits(1, 1024), temporary))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_ENTRY_LIMIT");
    assertThatThrownBy(() -> guard.inspect(tooLarge, limits(10, 64), temporary))
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

    assertThatThrownBy(() -> guard.inspect(compressed, limits, temporary))
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
}
