package com.github.klboke.kkrepo.server.conan;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.stereotype.Component;

/** Streaming, non-executing validation for Conan recipe and package tar archives. */
@Component
final class ConanArchiveInspector {
  private static final int MAX_ENTRIES = 200_000;
  private static final int MAX_PATH_DEPTH = 128;
  private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 20L * 1024 * 1024 * 1024;
  private static final long MAX_COMPRESSION_RATIO = 1_000;
  private static final long MAX_INSPECTION_NANOS = TimeUnit.MINUTES.toNanos(5);
  private static final Semaphore INSPECTION_SLOTS = new Semaphore(
      Math.max(1, Runtime.getRuntime().availableProcessors() / 2), true);
  private static final Set<String> ARCHIVES = Set.of(
      "conan_export.tgz", "conan_export.txz", "conan_export.tzst",
      "conan_sources.tgz", "conan_sources.txz", "conan_sources.tzst",
      "conan_package.tgz", "conan_package.txz", "conan_package.tzst");

  boolean archive(String path) {
    return path != null && ARCHIVES.contains(filename(path));
  }

  boolean exportArchive(String path) {
    return path != null && filename(path).startsWith("conan_export.") && archive(path);
  }

  boolean packageArchive(String path) {
    return path != null && filename(path).startsWith("conan_package.") && archive(path);
  }

