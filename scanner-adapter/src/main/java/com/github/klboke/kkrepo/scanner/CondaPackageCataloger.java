package com.github.klboke.kkrepo.scanner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.stereotype.Component;

/** Builds the minimal on-disk shape consumed by Syft's conda-meta cataloger. */
@Component
final class CondaPackageCataloger {
  private static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_CONTAINER_METADATA_BYTES = 64 * 1024;
  private static final int MAX_OUTER_ENTRIES = 32;

  private final ObjectMapper mapper;

  CondaPackageCataloger(ObjectMapper mapper) {
    this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  Prepared prepare(
      Path artifact,
      ScannerArtifactType artifactType,
      ResourceLimits limits,
      Path workspace,
      ScanDeadline deadline) {
    return prepare(artifact, artifactType, limits, workspace, deadline, null);
  }

  Prepared prepare(
      Path artifact,
      ScannerArtifactType artifactType,
      ResourceLimits limits,
      Path workspace,
      ScanDeadline deadline,
      byte[] inspectedIndex) {
    if (artifactType != ScannerArtifactType.CONDA) {
      throw invalid("Conda catalog preparation requires a Conda package type");
    }
    deadline.check();
    byte[] index = inspectedIndex == null ? null : inspectedIndex.clone();
    try {
      byte[] magic = readMagic(artifact, 3);
      if (magic.length >= 2 && magic[0] == 'P' && magic[1] == 'K') {
        if (index == null) {
          index = readModernIndex(artifact, limits, deadline);
        } else {
          validateModernContainer(artifact, limits, deadline);
        }
      } else if (magic.length == 3
          && magic[0] == 'B' && magic[1] == 'Z' && magic[2] == 'h') {
        if (index == null) {
          index = readLegacyIndex(artifact, limits, deadline);
        }
      } else {
        throw invalid("Conda package is neither a v2 ZIP nor a legacy bzip2 archive");
      }
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new ScannerRequestException(
          "CONDA_PACKAGE_INVALID", "Unable to read Conda package metadata", 422, false, e);
    }

    Metadata metadata = parseIndex(index);
    Path scanRoot = workspace.resolve("conda-root");
    Path target = scanRoot.resolve("conda-meta/package.json");
    try {
      deadline.check();
      Files.createDirectories(target.getParent());
      Files.write(target, index);
      deadline.check();
    } catch (IOException e) {
      throw new ScannerRequestException(
          "CONDA_CATALOG_IO", "Unable to prepare Conda metadata for scanning", 503, true, e);
    }
    return new Prepared(
        scanRoot,
        metadata.name(),
        metadata.version(),
        metadata.build(),
        metadata.subdir());
  }

  private byte[] readLegacyIndex(
      Path artifact, ResourceLimits limits, ScanDeadline deadline) throws IOException {
    try (InputStream raw = Files.newInputStream(artifact);
        BZip2CompressorInputStream bzip2 = new BZip2CompressorInputStream(raw, true);
        BoundedInputStream bounded =
            new BoundedInputStream(bzip2, limits.maxUncompressedBytes(), deadline);
        TarArchiveInputStream tar = new TarArchiveInputStream(bounded)) {
      return findIndex(tar, limits, deadline);
    }
  }

  private byte[] readModernIndex(
      Path artifact, ResourceLimits limits, ScanDeadline deadline) throws IOException {
    return inspectModernContainer(artifact, limits, deadline, true);
  }

  private void validateModernContainer(
      Path artifact, ResourceLimits limits, ScanDeadline deadline) throws IOException {
    inspectModernContainer(artifact, limits, deadline, false);
  }

  private byte[] inspectModernContainer(
      Path artifact,
      ResourceLimits limits,
      ScanDeadline deadline,
      boolean readIndex) throws IOException {
    try (ZipFile zip = new ZipFile(artifact.toFile())) {
      ZipEntry metadata = null;
      ZipEntry info = null;
      ZipEntry payload = null;
      Set<String> names = new HashSet<>();
      Enumeration<? extends ZipEntry> entries = zip.entries();
      int count = 0;
      while (entries.hasMoreElements()) {
        deadline.check();
        ZipEntry entry = entries.nextElement();
        if (++count > Math.min(MAX_OUTER_ENTRIES, limits.maxArchiveEntries())) {
          throw invalid("Conda v2 package contains too many outer entries");
        }
        String name = entry.getName();
        ArchiveGuard.validatePath(name);
        if (entry.isDirectory()
            || entry.getMethod() != ZipEntry.STORED
            || entry.getSize() < 0
            || entry.getCompressedSize() != entry.getSize()
            || !names.add(name)) {
          throw invalid("Conda v2 package contains an invalid outer entry");
        }
        if ("metadata.json".equals(name)) {
          if (metadata != null) throw invalid("Conda v2 package has duplicate metadata.json");
          metadata = entry;
        } else if (name.startsWith("info-") && name.endsWith(".tar.zst")) {
          if (info != null) throw invalid("Conda v2 package has multiple info archives");
          info = entry;
        } else if (name.startsWith("pkg-") && name.endsWith(".tar.zst")) {
          if (payload != null) throw invalid("Conda v2 package has multiple payload archives");
          payload = entry;
        } else {
          throw invalid("Conda v2 package contains an unsupported outer entry");
        }
      }
      if (metadata == null || info == null || payload == null) {
        throw invalid("Conda v2 package must contain metadata.json and one info/pkg archive pair");
      }
      if (!archiveIdentity(info.getName(), "info-")
          .equals(archiveIdentity(payload.getName(), "pkg-"))) {
        throw invalid("Conda v2 info and payload archive identities differ");
      }
      if (metadata.getSize() > MAX_CONTAINER_METADATA_BYTES) {
        throw limit("Conda v2 metadata.json exceeds the scanner metadata limit");
      }
      try (InputStream metadataInput = zip.getInputStream(metadata)) {
        validateContainerMetadata(readBounded(
            metadataInput, MAX_CONTAINER_METADATA_BYTES, deadline));
      }
      if (!readIndex) return null;
      try (InputStream compressed = zip.getInputStream(info);
          ZstdInputStream zstd = new ZstdInputStream(compressed);
          BoundedInputStream bounded =
              new BoundedInputStream(zstd, limits.maxUncompressedBytes(), deadline);
          TarArchiveInputStream tar = new TarArchiveInputStream(bounded)) {
        return findIndex(tar, limits, deadline);
      }
    }
  }

  private byte[] findIndex(
      TarArchiveInputStream tar, ResourceLimits limits, ScanDeadline deadline) throws IOException {
    byte[] index = null;
    int entries = 0;
    for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
      deadline.check();
      if (++entries > limits.maxArchiveEntries()) {
        throw limit("Conda info archive contains too many entries");
      }
      ArchiveGuard.validatePath(entry.getName());
      if (!"info/index.json".equals(entry.getName())) {
        continue;
      }
      if (index != null || !entry.isFile()) {
        throw invalid("Conda package contains an invalid or duplicate info/index.json");
      }
      long maximum = Math.min(MAX_INDEX_BYTES, limits.maxSingleFileBytes());
      if (entry.getSize() < 1 || entry.getSize() > maximum) {
        throw limit("Conda info/index.json exceeds the scanner metadata limit");
      }
      index = readBounded(tar, maximum, deadline);
      if (index.length == 0) {
        throw invalid("Conda info/index.json is empty");
      }
    }
    deadline.check();
    if (index == null) {
      throw invalid("Conda package does not contain info/index.json");
    }
    return index;
  }

