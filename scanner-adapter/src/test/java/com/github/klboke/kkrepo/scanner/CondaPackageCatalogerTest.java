package com.github.klboke.kkrepo.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CondaPackageCatalogerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path temporary;

  private final CondaPackageCataloger cataloger = new CondaPackageCataloger(MAPPER);

  @Test
  void preparesLegacyPackageAsTheMinimalSyftCondaMetaTree() throws IOException {
    byte[] metadata = index("demo", "1.2.3", "py_0", 0, "noarch");
    Path artifact = temporary.resolve("demo-1.2.3-py_0.tar.bz2");
    Files.write(artifact, legacy("info/index.json", metadata));

    CondaPackageCataloger.Prepared prepared = cataloger.prepare(
        artifact,
        ScannerArtifactType.CONDA,
        limits(1024 * 1024),
        temporary.resolve("legacy-work"),
        new ScanDeadline(30));

    assertThat(prepared.name()).isEqualTo("demo");
    assertThat(prepared.version()).isEqualTo("1.2.3");
    assertThat(prepared.build()).isEqualTo("py_0");
    assertThat(prepared.subdir()).isEqualTo("noarch");
    Path synthetic = prepared.scanRoot().resolve("conda-meta/package.json");
    assertThat(Files.readAllBytes(synthetic)).isEqualTo(metadata);
    try (var files = Files.walk(prepared.scanRoot())) {
      assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(1);
    }
  }

  @Test
  void preparesModernPackageFromItsBoundedInfoArchive() throws IOException {
    byte[] metadata = index("modern-demo", "2.0", "h123_4", 4, "linux-64");
    Path artifact = temporary.resolve("modern-demo-2.0-h123_4.conda");
    Files.write(artifact, modern("modern-demo-2.0-h123_4", metadata));

    CondaPackageCataloger.Prepared prepared = cataloger.prepare(
        artifact,
        ScannerArtifactType.CONDA,
        limits(1024 * 1024),
        temporary.resolve("modern-work"),
        new ScanDeadline(30));

    assertThat(prepared.name()).isEqualTo("modern-demo");
    assertThat(prepared.version()).isEqualTo("2.0");
    JsonNode written = MAPPER.readTree(
        prepared.scanRoot().resolve("conda-meta/package.json").toFile());
    assertThat(written.path("build_number").asLong()).isEqualTo(4);
  }

  @Test
  void rejectsMissingMalformedAndOversizedIndexMetadata() throws IOException {
    Path missing = temporary.resolve("missing.tar.bz2");
    Files.write(missing, legacy("info/about.json", "{}".getBytes(StandardCharsets.UTF_8)));
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        missing, ScannerArtifactType.CONDA, limits(1024), temporary.resolve("missing-work"),
        new ScanDeadline(30)));

    Path malformed = temporary.resolve("malformed.tar.bz2");
    Files.write(malformed, legacy(
        "info/index.json", "{\"name\":true}".getBytes(StandardCharsets.UTF_8)));
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        malformed, ScannerArtifactType.CONDA, limits(1024), temporary.resolve("bad-work"),
        new ScanDeadline(30)));

    Path oversized = temporary.resolve("oversized.tar.bz2");
    Files.write(oversized, legacy("info/index.json", index(
        "demo", "1.0", "py_0", 0, "noarch")));
    assertCode("CONDA_METADATA_LIMIT", () -> cataloger.prepare(
        oversized, ScannerArtifactType.CONDA, limits(16), temporary.resolve("large-work"),
        new ScanDeadline(30)));
  }

  @Test
  void rejectsRenamedNonCondaAndIncompleteModernContainers() throws IOException {
    Path raw = temporary.resolve("raw.conda");
    Files.writeString(raw, "not an archive");
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        raw, ScannerArtifactType.CONDA, limits(1024), temporary.resolve("raw-work"),
        new ScanDeadline(30)));

    byte[] metadata = index("demo", "1.0", "py_0", 0, "noarch");
    Path incomplete = temporary.resolve("incomplete.conda");
    byte[] info = zstdTar("info/index.json", metadata);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(incomplete))) {
      putStored(zip, "metadata.json",
          "{\"conda_pkg_format_version\":2}".getBytes(StandardCharsets.UTF_8));
      putStored(zip, "info-demo-1.0-py_0.tar.zst", info);
    }
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        incomplete, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("incomplete-work"), new ScanDeadline(30)));

    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        raw, ScannerArtifactType.ZIP, limits(1024), temporary.resolve("wrong-type-work"),
        new ScanDeadline(30)));
  }

  private static void assertCode(String code, Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo(code);
  }

  private static ResourceLimits limits(long singleFileBytes) {
    return new ResourceLimits(
        4 * 1024 * 1024,
        100,
        4 * 1024 * 1024,
        singleFileBytes,
        2,
        30);
  }

  private static byte[] index(
      String name, String version, String build, long buildNumber, String subdir)
      throws IOException {
    return MAPPER.writeValueAsBytes(Map.of(
        "name", name,
        "version", version,
        "build", build,
        "build_number", buildNumber,
        "subdir", subdir,
        "depends", java.util.List.of("python >=3.12")));
  }

  private static byte[] legacy(String name, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip2)) {
      writeTar(tar, name, content);
    }
    return bytes.toByteArray();
  }

  private static byte[] modern(String identity, byte[] index) throws IOException {
    byte[] info = zstdTar("info/index.json", index);
    byte[] payload = zstdTar("bin/demo", "payload".getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      putStored(zip, "metadata.json",
          "{\"conda_pkg_format_version\":2}".getBytes(StandardCharsets.UTF_8));
      putStored(zip, "info-" + identity + ".tar.zst", info);
      putStored(zip, "pkg-" + identity + ".tar.zst", payload);
    }
    return bytes.toByteArray();
  }

  private static byte[] zstdTar(String name, byte[] content) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZstdOutputStream zstd = new ZstdOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
      writeTar(tar, name, content);
    }
    return bytes.toByteArray();
  }

  private static void writeTar(
      TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    tar.putArchiveEntry(entry);
    tar.write(content);
    tar.closeArchiveEntry();
  }

  private static void putStored(ZipOutputStream zip, String name, byte[] content)
      throws IOException {
    CRC32 crc = new CRC32();
    crc.update(content);
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(content.length);
    entry.setCompressedSize(content.length);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }
}