  void inspect(InputStream source, long compressedBytes, String path) {
    if (!archive(path)) return;
    if (!INSPECTION_SLOTS.tryAcquire()) {
      throw new ConanExceptions.Busy("Conan archive inspection capacity is exhausted");
    }
    long deadline = System.nanoTime() + MAX_INSPECTION_NANOS;
    try (BufferedInputStream buffered = new BufferedInputStream(source);
         InputStream decoded = decoded(buffered, path);
         TarArchiveInputStream tar = new TarArchiveInputStream(decoded)) {
      Set<String> names = new HashSet<>();
      long expanded = 0;
      int entries = 0;
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (System.nanoTime() - deadline > 0) {
          throw new ConanExceptions.ContentTooLarge("Conan archive inspection timed out");
        }
        if (++entries > MAX_ENTRIES) {
          throw new ConanExceptions.ContentTooLarge("Conan archive entry limit exceeded");
        }
        String name = normalizedEntry(entry.getName());
        if (!names.add(name)) {
          throw new ConanExceptions.BadRequest("Duplicate Conan archive entry: " + name);
        }
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()
            || entry.isSparse()) {
          throw new ConanExceptions.BadRequest("Unsupported Conan archive entry: " + name);
        }
        if (entry.isSymbolicLink() || entry.isLink()) {
          validateLink(name, entry.getLinkName());
        }
        long size = entry.getSize();
        if (size < 0 || size > MAX_ENTRY_BYTES) {
          throw new ConanExceptions.ContentTooLarge("Conan archive entry is too large: " + name);
        }
        expanded = Math.addExact(expanded, size);
        if (expanded > MAX_EXPANDED_BYTES
            || (compressedBytes > 0 && expanded / Math.max(1, compressedBytes)
                > MAX_COMPRESSION_RATIO)) {
          throw new ConanExceptions.ContentTooLarge("Conan archive expansion limit exceeded");
        }
      }
      // Conan always emits a package archive, including for valid header-only/metadata-only
      // recipes whose package() step installs no files.
      if (entries == 0 && !packageArchive(path)) {
        throw new ConanExceptions.BadRequest("Conan archive is empty");
      }
    } catch (ConanExceptions.ConanException failure) {
      throw failure;
    } catch (ArithmeticException failure) {
      throw new ConanExceptions.ContentTooLarge("Conan archive expansion limit exceeded");
    } catch (IOException failure) {
      throw new ConanExceptions.BadRequest("Invalid Conan archive: " + path, failure);
    } finally {
      INSPECTION_SLOTS.release();
    }
  }

  /**
   * Replays a previously guarded archive at manifest commit and returns the bounded MD5 tree that
   * Conan used to create {@code conanmanifest.txt}. Recipe source entries use the official
   * {@code export_source/} logical prefix even though the archive itself stores relative paths.
   */
  Map<String, String> manifestEntries(
      InputStream source, long compressedBytes, String path, String logicalPrefix, int maxEntries) {
    if (!archive(path)) return Map.of();
    if (maxEntries <= 0 || !INSPECTION_SLOTS.tryAcquire()) {
      throw new ConanExceptions.Busy("Conan archive inspection capacity is exhausted");
    }
    long deadline = System.nanoTime() + MAX_INSPECTION_NANOS;
    try (BufferedInputStream buffered = new BufferedInputStream(source);
         InputStream decoded = decoded(buffered, path);
         TarArchiveInputStream tar = new TarArchiveInputStream(decoded)) {
      LinkedHashMap<String, String> result = new LinkedHashMap<>();
      long expanded = 0;
      for (TarArchiveEntry entry; (entry = tar.getNextEntry()) != null;) {
        if (System.nanoTime() - deadline > 0) {
          throw new ConanExceptions.ContentTooLarge("Conan archive inspection timed out");
        }
        String name = normalizedEntry(entry.getName());
        if (entry.isDirectory()) continue;
        if (result.size() >= maxEntries) {
          throw new ConanExceptions.ContentTooLarge("Conan manifest entry limit exceeded");
        }
        String digest;
        if (entry.isSymbolicLink() || entry.isLink()) {
          validateLink(name, entry.getLinkName());
          digest = md5(entry.getLinkName().getBytes(StandardCharsets.UTF_8));
        } else {
          if (!entry.isFile()) {
            throw new ConanExceptions.BadRequest("Unsupported Conan archive entry: " + name);
          }
          long size = entry.getSize();
          if (size < 0 || size > MAX_ENTRY_BYTES) {
            throw new ConanExceptions.ContentTooLarge("Conan archive entry is too large: " + name);
          }
          expanded = Math.addExact(expanded, size);
          if (expanded > MAX_EXPANDED_BYTES
              || (compressedBytes > 0 && expanded / Math.max(1, compressedBytes)
                  > MAX_COMPRESSION_RATIO)) {
            throw new ConanExceptions.ContentTooLarge("Conan archive expansion limit exceeded");
          }
          MessageDigest checksum = digest();
          byte[] buffer = new byte[64 * 1024];
          long remaining = size;
          while (remaining > 0) {
            int count = tar.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
              throw new ConanExceptions.BadRequest("Truncated Conan archive entry: " + name);
            }
            if (count == 0) continue;
            checksum.update(buffer, 0, count);
            remaining -= count;
          }
          digest = HexFormat.of().formatHex(checksum.digest());
        }
        String manifestPath = (logicalPrefix == null ? "" : logicalPrefix) + name;
        if (result.putIfAbsent(manifestPath, digest) != null) {
          throw new ConanExceptions.BadRequest(
              "Duplicate Conan manifest entry: " + manifestPath);
        }
      }
      return Map.copyOf(result);
    } catch (ConanExceptions.ConanException failure) {
      throw failure;
    } catch (ArithmeticException failure) {
      throw new ConanExceptions.ContentTooLarge("Conan archive expansion limit exceeded");
    } catch (IOException failure) {
      throw new ConanExceptions.BadRequest("Invalid Conan archive: " + path, failure);
    } finally {
      INSPECTION_SLOTS.release();
    }
  }

  private static InputStream decoded(BufferedInputStream input, String path) throws IOException {
    input.mark(64);
    try {
      input.reset();
      String detected = CompressorStreamFactory.detect(input);
      input.reset();
      String expected = path.endsWith(".tgz") ? CompressorStreamFactory.GZIP
          : path.endsWith(".txz") ? CompressorStreamFactory.XZ
          : path.endsWith(".tzst") ? CompressorStreamFactory.ZSTANDARD
          : null;
      if (expected == null || !expected.equals(detected)) {
        throw new IOException("Conan archive compressor does not match its extension");
      }
      return new CompressorStreamFactory().createCompressorInputStream(detected, input, false);
    } catch (org.apache.commons.compress.compressors.CompressorException invalid) {
      throw new IOException("Unsupported Conan archive compressor", invalid);
    }
  }

  private static String filename(String path) {
    int separator = path.lastIndexOf('/');
    return separator < 0 ? path : path.substring(separator + 1);
  }

  private static String normalizedEntry(String value) {
    if (value == null || value.isBlank() || value.startsWith("/") || value.indexOf('\\') >= 0
        || value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f)) {
      throw new ConanExceptions.BadRequest("Unsafe Conan archive path");
    }
    Path normalized = Path.of(value).normalize();
    String canonical = normalized.toString().replace('\\', '/');
    if (normalized.getNameCount() > MAX_PATH_DEPTH) {
      throw new ConanExceptions.BadRequest("Conan archive path is too deep: " + value);
    }
    if (canonical.isBlank() || canonical.equals(".") || canonical.equals("..")
        || canonical.startsWith("../") || !canonical.equals(value.replaceAll("/+$", ""))) {
      throw new ConanExceptions.BadRequest("Unsafe Conan archive path: " + value);
    }
    return canonical;
  }

  private static void validateLink(String entry, String link) {
    if (link == null || link.isBlank() || link.startsWith("/") || link.indexOf('\\') >= 0) {
      throw new ConanExceptions.BadRequest("Unsafe Conan archive link: " + entry);
    }
    Path parent = Path.of(entry).getParent();
    Path resolved = (parent == null ? Path.of(link) : parent.resolve(link)).normalize();
    String value = resolved.toString().replace('\\', '/');
    if (value.equals("..") || value.startsWith("../")) {
      throw new ConanExceptions.BadRequest("Conan archive link escapes its root: " + entry);
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("MD5 is unavailable", impossible);
    }
  }

  private static String md5(byte[] bytes) {
    return HexFormat.of().formatHex(digest().digest(bytes));
  }
}
