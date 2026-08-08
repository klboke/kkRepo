package com.github.klboke.kkrepo.server.apt;

import com.github.klboke.kkrepo.protocol.apt.AptPackageControl;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.luben.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, non-executing parser for Debian binary package archives. */
@Component
final class AptDebPackageInspector {
  private static final int MAX_DEBIAN_BINARY_BYTES = 64;
  private static final int MAX_CONTROL_BYTES = 4 * 1024 * 1024;
  private static final long MAX_CONTROL_ARCHIVE_BYTES = 64L * 1024 * 1024;
  private static final Set<String> CONTROL_ARCHIVES = Set.of(
      "control.tar", "control.tar.gz", "control.tar.xz", "control.tar.zst", "control.tar.bz2");

  private final long maxCompressedBytes;
  private final long maxControlExpandedBytes;
  private final int maxControlEntries;
  private final long maxInspectionNanos;
  private final Semaphore permits;
  private final long permitWaitMillis;

  @Autowired
  AptDebPackageInspector(
      @Value("${kkrepo.apt.archive.max-compressed-bytes:5368709120}") long maxCompressedBytes,
      @Value("${kkrepo.apt.archive.max-control-expanded-bytes:67108864}")
          long maxControlExpandedBytes,
      @Value("${kkrepo.apt.archive.max-control-entries:4096}") int maxControlEntries,
      @Value("${kkrepo.apt.archive.max-inspection-seconds:120}") long maxInspectionSeconds,
      @Value("${kkrepo.apt.archive.max-concurrent-inspections:4}") int maxConcurrentInspections,
      @Value("${kkrepo.apt.archive.inspection-permit-wait-ms:5000}") long permitWaitMillis) {
    this.maxCompressedBytes = Math.max(1, maxCompressedBytes);
    this.maxControlExpandedBytes = Math.max(1, maxControlExpandedBytes);
    this.maxControlEntries = Math.max(1, maxControlEntries);
    this.maxInspectionNanos = Duration.ofSeconds(Math.max(1, maxInspectionSeconds)).toNanos();
    this.permits = new Semaphore(Math.max(1, maxConcurrentInspections), true);
    this.permitWaitMillis = Math.max(1, permitWaitMillis);
  }

  AptDebPackageInspector() {
    this(5L * 1024 * 1024 * 1024, 64L * 1024 * 1024, 4096, 120, 4, 5000);
  }

