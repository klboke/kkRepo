package com.github.klboke.kkrepo.server.conda;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.conda.CondaPackageIdentifiers;
import com.github.klboke.kkrepo.protocol.conda.CondaPathParser;
import com.github.klboke.kkrepo.protocol.conda.CondaVersions;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, non-executing inspector for legacy and v2 Conda package archives. */
@Component
final class CondaArchiveInspector {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
  private static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_CONTAINER_METADATA_BYTES = 64 * 1024;

  private final ObjectMapper mapper;
  private final long maxCompressedBytes;
  private final long maxInfoExpandedBytes;
  private final int maxInfoEntries;
  private final long maxInspectionNanos;
  private final Semaphore permits;
  private final long permitWaitMillis;

  @Autowired
  CondaArchiveInspector(
      ObjectMapper mapper,
      @Value("${kkrepo.conda.archive.max-compressed-bytes:5368709120}") long maxCompressedBytes,
      @Value("${kkrepo.conda.archive.max-info-expanded-bytes:268435456}")
          long maxInfoExpandedBytes,
      @Value("${kkrepo.conda.archive.max-info-entries:20000}") int maxInfoEntries,
      @Value("${kkrepo.conda.archive.max-inspection-seconds:120}") long maxInspectionSeconds,
      @Value("${kkrepo.conda.archive.max-concurrent-inspections:4}") int maxConcurrentInspections,
      @Value("${kkrepo.conda.archive.inspection-permit-wait-ms:5000}") long permitWaitMillis) {
    this.mapper = mapper;
    this.maxCompressedBytes = Math.max(1, maxCompressedBytes);
    this.maxInfoExpandedBytes = Math.max(1, maxInfoExpandedBytes);
    this.maxInfoEntries = Math.max(1, maxInfoEntries);
    this.maxInspectionNanos = Duration.ofSeconds(Math.max(1, maxInspectionSeconds)).toNanos();
    this.permits = new Semaphore(Math.max(1, maxConcurrentInspections), true);
    this.permitWaitMillis = Math.max(1, permitWaitMillis);
  }

  CondaArchiveInspector(ObjectMapper mapper) {
    this(mapper, 5L * 1024 * 1024 * 1024, 256L * 1024 * 1024, 20_000, 120, 4, 5_000);
  }

