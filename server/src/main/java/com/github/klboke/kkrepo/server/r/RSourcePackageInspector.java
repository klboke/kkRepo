package com.github.klboke.kkrepo.server.r;

import com.github.klboke.kkrepo.protocol.r.RPackageMetadata;
import com.github.klboke.kkrepo.protocol.r.RPathParser;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, non-executing inspector for CRAN source package archives. */
@Component
final class RSourcePackageInspector {
  private static final int MAX_DESCRIPTION_BYTES = 1024 * 1024;
  private static final int MAX_PATH_DEPTH = 64;

  private final long maxCompressedBytes;
  private final long maxExpandedBytes;
  private final long maxEntryBytes;
  private final long maxExpansionRatio;
  private final int maxEntries;
  private final Duration timeout;
  private final Semaphore permits;
  private final long permitWaitMillis;

  @Autowired
  RSourcePackageInspector(
      @Value("${kkrepo.r.archive.max-compressed-bytes:5368709120}") long maxCompressedBytes,
      @Value("${kkrepo.r.archive.max-expanded-bytes:21474836480}") long maxExpandedBytes,
      @Value("${kkrepo.r.archive.max-entry-bytes:4294967296}") long maxEntryBytes,
      @Value("${kkrepo.r.archive.max-expansion-ratio:500}") long maxExpansionRatio,
      @Value("${kkrepo.r.archive.max-entries:1000000}") int maxEntries,
      @Value("${kkrepo.r.archive.max-inspection-seconds:120}") long maxInspectionSeconds,
      @Value("${kkrepo.r.archive.max-concurrent-inspections:4}") int maxConcurrentInspections,
      @Value("${kkrepo.r.archive.inspection-permit-wait-ms:5000}") long permitWaitMillis) {
    this.maxCompressedBytes = Math.max(1, maxCompressedBytes);
    this.maxExpandedBytes = Math.max(1, maxExpandedBytes);
    this.maxEntryBytes = Math.max(1, maxEntryBytes);
    this.maxExpansionRatio = Math.max(1, maxExpansionRatio);
    this.maxEntries = Math.max(1, maxEntries);
    this.timeout = Duration.ofSeconds(Math.max(1, maxInspectionSeconds));
    this.permits = new Semaphore(Math.max(1, maxConcurrentInspections), true);
    this.permitWaitMillis = Math.max(1, permitWaitMillis);
  }

  RSourcePackageInspector() {
    this(5L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024,
        4L * 1024 * 1024 * 1024, 500, 1_000_000, 120, 4, 5000);
  }