  InspectedPackage inspect(InputStream body, String filename) {
    if (filename != null && (filename.isBlank()
        || !filename.toLowerCase(Locale.ROOT).endsWith(".deb")
        || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
        || ".".equals(filename) || "..".equals(filename))) {
      throw bad("APT packages must use a safe .deb filename");
    }
    boolean acquired = acquirePermit();
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-apt-", ".deb");
      Digests digests = buffer(body, file);
      AptPackageControl control = inspectArchive(file);
      // Nexus derives the stored filename from the package control identity. The submitted name
      // is only a transport hint (repository-root POST does not carry one at all).
      String effectiveFilename = canonicalFilename(control);
      return new InspectedPackage(
          file, effectiveFilename, control, digests.md5(), digests.sha1(), digests.sha256(),
          digests.size());
    } catch (MavenExceptions.BadRequestException error) {
      delete(file);
      throw error;
    } catch (IOException | RuntimeException error) {
      delete(file);
      throw bad("Unable to inspect Debian package archive", error);
    } finally {
      if (acquired) permits.release();
    }
  }

  private boolean acquirePermit() {
    try {
      if (permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS)) return true;
      throw new MavenExceptions.WritePolicyDenied(
          "APT archive inspection capacity is busy; retry the request");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new MavenExceptions.WritePolicyDenied(
          "Interrupted while waiting for APT archive inspection capacity");
    }
  }

  private Digests buffer(InputStream body, Path file) throws IOException {
    if (body == null) throw bad("APT package body is required");
    MessageDigest md5 = digest("MD5");
    MessageDigest sha1 = digest("SHA-1");
    MessageDigest sha256 = digest("SHA-256");
    long size = 0;
    try (var output = Files.newOutputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = body.read(buffer)) >= 0;) {
        if (read == 0) continue;
        size += read;
        if (size > maxCompressedBytes) throw bad("APT package exceeds the upload limit");
        md5.update(buffer, 0, read);
        sha1.update(buffer, 0, read);
        sha256.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    if (size == 0) throw bad("APT package is empty");
    return new Digests(
        HexFormat.of().formatHex(md5.digest()),
        HexFormat.of().formatHex(sha1.digest()),
        HexFormat.of().formatHex(sha256.digest()),
        size);
  }

  private AptPackageControl inspectArchive(Path file) throws IOException {
    byte[] controlArchive = null;
    int phase = 0;
    Set<String> members = new HashSet<>();
    long started = System.nanoTime();
    try (InputStream raw = Files.newInputStream(file);
         ArArchiveInputStream ar = new ArArchiveInputStream(raw)) {
      int count = 0;
      for (ArArchiveEntry entry; (entry = ar.getNextEntry()) != null;) {
        checkTime(started);
        if (++count > 16) throw bad("Debian package contains too many ar members");
        String name = normalizeArName(entry.getName());
        if (!members.add(name)) throw bad("Debian package contains duplicate ar member: " + name);
        if (entry.getLength() < 0 || entry.getLength() > maxCompressedBytes) {
          throw bad("Debian package ar member exceeds safety limits");
        }
        if (name.startsWith("_")) {
          drain(ar, started);
          continue;
        }
        if ("debian-binary".equals(name)) {
          if (phase != 0) throw bad("debian-binary must be the first Debian package member");
          byte[] bytes = readBounded(ar, MAX_DEBIAN_BINARY_BYTES, started);
          if (!"2.0\n".equals(new String(bytes, StandardCharsets.US_ASCII))) {
            throw bad("Unsupported Debian package format version");
          }
          phase = 1;
        } else if (CONTROL_ARCHIVES.contains(name)) {
          if (phase != 1) throw bad("Debian control archive has invalid member order");
          if (controlArchive != null) throw bad("Debian package contains multiple control archives");
          controlArchive = readBounded(
              ar, Math.min(maxCompressedBytes, MAX_CONTROL_ARCHIVE_BYTES), started);
          controlArchive = withNamePrefix(name, controlArchive);
          phase = 2;
        } else if (name.startsWith("data.tar")) {
          if (phase != 2) throw bad("Debian data archive has invalid member order");
          requireSupportedDataArchive(name);
          if (entry.getLength() == 0) throw bad("Debian package data archive is empty");
          drain(ar, started);
          phase = 3;
        } else {
          throw bad("Unexpected Debian package ar member: " + name);
        }
      }
    }
    if (phase != 3 || controlArchive == null) {
      throw bad("Debian package must contain debian-binary, control.tar and data.tar members");
    }
    return inspectControlArchive(controlArchive, started);
  }

  /** Stores the archive member name ahead of its bytes without allocating another wrapper record. */
  private static byte[] withNamePrefix(String name, byte[] bytes) {
    byte[] prefix = (name + "\n").getBytes(StandardCharsets.US_ASCII);
    byte[] joined = new byte[prefix.length + bytes.length];
    System.arraycopy(prefix, 0, joined, 0, prefix.length);
    System.arraycopy(bytes, 0, joined, prefix.length, bytes.length);
    return joined;
  }

  private AptPackageControl inspectControlArchive(byte[] namedArchive, long started) throws IOException {
    int delimiter = -1;
    for (int index = 0; index < namedArchive.length; index++) {
      if (namedArchive[index] == '\n') {
        delimiter = index;
        break;
      }
    }
    if (delimiter <= 0) throw bad("Debian control archive identity is missing");
    String name = new String(namedArchive, 0, delimiter, StandardCharsets.US_ASCII);
    ByteArrayInputStream bytes = new ByteArrayInputStream(
        namedArchive, delimiter + 1, namedArchive.length - delimiter - 1);
    try (InputStream decompressed = decompressControl(name, bytes);
         CountingInputStream bounded = new CountingInputStream(
             decompressed, maxControlExpandedBytes, started, maxInspectionNanos);
         TarArchiveInputStream tar = new TarArchiveInputStream(bounded)) {
      byte[] control = null;
      Set<String> names = new HashSet<>();
      int entries = 0;
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (++entries > maxControlEntries) throw bad("Debian control archive has too many entries");
        String entryName = safeTarName(entry.getName());
        if (!names.add(entryName)) throw bad("Debian control archive has duplicate entries");
        if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
            || entry.isBlockDevice() || entry.isFIFO()) {
          throw bad("Debian control archive contains an unsafe entry");
        }
        if (entry.isDirectory()) continue;
        if (!entry.isFile()) throw bad("Debian control archive contains an unsupported entry");
        if ("control".equals(entryName)) {
          if (control != null) throw bad("Debian control archive contains duplicate control files");
          control = readBounded(tar, MAX_CONTROL_BYTES, started);
        } else {
          drain(tar, started);
        }
      }
      if (control == null) throw bad("Debian control archive does not contain control metadata");
      try {
        return AptPackageControl.parse(decodeUtf8(control));
      } catch (IllegalArgumentException error) {
        throw bad("Invalid Debian control metadata", error);
      }
    }
  }

  private InputStream decompressControl(String name, InputStream input) throws IOException {
    return switch (name) {
      case "control.tar" -> input;
      case "control.tar.gz" -> new GzipCompressorInputStream(input, false);
      case "control.tar.xz" -> new XZCompressorInputStream(input, false);
      case "control.tar.zst" -> new ZstdInputStream(input);
      case "control.tar.bz2" -> new BZip2CompressorInputStream(input, false);
      default -> throw bad("Unsupported Debian control archive compression: " + name);
    };
  }

  private static void requireSupportedDataArchive(String name) {
    if (!Set.of("data.tar", "data.tar.gz", "data.tar.xz", "data.tar.zst", "data.tar.bz2", "data.tar.lzma")
        .contains(name)) {
      throw bad("Unsupported Debian data archive compression: " + name);
    }
  }

  private static String decodeUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw bad("Debian control metadata is not valid UTF-8", error);
    }
  }

  private static String canonicalFilename(AptPackageControl control) {
    String filename = control.packageName() + "_" + control.version() + "_"
        + control.architecture() + ".deb";
    if (filename.length() > 255) {
      throw bad("Canonical Debian package filename exceeds 255 characters");
    }
    return filename;
  }

  private byte[] readBounded(InputStream input, long limit, long started) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 64 * 1024));
    byte[] buffer = new byte[8192];
    long total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      checkTime(started);
      if (read == 0) continue;
      total += read;
      if (total > limit) throw bad("Debian package member exceeds safety limits");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private void drain(InputStream input, long started) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    while (input.read(buffer) >= 0) checkTime(started);
  }

  private void checkTime(long started) {
    if (System.nanoTime() - started > maxInspectionNanos) {
      throw bad("Debian package inspection timed out");
    }
  }

  private static String normalizeArName(String raw) {
    if (raw == null || raw.isBlank()) throw bad("Debian package contains an unnamed ar member");
    String name = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
      throw bad("Debian package contains an unsafe ar member");
    }
    return name;
  }

  private static String safeTarName(String raw) {
    if (raw == null || raw.isBlank() || raw.indexOf('\\') >= 0 || raw.startsWith("/")) {
      throw bad("Debian control archive contains an unsafe path");
    }
    String value = raw;
    while (value.startsWith("./")) value = value.substring(2);
    if (value.isBlank()) return ".";
    for (String segment : value.split("/", -1)) {
      if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
        throw bad("Debian control archive contains an unsafe path");
      }
    }
    return value;
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("Missing digest: " + algorithm, error);
    }
  }

  private static void delete(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
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
      AptPackageControl control,
      String md5,
      String sha1,
      String sha256,
      long size) implements AutoCloseable {
    @Override
    public void close() {
      delete(file);
    }
  }

  private record Digests(String md5, String sha1, String sha256, long size) { }

  private static final class CountingInputStream extends FilterInputStream {
    private final long limit;
    private final long started;
    private final long maxNanos;
    private long count;

    private CountingInputStream(InputStream input, long limit, long started, long maxNanos) {
      super(input);
      this.limit = limit;
      this.started = started;
      this.maxNanos = maxNanos;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) consumed(1);
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      if (read > 0) consumed(read);
      return read;
    }

    private void consumed(long bytes) {
      count += bytes;
      if (count > limit) throw bad("Debian control archive exceeds expanded byte limit");
      if (System.nanoTime() - started > maxNanos) throw bad("Debian package inspection timed out");
    }
  }
}