  InspectedPackage inspect(InputStream body, String filename, String expectedSubdir) {
    if (filename == null || (!filename.endsWith(".conda") && !filename.endsWith(".tar.bz2"))) {
      throw bad("Conda packages must end in .conda or .tar.bz2");
    }
    if (!CondaPathParser.isSubdir(expectedSubdir)) {
      throw bad("Conda package subdir is invalid");
    }
    boolean acquired = acquirePermit();
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-conda-", filename.endsWith(".conda") ? ".conda" : ".tar.bz2");
      Digests digests = buffer(body, file);
      InfoArchive info = filename.endsWith(".conda")
          ? inspectConda(file, filename)
          : inspectTarBz2(file);
      Map<String, Object> metadata = parseIndex(info.index());
      validateIndexSchema(metadata, info.pathsPresent());
      Coordinate coordinate = validateCoordinate(metadata, filename, expectedSubdir);
      InspectedPackage result = new InspectedPackage(
          file,
          filename,
          filename.endsWith(".conda") ? "conda" : "tar.bz2",
          coordinate.name(),
          coordinate.version(),
          coordinate.build(),
          coordinate.buildNumber(),
          java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metadata)),
          digests.md5(),
          digests.sha256(),
          digests.size());
      file = null;
      return result;
    } catch (MavenExceptions.BadRequestException e) {
      delete(file);
      throw e;
    } catch (IOException | RuntimeException e) {
      delete(file);
      throw bad("Unable to inspect Conda package archive", e);
    } finally {
      if (acquired) permits.release();
    }
  }

  private boolean acquirePermit() {
    try {
      if (permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS)) return true;
      throw new MavenExceptions.WritePolicyDenied(
          "Conda archive inspection capacity is busy; retry the request");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MavenExceptions.WritePolicyDenied(
          "Interrupted while waiting for Conda archive inspection capacity");
    }
  }

  private Digests buffer(InputStream body, Path file) throws IOException {
    if (body == null) throw bad("Conda package body is required");
    MessageDigest md5 = digest("MD5");
    MessageDigest sha256 = digest("SHA-256");
    long size = 0;
    try (var output = Files.newOutputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = body.read(buffer)) >= 0;) {
        if (read == 0) continue;
        size += read;
        if (size > maxCompressedBytes) {
          throw bad("Conda package exceeds the upload limit");
        }
        md5.update(buffer, 0, read);
        sha256.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    if (size == 0) throw bad("Conda package is empty");
    return new Digests(
        HexFormat.of().formatHex(md5.digest()),
        HexFormat.of().formatHex(sha256.digest()),
        size);
  }

  private InfoArchive inspectTarBz2(Path file) throws IOException {
    try (InputStream raw = Files.newInputStream(file)) {
      if (raw.read() != 'B' || raw.read() != 'Z' || raw.read() != 'h') {
        throw bad("Legacy Conda package is not bzip2-compressed");
      }
    }
    long started = System.nanoTime();
    try (InputStream raw = Files.newInputStream(file);
         BZip2CompressorInputStream bzip2 = new BZip2CompressorInputStream(raw, true);
         CountingInputStream counted = new CountingInputStream(
             bzip2, maxInfoExpandedBytes, started, maxInspectionNanos);
         TarArchiveInputStream tar = new TarArchiveInputStream(counted)) {
      return findIndex(tar, counted, started);
    }
  }

  private InfoArchive inspectConda(Path file, String filename) throws IOException {
    try (InputStream raw = Files.newInputStream(file)) {
      if (raw.read() != 'P' || raw.read() != 'K') {
        throw bad("Conda v2 package is not a ZIP container");
      }
    }
    List<ZipEntry> infoEntries = new ArrayList<>();
    List<ZipEntry> packageEntries = new ArrayList<>();
    ZipEntry metadataEntry = null;
    Set<String> names = new HashSet<>();
    try (ZipFile zip = new ZipFile(file.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      int count = 0;
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (++count > 32) throw bad("Conda v2 package contains too many outer entries");
        String name = safeOuterName(entry.getName());
        if (!names.add(name)) throw bad("Conda v2 package contains duplicate entries");
        if (entry.isDirectory() || entry.getMethod() != ZipEntry.STORED
            || entry.getSize() < 0 || entry.getCompressedSize() < 0
            || entry.getSize() != entry.getCompressedSize()) {
          throw bad("Conda v2 package contains an invalid outer entry");
        }
        if (name.equals("metadata.json")) {
          metadataEntry = entry;
        } else if (name.startsWith("info-") && name.endsWith(".tar.zst")) {
          infoEntries.add(entry);
        } else if (name.startsWith("pkg-") && name.endsWith(".tar.zst")) {
          packageEntries.add(entry);
        } else {
          throw bad("Conda v2 package contains an unsupported outer entry");
        }
      }
      if (metadataEntry == null || infoEntries.size() != 1 || packageEntries.size() != 1) {
        throw bad("Conda v2 package must contain metadata.json and one info/pkg tar.zst pair");
      }
      String infoIdentity = archiveIdentity(infoEntries.getFirst().getName(), "info-");
      String packageIdentity = archiveIdentity(packageEntries.getFirst().getName(), "pkg-");
      if (!infoIdentity.equals(packageIdentity)) {
        throw bad("Conda v2 info and package payload identities differ");
      }
      String filenameIdentity = filename.substring(0, filename.length() - ".conda".length());
      if (!infoIdentity.equals(filenameIdentity)) {
        throw bad("Conda v2 payload identity does not match its package filename");
      }
      if (metadataEntry.getSize() > MAX_CONTAINER_METADATA_BYTES) {
        throw bad("Conda v2 metadata.json exceeds the safe limit");
      }
      try (InputStream metadataInput = zip.getInputStream(metadataEntry)) {
        validateContainerMetadata(readBounded(metadataInput, MAX_CONTAINER_METADATA_BYTES));
      }
      long started = System.nanoTime();
      try (InputStream compressed = zip.getInputStream(infoEntries.getFirst());
           ZstdInputStream zstd = new ZstdInputStream(compressed);
           CountingInputStream counted = new CountingInputStream(
               zstd, maxInfoExpandedBytes, started, maxInspectionNanos);
           TarArchiveInputStream tar = new TarArchiveInputStream(counted)) {
        return findIndex(tar, counted, started);
      }
    }
  }

  private InfoArchive findIndex(
      TarArchiveInputStream tar, CountingInputStream counted, long started) throws IOException {
    int entries = 0;
    byte[] index = null;
    boolean pathsPresent = false;
    Set<String> names = new HashSet<>();
    for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
      checkLimits(++entries, counted.count(), started);
      String name = safeTarName(entry.getName());
      if (!names.add(name)) throw bad("Conda info archive contains duplicate entries");
      if (entry.isLink() || entry.isSymbolicLink()) {
        validateLinkTarget(name, entry.getLinkName(), entry.isSymbolicLink());
      } else if (!entry.isFile() && !entry.isDirectory()) {
        throw bad("Conda package contains a special file");
      }
      if ("info/paths.json".equals(name)) {
        if (!entry.isFile()) throw bad("Conda info/paths.json must be a regular file");
        pathsPresent = true;
      }
      if (!"info/index.json".equals(name)) continue;
      if (index != null || !entry.isFile()) throw bad("Conda package has duplicate info/index.json");
      if (entry.getSize() < 0 || entry.getSize() > MAX_INDEX_BYTES) {
        throw bad("Conda info/index.json exceeds the safe limit");
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream((int) entry.getSize());
      byte[] buffer = new byte[16 * 1024];
      for (int read; (read = tar.read(buffer)) >= 0;) {
        if (read == 0) continue;
        if (output.size() + read > MAX_INDEX_BYTES) {
          throw bad("Conda info/index.json exceeds the safe limit");
        }
        output.write(buffer, 0, read);
        checkLimits(entries, counted.count(), started);
      }
      if (output.size() == 0) throw bad("Conda info/index.json is empty");
      index = output.toByteArray();
    }
    checkLimits(entries, counted.count(), started);
    if (index == null) throw bad("Conda package does not contain info/index.json");
    return new InfoArchive(index, pathsPresent);
  }

  private void validateContainerMetadata(byte[] bytes) {
    try {
      Map<String, Object> value = mapper.readValue(bytes, MAP);
      Object version = value.get("conda_pkg_format_version");
      if (!(version instanceof Number number)
          || number.longValue() != 2L
          || number.doubleValue() != 2.0d) {
        throw bad("Conda v2 metadata.json has an unsupported format version");
      }
    } catch (MavenExceptions.BadRequestException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw bad("Conda v2 metadata.json is invalid", e);
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (read == 0) continue;
      if (output.size() + read > limit) {
        throw bad("Conda v2 metadata.json exceeds the safe limit");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static String archiveIdentity(String name, String prefix) {
    if (name.length() <= prefix.length() + ".tar.zst".length()) {
      throw bad("Conda v2 payload entry name is invalid");
    }
    return name.substring(prefix.length(), name.length() - ".tar.zst".length());
  }

  private void checkLimits(int entries, long expanded, long started) {
    if (entries > maxInfoEntries) throw bad("Conda info archive contains too many entries");
    if (expanded > maxInfoExpandedBytes) {
      throw bad("Conda info archive expands beyond the safe limit");
    }
    if (System.nanoTime() - started > maxInspectionNanos) {
      throw bad("Conda package inspection exceeded the time limit");
    }
  }

  private Map<String, Object> parseIndex(byte[] bytes) {
    try {
      Map<String, Object> value = mapper.readValue(bytes, MAP);
      if (value == null) throw bad("Conda info/index.json must be a JSON object");
      return new LinkedHashMap<>(value);
    } catch (IOException e) {
      throw bad("Conda info/index.json is invalid", e);
    }
  }

  private static void validateIndexSchema(
      Map<String, Object> metadata, boolean pathsPresent) {
    Object raw = metadata.get("schema_version");
    if (raw == null) return;
    if (!(raw instanceof Number number) || number.doubleValue() != number.longValue()) {
      throw bad("Conda info/index.json has an unsupported schema_version");
    }
    if (number.longValue() == 1L) return;
    if (number.longValue() != 2L) {
      throw bad("Conda info/index.json has an unsupported schema_version");
    }
    if (!pathsPresent) {
      throw bad("Conda schema v2 package is missing info/paths.json");
    }
  }

  private static Coordinate validateCoordinate(
      Map<String, Object> metadata, String filename, String expectedSubdir) {
    String name = requiredString(metadata, "name");
    String version = requiredString(metadata, "version");
    String build = requiredString(metadata, "build");
    if (!CondaPackageIdentifiers.isName(name)) {
      throw bad("Conda info/index.json has an invalid name");
    }
    if (!CondaPackageIdentifiers.isBuild(build)) {
      throw bad("Conda info/index.json has an invalid build");
    }
    try {
      CondaVersions.require(version);
    } catch (IllegalArgumentException e) {
      throw bad("Conda info/index.json has an invalid version", e);
    }
    long buildNumber = requiredLong(metadata, "build_number");
    String suffix = filename.endsWith(".conda") ? ".conda" : ".tar.bz2";
    String canonical = name + "-" + version + "-" + build + suffix;
    if (!canonical.equals(filename)) {
      throw bad("Conda filename does not match info/index.json coordinate: " + canonical);
    }
    Object declaredSubdir = metadata.get("subdir");
    if (declaredSubdir != null
        && !declaredSubdir.toString().isBlank()
        && !declaredSubdir.toString().equals(expectedSubdir)) {
      throw bad("Conda package subdir does not match its upload path");
    }
    validateStringList(metadata, "depends");
    validateStringList(metadata, "constrains");
    metadata.remove("base_url");
    metadata.remove("download_url");
    metadata.put("subdir", expectedSubdir);
    return new Coordinate(name, version, build, buildNumber);
  }

  private static void validateStringList(Map<String, Object> metadata, String key) {
    Object raw = metadata.get(key);
    if (raw == null) return;
    if (!(raw instanceof List<?> list) || list.size() > 10_000
        || list.stream().anyMatch(value -> !(value instanceof String text)
            || text.isBlank() || text.length() > 4096
            || text.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f))) {
      throw bad("Conda package " + key + " must be a bounded array of strings");
    }
  }

  private static String requiredString(Map<String, Object> metadata, String key) {
    Object raw = metadata.get(key);
    if (!(raw instanceof String value) || value.isBlank() || value.length() > 255
        || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
        || value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
      throw bad("Conda info/index.json has an invalid " + key);
    }
    return value;
  }

  private static long requiredLong(Map<String, Object> metadata, String key) {
    Object raw = metadata.get(key);
    if (!(raw instanceof Number number)) throw bad("Conda info/index.json has an invalid " + key);
    long value = number.longValue();
    if (value < 0 || number.doubleValue() != (double) value) {
      throw bad("Conda info/index.json has an invalid " + key);
    }
    return value;
  }

  private static String safeOuterName(String raw) {
    String name = raw == null ? "" : raw.replace('\\', '/');
    if (name.isBlank() || name.startsWith("/") || name.contains("/")
        || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0) {
      throw bad("Conda v2 package contains an unsafe outer entry");
    }
    return name;
  }

  private static String safeTarName(String raw) {
    String name = raw == null ? "" : raw.replace('\\', '/');
    while (name.startsWith("./")) name = name.substring(2);
    if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*")
        || name.indexOf('\0') >= 0) {
      throw bad("Conda package contains an unsafe info entry");
    }
    for (String segment : name.split("/")) {
      if (segment.equals(".") || segment.equals("..")) {
        throw bad("Conda package contains path traversal");
      }
    }
    return name;
  }

  private static void validateLinkTarget(
      String entryName, String rawTarget, boolean relativeToParent) {
    String target = rawTarget == null ? "" : rawTarget.replace('\\', '/');
    if (target.isBlank() || target.startsWith("/") || target.matches("^[A-Za-z]:.*")
        || target.indexOf('\0') >= 0) {
      throw bad("Conda package contains an unsafe link target");
    }
    String parent = "";
    if (relativeToParent) {
      int slash = entryName.lastIndexOf('/');
      if (slash >= 0) parent = entryName.substring(0, slash + 1);
    }
    java.util.ArrayDeque<String> normalized = new java.util.ArrayDeque<>();
    for (String segment : (parent + target).split("/")) {
      if (segment.isEmpty() || segment.equals(".")) continue;
      if (segment.equals("..")) {
        if (normalized.isEmpty()) {
          throw bad("Conda package link escapes the archive");
        }
        normalized.removeLast();
      } else {
        normalized.addLast(segment);
      }
    }
  }

  static void delete(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }

  private static MavenExceptions.BadRequestException bad(String message) {
    return new MavenExceptions.BadRequestException(message);
  }

  private static MavenExceptions.BadRequestException bad(String message, Throwable cause) {
    return new MavenExceptions.BadRequestException(message, cause);
  }

  record InspectedPackage(
      Path file,
      String filename,
      String archiveFormat,
      String name,
      String version,
      String build,
      long buildNumber,
      Map<String, Object> metadata,
      String md5,
      String sha256,
      long size) { }

  private record Coordinate(String name, String version, String build, long buildNumber) { }

  private record InfoArchive(byte[] index, boolean pathsPresent) { }

  private record Digests(String md5, String sha256, long size) { }

  private static final class CountingInputStream extends FilterInputStream {
    private final long maximum;
    private final long started;
    private final long timeoutNanos;
    private long count;

    private CountingInputStream(
        InputStream input, long maximum, long started, long timeoutNanos) {
      super(input);
      this.maximum = maximum;
      this.started = started;
      this.timeoutNanos = timeoutNanos;
    }

    @Override
    public int read() throws IOException {
      checkTime();
      int value = super.read();
      if (value >= 0) record(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      checkTime();
      int read = super.read(bytes, offset, length);
      if (read > 0) record(read);
      return read;
    }

    private void record(int read) {
      count += read;
      if (count > maximum) {
        throw bad("Conda info archive expands beyond the safe limit");
      }
      checkTime();
    }

    private void checkTime() {
      if (System.nanoTime() - started > timeoutNanos) {
        throw bad("Conda package inspection exceeded the time limit");
      }
    }

    long count() {
      return count;
    }
  }
}
