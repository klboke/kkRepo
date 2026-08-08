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
import java.util.List;
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

  @Test
  void reusesTheServerInspectedIndexWhileStillValidatingModernContainers() throws IOException {
    byte[] legacyIndex = index("trusted-legacy", "1.0", "0", 0, "noarch");
    Path legacy = temporary.resolve("trusted-legacy.tar.bz2");
    Files.write(legacy, legacy("info/not-index.json", "ignored".getBytes(StandardCharsets.UTF_8)));
    CondaPackageCataloger.Prepared legacyPrepared = cataloger.prepare(
        legacy, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("trusted-legacy-work"), new ScanDeadline(30), legacyIndex);
    assertThat(legacyPrepared.name()).isEqualTo("trusted-legacy");

    byte[] modernIndex = index("trusted-modern", "2.0", "h1", 1, "linux-64");
    Path modern = temporary.resolve("trusted-modern.conda");
    Files.write(modern, modern("trusted-modern-2.0-h1", "not-json".getBytes(StandardCharsets.UTF_8)));
    CondaPackageCataloger.Prepared modernPrepared = cataloger.prepare(
        modern, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("trusted-modern-work"), new ScanDeadline(30), modernIndex);
    assertThat(modernPrepared.version()).isEqualTo("2.0");
    assertThat(MAPPER.readTree(
        modernPrepared.scanRoot().resolve("conda-meta/package.json").toFile()).path("name")
        .asText()).isEqualTo("trusted-modern");
  }

  @Test
  void validatesEveryRequiredIndexFieldAndOptionalSubdir() throws IOException {
    List<String> invalid = List.of(
        "[]",
        "{}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\"}",
        "{\"name\":\"\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"bad/name\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"demo\",\"version\":true,\"build\":\"0\",\"build_number\":0}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"bad\\\\build\",\"build_number\":0}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":-1}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":\"0\"}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0,\"subdir\":1}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0,\"subdir\":\"bad/subdir\"}",
        "{\"name\":\"demo\",\"version\":\"1\",\"build\":\"0\",\"build_number\":0} trailing");
    int index = 0;
    for (String json : invalid) {
      Path artifact = temporary.resolve("invalid-" + index + ".tar.bz2");
      Files.write(artifact, legacy("info/index.json", json.getBytes(StandardCharsets.UTF_8)));
      int caseIndex = index++;
      assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
          artifact, ScannerArtifactType.CONDA, limits(1024 * 1024),
          temporary.resolve("invalid-work-" + caseIndex), new ScanDeadline(30)));
    }

    byte[] noSubdir = MAPPER.writeValueAsBytes(Map.of(
        "name", "demo", "version", "1", "build", "0", "build_number", 0));
    Path valid = temporary.resolve("no-subdir.tar.bz2");
    Files.write(valid, legacy("info/index.json", noSubdir));
    assertThat(cataloger.prepare(
        valid, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("no-subdir-work"), new ScanDeadline(30)).subdir()).isEmpty();
  }

  @Test
  void rejectsUnsafeOrInconsistentModernContainerLayouts() throws IOException {
    byte[] metadata = "{\"conda_pkg_format_version\":2}".getBytes(StandardCharsets.UTF_8);
    byte[] index = index("demo", "1.0", "0", 0, "linux-64");
    byte[] info = zstdTar("info/index.json", index);
    byte[] payload = zstdTar("bin/demo", new byte[] {1});

    Path unsupported = modernFile("unsupported.conda", List.of(
        entry("metadata.json", metadata), entry("info-demo.tar.zst", info),
        entry("pkg-demo.tar.zst", payload), entry("extra.txt", new byte[] {1})));
    assertInvalidModern(unsupported, limits(1024 * 1024), "unsupported-work");

    Path multipleInfo = modernFile("multiple-info.conda", List.of(
        entry("metadata.json", metadata), entry("info-one.tar.zst", info),
        entry("info-two.tar.zst", info), entry("pkg-one.tar.zst", payload)));
    assertInvalidModern(multipleInfo, limits(1024 * 1024), "multiple-info-work");

    Path mismatched = modernFile("mismatch.conda", List.of(
        entry("metadata.json", metadata), entry("info-one.tar.zst", info),
        entry("pkg-two.tar.zst", payload)));
    assertInvalidModern(mismatched, limits(1024 * 1024), "mismatch-work");

    Path emptyIdentity = modernFile("empty-identity.conda", List.of(
        entry("metadata.json", metadata), entry("info-.tar.zst", info),
        entry("pkg-.tar.zst", payload)));
    assertInvalidModern(emptyIdentity, limits(1024 * 1024), "empty-identity-work");

    Path invalidMetadata = modernFile("invalid-metadata.conda", List.of(
        entry("metadata.json", "{\"conda_pkg_format_version\":1}".getBytes(StandardCharsets.UTF_8)),
        entry("info-demo.tar.zst", info), entry("pkg-demo.tar.zst", payload)));
    assertInvalidModern(invalidMetadata, limits(1024 * 1024), "invalid-metadata-work");

    Path malformedMetadata = modernFile("malformed-metadata.conda", List.of(
        entry("metadata.json", "{".getBytes(StandardCharsets.UTF_8)),
        entry("info-demo.tar.zst", info), entry("pkg-demo.tar.zst", payload)));
    assertInvalidModern(malformedMetadata, limits(1024 * 1024), "malformed-metadata-work");

    Path oversizedMetadata = modernFile("oversized-metadata.conda", List.of(
        entry("metadata.json", new byte[64 * 1024 + 1]),
        entry("info-demo.tar.zst", info), entry("pkg-demo.tar.zst", payload)));
    assertCode("CONDA_METADATA_LIMIT", () -> cataloger.prepare(
        oversizedMetadata, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("oversized-metadata-work"), new ScanDeadline(30)));

    Path tooMany = modernFile("too-many.conda", List.of(
        entry("metadata.json", metadata), entry("info-demo.tar.zst", info),
        entry("pkg-demo.tar.zst", payload)));
    assertInvalidModern(tooMany, new ResourceLimits(
        4 * 1024 * 1024, 2, 4 * 1024 * 1024, 1024 * 1024, 2, 30), "too-many-work");
  }

  @Test
  void mapsArtifactAndWorkspaceIoFailuresToStableScannerErrors() throws IOException {
    Path missing = temporary.resolve("does-not-exist.conda");
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        missing, ScannerArtifactType.CONDA, limits(1024), temporary.resolve("missing-artifact"),
        new ScanDeadline(30)));

    Path artifact = temporary.resolve("workspace-error.tar.bz2");
    Files.write(artifact, legacy("info/index.json", index("demo", "1", "0", 0, "noarch")));
    Path workspace = temporary.resolve("workspace-file");
    Files.writeString(workspace, "not a directory");
    assertCode("CONDA_CATALOG_IO", () -> cataloger.prepare(
        artifact, ScannerArtifactType.CONDA, limits(1024 * 1024), workspace,
        new ScanDeadline(30)));
  }

  @Test
  void rejectsDuplicateAndOverpopulatedInfoArchivesWhileSkippingUnneededPayloads()
      throws IOException {
    byte[] valid = index("demo", "1", "0", 0, "noarch");
    Path duplicate = temporary.resolve("duplicate-index.tar.bz2");
    Files.write(duplicate, legacyEntries(List.of(
        entry("info/index.json", valid), entry("info/index.json", valid))));
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        duplicate, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("duplicate-work"), new ScanDeadline(30)));

    Path tooMany = temporary.resolve("too-many-info.tar.bz2");
    Files.write(tooMany, legacyEntries(List.of(
        entry("info/about.json", new byte[32 * 1024]),
        entry("info/files", new byte[] {1}),
        entry("info/index.json", valid))));
    ResourceLimits twoEntries = new ResourceLimits(
        4 * 1024 * 1024, 2, 4 * 1024 * 1024, 1024 * 1024, 2, 30);
    assertCode("CONDA_METADATA_LIMIT", () -> cataloger.prepare(
        tooMany, ScannerArtifactType.CONDA, twoEntries,
        temporary.resolve("too-many-info-work"), new ScanDeadline(30)));

    Path skipped = temporary.resolve("skipped-payload.tar.bz2");
    Files.write(skipped, legacyEntries(List.of(
        entry("info/about.json", new byte[64 * 1024]),
        entry("info/index.json", valid))));
    assertThat(cataloger.prepare(
        skipped, ScannerArtifactType.CONDA, limits(1024 * 1024),
        temporary.resolve("skipped-work"), new ScanDeadline(30)).name()).isEqualTo("demo");
  }

  private static void assertCode(String code, Runnable invocation) {
    assertThatThrownBy(invocation::run)
        .isInstanceOf(ScannerRequestException.class)
        .extracting(failure -> ((ScannerRequestException) failure).code())
        .isEqualTo(code);
  }

  private void assertInvalidModern(Path artifact, ResourceLimits resourceLimits, String workspace) {
    assertCode("CONDA_PACKAGE_INVALID", () -> cataloger.prepare(
        artifact, ScannerArtifactType.CONDA, resourceLimits, temporary.resolve(workspace),
        new ScanDeadline(30)));
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
    return legacyEntries(List.of(entry(name, content)));
  }

  private static byte[] legacyEntries(List<OuterEntry> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(bzip2)) {
      for (OuterEntry entry : entries) {
        writeTar(tar, entry.name(), entry.content());
      }
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

  private Path modernFile(String filename, List<OuterEntry> entries) throws IOException {
    Path artifact = temporary.resolve(filename);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
      for (OuterEntry entry : entries) {
        putStored(zip, entry.name(), entry.content());
      }
    }
    return artifact;
  }

  private static OuterEntry entry(String name, byte[] content) {
    return new OuterEntry(name, content);
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

  private record OuterEntry(String name, byte[] content) {}
}