  InspectedPackage inspect(InputStream body, String filename) {
    if (filename == null || !filename.endsWith(".tar.gz")) {
      throw bad("R source packages must use a safe .tar.gz filename");
    }
    boolean acquired = acquirePermit();
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-r-package-", ".tar.gz");
      BlobDigest blob = spool(body, file);
      ArchiveProjection projection = inspectArchive(file, blob.size());
      RPackageMetadata metadata = RPackageMetadata.fromDescription(
          projection.description(), filename);
      if (!projection.topLevel().equals(metadata.packageName())) {
        throw bad("R archive top-level directory must match Package");
      }
      return new InspectedPackage(
          file, filename, metadata, blob.md5(), blob.sha1(), blob.sha256(), blob.size(),
          projection.entryCount(), projection.expandedBytes());
    } catch (MavenExceptions.BadRequestException error) {
      delete(file);
      throw error;
    } catch (IOException | IllegalArgumentException error) {
      delete(file);
      throw bad("Unable to inspect R source package", error);
    } finally {
      if (acquired) permits.release();
    }
  }

  private ArchiveProjection inspectArchive(Path file, long compressedBytes) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    Set<String> names = new HashSet<>();
    String topLevel = null;
    byte[] description = null;
    long expanded = 0;
    int entries = 0;
    try (InputStream raw = Files.newInputStream(file);
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw, false);
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (++entries > maxEntries) throw bad("R archive contains too many entries");
        checkDeadline(deadline);
        String name = safePath(entry.getName(), entry.isDirectory());
        if (!names.add(name)) throw bad("R archive contains duplicate entries");
        int separator = name.indexOf('/');
        String entryTop = separator < 0 ? name : name.substring(0, separator);
        if (topLevel == null) topLevel = entryTop;
        if (!topLevel.equals(entryTop)) {
          throw bad("R archive must contain exactly one top-level package directory");
        }
        if (entry.isSparse() || entry.isSymbolicLink() || entry.isLink()
            || entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
          throw bad("R archive contains an unsafe entry type: " + name);
        }
        if (!entry.isFile() && !entry.isDirectory()) {
          throw bad("R archive contains an unsupported entry type: " + name);
        }
        if (entry.getSize() < 0 || entry.getSize() > maxEntryBytes) {
          throw bad("R archive entry exceeds safety limits: " + name);
        }
        if (entry.isDirectory()) continue;
        ByteArrayOutputStream selected = name.equals(topLevel + "/DESCRIPTION")
            ? new ByteArrayOutputStream() : null;
        if (selected != null && description != null) {
          throw bad("R archive contains more than one DESCRIPTION");
        }
        byte[] buffer = new byte[64 * 1024];
        long entryBytes = 0;
        for (int read; (read = tar.read(buffer)) >= 0;) {
          if (read == 0) continue;
          entryBytes = Math.addExact(entryBytes, read);
          expanded = Math.addExact(expanded, read);
          if (entryBytes > maxEntryBytes || expanded > maxExpandedBytes) {
            throw bad("R archive expansion exceeds safety limits");
          }
          if (compressedBytes > 0 && exceedsExpansionRatio(
              expanded, compressedBytes, maxExpansionRatio)) {
            throw bad("R archive expansion ratio exceeds safety limits");
          }
          if (selected != null) {
            if (selected.size() + read > MAX_DESCRIPTION_BYTES) {
              throw bad("R DESCRIPTION exceeds safety limits");
            }
            selected.write(buffer, 0, read);
          }
          checkDeadline(deadline);
        }
        if (entryBytes != entry.getSize()) throw bad("R archive contains a truncated entry");
        if (selected != null) description = selected.toByteArray();
      }
    } catch (ArithmeticException overflow) {
      throw bad("R archive expanded size overflow", overflow);
    }
    if (topLevel == null || description == null || description.length == 0) {
      throw bad("R archive requires a unique top-level DESCRIPTION");
    }
    return new ArchiveProjection(topLevel, description, entries, expanded);
  }

  private BlobDigest spool(InputStream body, Path file) throws IOException {
    if (body == null) throw bad("R source package body is required");
    MessageDigest md5 = digest("MD5");
    MessageDigest sha1 = digest("SHA-1");
    MessageDigest sha256 = digest("SHA-256");
    long size = 0;
    try (var output = Files.newOutputStream(
        file, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = body.read(buffer)) >= 0;) {
        if (read == 0) continue;
        size = Math.addExact(size, read);
        if (size > maxCompressedBytes) throw bad("R source package exceeds upload limit");
        md5.update(buffer, 0, read);
        sha1.update(buffer, 0, read);
        sha256.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    } catch (ArithmeticException overflow) {
      throw bad("R source package size overflow", overflow);
    }
    if (size == 0) throw bad("R source package is empty");
    return new BlobDigest(
        HexFormat.of().formatHex(md5.digest()),
        HexFormat.of().formatHex(sha1.digest()),
        HexFormat.of().formatHex(sha256.digest()),
        size);
  }

  private static String safePath(String raw, boolean directory) {
    if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.indexOf('\\') >= 0
        || raw.indexOf('\0') >= 0) {
      throw bad("R archive contains an unsafe path");
    }
    String name = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    String[] segments = name.split("/", -1);
    if ((!directory && segments.length < 2) || segments.length > MAX_PATH_DEPTH) {
      throw bad("R archive path depth is invalid");
    }
    for (String segment : segments) {
      if (segment.isBlank() || segment.equals(".") || segment.equals("..")
          || segment.length() > 255) {
        throw bad("R archive contains an unsafe path segment");
      }
    }
    if (!RPathParser.validPackageName(segments[0])) {
      throw bad("R archive top-level directory is not a package name");
    }
    return name;
  }

  private static boolean exceedsExpansionRatio(
      long expandedBytes, long compressedBytes, long ratio) {
    long floor = 64L * 1024 * 1024;
    if (expandedBytes <= floor || compressedBytes <= 0) return false;
    if (compressedBytes > Long.MAX_VALUE / ratio) return false;
    return expandedBytes > compressedBytes * ratio;
  }

  private boolean acquirePermit() {
    try {
      if (permits.tryAcquire(permitWaitMillis, TimeUnit.MILLISECONDS)) return true;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
    throw new MavenExceptions.WritePolicyDenied(
        "R package inspection capacity is busy; retry the request");
  }

  private static void checkDeadline(long deadline) {
    if (System.nanoTime() > deadline) throw bad("R archive inspection timed out");
  }

  private static MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void delete(Path file) {
    if (file == null) return;
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      file.toFile().deleteOnExit();
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
      RPackageMetadata metadata,
      String md5,
      String sha1,
      String sha256,
      long size,
      int entryCount,
      long expandedBytes) implements AutoCloseable {
    @Override
    public void close() {
      delete(file);
    }
  }

  private record BlobDigest(String md5, String sha1, String sha256, long size) { }

  private record ArchiveProjection(
      String topLevel, byte[] description, int entryCount, long expandedBytes) { }
}
