package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
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
  void recognizesEveryScannerSupportedNestedArchiveSuffix() throws IOException {
    byte[] tarZstd = zstdTar("inside-zstd.txt", "zstd".getBytes());
    byte[] tarXz = xzTar("inside-xz.txt", "xz".getBytes());
    byte[] gem = tar("inside-gem.txt", "gem".getBytes());
    Path outer = temporary.resolve("supported-nested.zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(outer))) {
      output.putNextEntry(new ZipEntry("packages/dependency.tar.zst"));
      output.write(tarZstd);
      output.closeEntry();
      output.putNextEntry(new ZipEntry("packages/dependency.gem"));
      output.write(gem);
      output.closeEntry();
      output.putNextEntry(new ZipEntry("packages/dependency.xz"));
      output.write(tarXz);
      output.closeEntry();
    }

    ArchiveGuard.Inspection inspection =
        guard.inspect(outer, limits(10, 16 * 1024), temporary, deadline());

    assertThat(inspection.entries()).isEqualTo(6);
    assertThat(inspection.nestedArchives()).isEqualTo(3);
  }

  @Test
  void unwrapsRpmPayloadBeforeApplyingArchiveLimits() throws IOException {
    Path safe = temporary.resolve("safe.rpm");
    Files.write(safe, rpm("usr/share/doc/readme.txt", "safe".getBytes()));

    ArchiveGuard.Inspection inspection =
        guard.inspect(safe, limits(10, 1024), temporary, deadline());

    assertThat(inspection.entries()).isEqualTo(1);
    assertThat(inspection.expandedBytes()).isEqualTo(4);

    Path oversized = temporary.resolve("oversized.rpm");
    Files.write(oversized, rpm("usr/lib/large.bin", new byte[65]));
    assertThatThrownBy(
            () -> guard.inspect(oversized, limits(10, 64), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_ENTRY_TOO_LARGE");

    Path nested = zip("nested-rpm.zip", "packages/unsafe.rpm", rpm("../escape", new byte[] {1}));
    assertThatThrownBy(
            () -> guard.inspect(nested, limits(10, 16 * 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_PATH_ESCAPE");
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
  void condaInspectionAcceptsOnlyLinksThatStayInsideTheArchive() throws IOException {
    Path safe = temporary.resolve("conda-safe-links.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(safe))) {
      writeTarEntry(output, "lib/libdemo.so.1", "library".getBytes());
      TarArchiveEntry symlink = new TarArchiveEntry("lib/libdemo.so", TarConstants.LF_SYMLINK);
      symlink.setLinkName("libdemo.so.1");
      output.putArchiveEntry(symlink);
      output.closeArchiveEntry();
      TarArchiveEntry hardlink = new TarArchiveEntry("lib/libdemo-hard", TarConstants.LF_LINK);
      hardlink.setLinkName("lib/libdemo.so.1");
      output.putArchiveEntry(hardlink);
      output.closeArchiveEntry();
    }

    ArchiveGuard.Inspection inspection =
        guard.inspectConda(safe, limits(10, 1024), temporary, deadline());

    assertThat(inspection.entries()).isEqualTo(3);
    assertThatThrownBy(() -> guard.inspect(safe, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_SPECIAL_FILE_REJECTED");

    Path escaping = temporary.resolve("conda-escaping-link.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(escaping))) {
      TarArchiveEntry link = new TarArchiveEntry("lib/libdemo.so", TarConstants.LF_SYMLINK);
      link.setLinkName("../../outside");
      output.putArchiveEntry(link);
      output.closeArchiveEntry();
    }
    assertThatThrownBy(
            () -> guard.inspectConda(escaping, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_LINK_ESCAPE");
  }

  @Test
  void condaInspectionReadsExactlyOneNonEmptyIndexAndWrapsMalformedInput() throws IOException {
    Path valid = temporary.resolve("conda-index.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(valid))) {
      writeTarEntry(output, "info/index.json", "{\"name\":\"demo\"}".getBytes());
    }
    assertThat(guard.inspectConda(valid, limits(10, 1024), temporary, deadline()).condaIndex())
        .isEqualTo("{\"name\":\"demo\"}".getBytes());

    Path duplicate = temporary.resolve("conda-duplicate-index.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(duplicate))) {
      writeTarEntry(output, "info/index.json", "{}".getBytes());
      writeTarEntry(output, "info/index.json", "{}".getBytes());
    }
    assertThatThrownBy(
            () -> guard.inspectConda(duplicate, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("CONDA_INDEX_DUPLICATE");

    Path empty = temporary.resolve("conda-empty-index.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(empty))) {
      writeTarEntry(output, "info/index.json", new byte[0]);
    }
    assertThatThrownBy(
            () -> guard.inspectConda(empty, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("CONDA_INDEX_EMPTY");

    assertThatThrownBy(() -> guard.inspectConda(
            temporary.resolve("missing.conda"), limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_INVALID");
  }

  @Test
  void condaInspectionRejectsSpecialFilesAndUnsafeLinkTargetShapes() throws IOException {
    Path special = temporary.resolve("conda-special.tar");
    try (TarArchiveOutputStream output =
        new TarArchiveOutputStream(Files.newOutputStream(special))) {
      TarArchiveEntry fifo = new TarArchiveEntry("device", TarConstants.LF_FIFO);
      output.putArchiveEntry(fifo);
      output.closeArchiveEntry();
    }
    assertThatThrownBy(
            () -> guard.inspectConda(special, limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_SPECIAL_FILE_REJECTED");

    for (String target : new String[] {null, "", "/absolute", "C:\\absolute"}) {
      assertThatThrownBy(() -> ArchiveGuard.validateLinkTarget("lib/demo", target, true))
          .isInstanceOf(ScannerRequestException.class)
          .extracting(failure -> ((ScannerRequestException) failure).code())
          .isEqualTo("ARCHIVE_LINK_ESCAPE");
    }
  }

  @Test
  void conanInspectionUsesTheArchiveBudgetAndMapsFilesystemFailures() throws IOException {
    Path archive = temporary.resolve("conan_package.tgz");
    try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive));
        TarArchiveOutputStream output = new TarArchiveOutputStream(gzip)) {
      writeTarEntry(output, "bin/demo", "payload".getBytes());
    }

    ArchiveGuard.Inspection inspection =
        guard.inspectConan(archive, limits(10, 1024), temporary, deadline());
    assertThat(inspection.entries()).isEqualTo(1);
    assertThat(inspection.expandedBytes()).isEqualTo(7);
    assertThat(inspection.condaIndex()).isNull();

    assertThatThrownBy(() -> guard.inspectConan(
            temporary.resolve("missing.tgz"), limits(10, 1024), temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo("ARCHIVE_INVALID");
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

  private static byte[] tar(String entryName, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarArchiveOutputStream output = new TarArchiveOutputStream(bytes)) {
      writeTarEntry(output, entryName, content);
    }
    return bytes.toByteArray();
  }

  private static byte[] zstdTar(String entryName, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZstdOutputStream zstd = new ZstdOutputStream(bytes);
        TarArchiveOutputStream output = new TarArchiveOutputStream(zstd)) {
      writeTarEntry(output, entryName, content);
    }
    return bytes.toByteArray();
  }

  private static byte[] xzTar(String entryName, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (XZCompressorOutputStream xz = new XZCompressorOutputStream(bytes);
        TarArchiveOutputStream output = new TarArchiveOutputStream(xz)) {
      writeTarEntry(output, entryName, content);
    }
    return bytes.toByteArray();
  }

  private static byte[] rpm(String entryName, byte[] content) throws IOException {
    ByteArrayOutputStream compressedPayload = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(compressedPayload);
        CpioArchiveOutputStream cpio =
            new CpioArchiveOutputStream(gzip, CpioConstants.FORMAT_NEW)) {
      CpioArchiveEntry entry =
          new CpioArchiveEntry(CpioConstants.FORMAT_NEW, entryName, content.length);
      entry.setMode(CpioConstants.C_ISREG | 0644);
      cpio.putArchiveEntry(entry);
      cpio.write(content);
      cpio.closeArchiveEntry();
    }

    ByteArrayOutputStream rpm = new ByteArrayOutputStream();
    byte[] lead = new byte[96];
    lead[0] = (byte) 0xed;
    lead[1] = (byte) 0xab;
    lead[2] = (byte) 0xee;
    lead[3] = (byte) 0xdb;
    rpm.write(lead);
    rpm.write(emptyRpmHeader());
    rpm.write(emptyRpmHeader());
    rpm.write(compressedPayload.toByteArray());
    return rpm.toByteArray();
  }

  private static byte[] emptyRpmHeader() {
    ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
    header.put(new byte[] {(byte) 0x8e, (byte) 0xad, (byte) 0xe8, 0x01});
    header.putInt(0);
    header.putInt(0);
    header.putInt(0);
    return header.array();
  }

  private static void writeTarEntry(
      TarArchiveOutputStream output, String entryName, byte[] content) throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(entryName);
    entry.setSize(content.length);
    output.putArchiveEntry(entry);
    output.write(content);
    output.closeArchiveEntry();
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