  private Metadata parseIndex(byte[] index) {
    try {
      JsonNode root = mapper.readTree(index);
      if (root == null || !root.isObject()) {
        throw invalid("Conda info/index.json must be a JSON object");
      }
      String name = requiredText(root, "name");
      String version = requiredText(root, "version");
      String build = requiredText(root, "build");
      JsonNode buildNumber = root.get("build_number");
      if (buildNumber == null
          || !buildNumber.isIntegralNumber()
          || !buildNumber.canConvertToLong()
          || buildNumber.longValue() < 0) {
        throw invalid("Conda info/index.json has an invalid build_number");
      }
      String subdir = optionalText(root, "subdir");
      return new Metadata(name, version, build, subdir);
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new ScannerRequestException(
          "CONDA_PACKAGE_INVALID", "Conda info/index.json is invalid", 422, false, e);
    }
  }

  private void validateContainerMetadata(byte[] metadata) {
    try {
      JsonNode root = mapper.readTree(metadata);
      JsonNode version = root == null ? null : root.get("conda_pkg_format_version");
      if (root == null
          || !root.isObject()
          || version == null
          || !version.isNumber()
          || version.doubleValue() != 2.0d) {
        throw invalid("Conda v2 metadata.json has an unsupported format version");
      }
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new ScannerRequestException(
          "CONDA_PACKAGE_INVALID", "Conda v2 metadata.json is invalid", 422, false, e);
    }
  }

