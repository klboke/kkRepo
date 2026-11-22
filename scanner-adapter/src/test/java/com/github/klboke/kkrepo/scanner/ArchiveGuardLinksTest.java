package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArchiveGuardLinksTest {
  @TempDir Path temporary;
  private final ArchiveGuard guard = new ArchiveGuard();
  private static final ResourceLimits LIMITS =
      new ResourceLimits(1024 * 1024, 100, 1024 * 1024, 64 * 1024, 2, 30);

  @Test
  void acceptsGalaxyDebAndRpmLinksWithoutMaterializingThem() throws IOException {
    assertThat(inspect("collection.tar.gz", ArchiveLinkFixtures.galaxy()).entries()).isEqualTo(4);
    assertThat(inspect("demo.deb", ArchiveLinkFixtures.deb()).nestedArchives()).isEqualTo(2);
    ArchiveGuard.Inspection rpm = inspect("demo.rpm", ArchiveLinkFixtures.rpm());
    assertThat(rpm.entries()).isEqualTo(1);
    assertThat(rpm.expandedBytes()).isEqualTo("../../../../usr/bin/gzip".length());
    try (var files = Files.list(temporary)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .containsExactlyInAnyOrder("collection.tar.gz", "demo.deb", "demo.rpm");
    }
  }

  @Test
  void doesNotRecurseIntoTarLinksOrReadThemAsCondaMetadata() throws IOException {
    Path archive = temporary.resolve("links.tar");
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      ArchiveLinkFixtures.file(tar, "package.tgz", "ordinary content");
      ArchiveLinkFixtures.link(tar, "alias.tgz", "package.tgz", TarConstants.LF_SYMLINK);
      ArchiveLinkFixtures.link(tar, "hard.tar.gz", "package.tgz", TarConstants.LF_LINK);
      ArchiveLinkFixtures.link(tar, "info/index.json", "../index.json", TarConstants.LF_SYMLINK);
    }
    // Only the regular .tgz entry consumes nesting depth; the two archive-named links do not.
    ArchiveGuard.Inspection result = guard.inspectConda(archive, LIMITS, temporary, deadline());
    assertThat(result.entries()).isEqualTo(4);
    assertThat(result.nestedArchives()).isEqualTo(1);
    assertThat(result.expandedBytes()).isEqualTo("ordinary content".length());
    assertThat(result.condaIndex()).isNull();
  }

  @Test
  void doesNotReadUnrelatedInfoIndexFilesAsCondaMetadata() throws IOException {
    Path archive = temporary.resolve("ordinary.tar");
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      ArchiveLinkFixtures.file(tar, "info/index.json", "");
      ArchiveLinkFixtures.link(tar, "alias", "info/index.json", TarConstants.LF_SYMLINK);
    }
    assertThat(guard.inspect(archive, LIMITS, temporary, deadline()).condaIndex()).isNull();
    assertThat(guard.inspectConan(archive, LIMITS, temporary, deadline()).condaIndex()).isNull();
  }

  @Test
  void doesNotFollowLinkCyclesOrTreatCpioTargetsAsNestedArchives() throws IOException {
    Path archive = temporary.resolve("cycles.tar");
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      ArchiveLinkFixtures.link(tar, "a.tgz", "b.tgz", TarConstants.LF_SYMLINK);
      ArchiveLinkFixtures.link(tar, "b.tgz", "a.tgz", TarConstants.LF_SYMLINK);
    }
    ResourceLimits noNesting = new ResourceLimits(65536, 10, 65536, 65536, 0, 30);
    assertThat(guard.inspect(archive, noNesting, temporary, deadline()).nestedArchives()).isZero();
    Path rpm = write("link.rpm", ArchiveLinkFixtures.rpm(
        "usr/share/alias.tar.gz", "target.tar.gz".getBytes(StandardCharsets.UTF_8),
        CpioConstants.C_ISLNK));
    assertThat(guard.inspect(rpm, noNesting, temporary, deadline()).nestedArchives()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"../../outside", "/etc/passwd", "C:/outside", "C:\\outside",
      "..\\outside", "\u2215outside", "\uff0foutside", "", "bad\0target"})
  void rejectsEscapingOrMalformedTarAndCpioTargets(String target) throws IOException {
    Path archive = temporary.resolve("unsafe.tar");
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      ArchiveLinkFixtures.link(tar, "lib/link", target, TarConstants.LF_SYMLINK);
    }
    // The TAR header terminates at NUL; test the validator directly for that case.
    if (target.indexOf('\0') < 0) assertRejected(archive, LIMITS, "ARCHIVE_LINK_ESCAPE");
    assertThatThrownBy(() -> ArchiveGuard.validateLinkTarget("lib/link", target, true))
        .isInstanceOf(ScannerRequestException.class);
    Path rpm = write("unsafe.rpm", ArchiveLinkFixtures.rpm(
        "lib/link", target.getBytes(StandardCharsets.UTF_8), CpioConstants.C_ISLNK));
    assertRejected(rpm, LIMITS, "ARCHIVE_LINK_ESCAPE");
  }

  @Test
  void rejectsHardlinksEscapingTheArchiveRoot() throws IOException {
    Path archive = temporary.resolve("hard.tar");
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      ArchiveLinkFixtures.link(tar, "lib/link", "../outside", TarConstants.LF_LINK);
    }
    assertRejected(archive, LIMITS, "ARCHIVE_LINK_ESCAPE");
  }

  @Test
  void boundsCpioTargetAllocationAndCountsLinkBytesAndEntries() throws IOException {
    Path oversized = write("large.rpm", ArchiveLinkFixtures.rpm(
        "link", new byte[16 * 1024 + 1], CpioConstants.C_ISLNK));
    assertRejected(oversized, LIMITS, "ARCHIVE_LINK_TOO_LARGE");
    Path invalidUtf8 = write("utf8.rpm", ArchiveLinkFixtures.rpm(
        "link", new byte[] {(byte) 0xc3, (byte) 0x28}, CpioConstants.C_ISLNK));
    assertRejected(invalidUtf8, LIMITS, "ARCHIVE_LINK_ESCAPE");
    Path rpm = write("budget.rpm", ArchiveLinkFixtures.rpm(
        "link", "target".getBytes(StandardCharsets.UTF_8), CpioConstants.C_ISLNK));
    assertRejected(rpm, new ResourceLimits(65536, 10, 5, 100, 1, 30), "ARCHIVE_EXPANDED_LIMIT");
    assertRejected(rpm, new ResourceLimits(65536, 10, 100, 5, 1, 30), "ARCHIVE_ENTRY_TOO_LARGE");
    Path galaxy = write("entries.tgz", ArchiveLinkFixtures.galaxy());
    assertRejected(galaxy, new ResourceLimits(65536, 1, 65536, 65536, 1, 30), "ARCHIVE_ENTRY_LIMIT");
  }

  @Test
  void rejectsTruncatedCpioLinkBodies() throws IOException {
    byte[] rpm = ArchiveLinkFixtures.rpm(
        "link", "target".getBytes(StandardCharsets.UTF_8), CpioConstants.C_ISLNK);
    byte[] payload;
    try (GZIPInputStream gzip = new GZIPInputStream(
        new ByteArrayInputStream(rpm, 128, rpm.length - 128))) {
      payload = gzip.readAllBytes();
    }
    ByteArrayOutputStream truncated = new ByteArrayOutputStream();
    truncated.write(Arrays.copyOf(rpm, 128));
    try (GZIPOutputStream gzip = new GZIPOutputStream(truncated)) {
      // newc's 110-byte header plus the padded five-byte name ends at byte 116.
      // Keep only three bytes of the declared six-byte target, inside a valid gzip stream.
      gzip.write(payload, 0, 119);
    }
    assertRejected(write("truncated.rpm", truncated.toByteArray()), LIMITS, "ARCHIVE_INVALID");
  }

  @Test
  void continuesRejectingDeviceFifoAndSocketEntries() throws IOException {
    for (byte type : new byte[] {TarConstants.LF_CHR, TarConstants.LF_BLK, TarConstants.LF_FIFO}) {
      Path archive = temporary.resolve("special.tar");
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
        ArchiveLinkFixtures.link(tar, "special", "", type);
      }
      assertRejected(archive, LIMITS, "ARCHIVE_SPECIAL_FILE_REJECTED");
    }
    for (long mode : new long[] {CpioConstants.C_ISCHR, CpioConstants.C_ISBLK,
        CpioConstants.C_ISFIFO, CpioConstants.C_ISSOCK}) {
      Path rpm = write("special.rpm", ArchiveLinkFixtures.rpm("special", new byte[0], mode));
      assertRejected(rpm, LIMITS, "ARCHIVE_SPECIAL_FILE_REJECTED");
    }
  }

  private ArchiveGuard.Inspection inspect(String name, byte[] bytes) throws IOException {
    return guard.inspect(write(name, bytes), LIMITS, temporary, deadline());
  }

  private Path write(String name, byte[] bytes) throws IOException {
    return Files.write(temporary.resolve(name), bytes);
  }

  private void assertRejected(Path archive, ResourceLimits limits, String code) {
    assertThatThrownBy(() -> guard.inspect(archive, limits, temporary, deadline()))
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo(code);
  }

  private static ScanDeadline deadline() {
    return new ScanDeadline(30);
  }
}