  private static String requiredText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual()) {
      throw invalid("Conda info/index.json has an invalid " + field);
    }
    String text = value.textValue();
    if (text.isBlank()
        || text.length() > 255
        || text.indexOf('/') >= 0
        || text.indexOf('\\') >= 0
        || text.chars().anyMatch(character -> character <= 0x1f || character == 0x7f)) {
      throw invalid("Conda info/index.json has an invalid " + field);
    }
    return text;
  }

  private static String optionalText(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) return "";
    if (!value.isTextual()) {
      throw invalid("Conda info/index.json has an invalid " + field);
    }
    String text = value.textValue();
    if (text.length() > 255
        || text.indexOf('/') >= 0
        || text.indexOf('\\') >= 0
        || text.chars().anyMatch(character -> character <= 0x1f || character == 0x7f)) {
      throw invalid("Conda info/index.json has an invalid " + field);
    }
    return text;
  }

  private static byte[] readMagic(Path artifact, int length) throws IOException {
    try (InputStream input = Files.newInputStream(artifact)) {
      return input.readNBytes(length);
    }
  }

  private static byte[] readBounded(
      InputStream input, long maximum, ScanDeadline deadline) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[16 * 1024];
    for (int read; (read = input.read(buffer)) >= 0;) {
      deadline.check();
      if (read == 0) continue;
      if (read > maximum - output.size()) {
        throw limit("Conda metadata exceeds the scanner metadata limit");
      }
      output.write(buffer, 0, read);
    }
    deadline.check();
    return output.toByteArray();
  }

  private static String archiveIdentity(String name, String prefix) {
    int suffixLength = ".tar.zst".length();
    if (name.length() <= prefix.length() + suffixLength) {
      throw invalid("Conda v2 payload archive name is invalid");
    }
    return name.substring(prefix.length(), name.length() - suffixLength);
  }

  private static ScannerRequestException invalid(String message) {
    return new ScannerRequestException("CONDA_PACKAGE_INVALID", message, 422, false);
  }

  private static ScannerRequestException limit(String message) {
    return new ScannerRequestException("CONDA_METADATA_LIMIT", message, 413, false);
  }

  record Prepared(Path scanRoot, String name, String version, String build, String subdir) {}

  private record Metadata(String name, String version, String build, String subdir) {}

  private static final class BoundedInputStream extends FilterInputStream {
    private final long maximum;
    private final ScanDeadline deadline;
    private long count;

    private BoundedInputStream(InputStream input, long maximum, ScanDeadline deadline) {
      super(input);
      this.maximum = maximum;
      this.deadline = deadline;
    }

    @Override
    public int read() throws IOException {
      deadline.check();
      int value = super.read();
      if (value >= 0) add(1);
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      deadline.check();
      int read = super.read(buffer, offset, length);
      if (read > 0) add(read);
      return read;
    }

    @Override
    public long skip(long count) throws IOException {
      long remaining = count;
      byte[] buffer = new byte[(int) Math.min(16 * 1024L, Math.max(1L, remaining))];
      long skipped = 0;
      while (remaining > 0) {
        int read = read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) break;
        skipped += read;
        remaining -= read;
      }
      return skipped;
    }

    private void add(long bytes) {
      deadline.check();
      if (bytes > maximum - count) {
        throw limit("Conda info archive exceeds the scanner expansion limit");
      }
      count += bytes;
    }
  }
}
