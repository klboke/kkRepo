package com.github.klboke.kkrepo.server.arch;

import com.github.klboke.kkrepo.protocol.arch.ArchDesc;
import com.github.klboke.kkrepo.protocol.arch.ArchPackageFilename;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import com.github.luben.zstd.ZstdInputStream;
import org.tukaani.xz.XZInputStream;
import org.springframework.stereotype.Component;

/** Bounded, non-executing pacman package inspector. */
@Component
final class ArchPackageInspector {
  private static final int MAX_PKGINFO_BYTES = 1024 * 1024;
  private static final int MAX_ENTRIES = 1_000_000;
  private static final long MAX_TOTAL_ENTRY_BYTES = 8L * 1024 * 1024 * 1024;

  Inspected inspect(Path file, String fileName, long size, String md5, String sha256) {
    ArchPackageFilename parsed = ArchPackageFilename.parse(fileName);
    if (parsed == null) {
      throw new MavenExceptions.BadRequestException("Invalid pacman package filename");
    }
    try (InputStream input = Files.newInputStream(file);
         InputStream counted = new CountingInputStream(
             decompress(input, parsed.compression()), MAX_TOTAL_ENTRY_BYTES);
         TarArchiveInputStream tar = new TarArchiveInputStream(counted)) {
      Map<String, List<String>> fields = null;
      TarArchiveEntry entry;
      int entries = 0;
      long totalEntryBytes = 0;
      while ((entry = tar.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) {
          throw new MavenExceptions.BadRequestException("Package contains too many entries");
        }
        if (entry.getSize() > 0) {
          if (entry.getSize() > MAX_TOTAL_ENTRY_BYTES - totalEntryBytes) {
            throw new MavenExceptions.BadRequestException(
                "Package payload is too large to inspect");
          }
          totalEntryBytes += entry.getSize();
        }
        String name = stripDotSlash(entry.getName());
        if (name.equals(".PKGINFO")) {
          fields = parsePkginfo(readBounded(tar, MAX_PKGINFO_BYTES));
        }
      }
      if (fields == null) {
        throw new MavenExceptions.BadRequestException("Package does not contain .PKGINFO");
      }
      String pkgname = first(fields, "pkgname");
      String pkgver = first(fields, "pkgver");
      String arch = first(fields, "arch");
      if (pkgname == null || pkgver == null || arch == null) {
        throw new MavenExceptions.BadRequestException(
            "Package .PKGINFO is missing pkgname, pkgver or arch");
      }
      if (!pkgname.equals(parsed.name()) || !pkgver.equals(parsed.version())
          || !arch.equals(parsed.architecture())) {
        throw new MavenExceptions.BadRequestException(
            "Package filename does not match .PKGINFO identity");
      }
      ArchDesc desc = new ArchDesc(
          fileName,
          pkgname,
          first(fields, "pkgbase"),
          pkgver,
          first(fields, "pkgdesc"),
          size,
          longValue(first(fields, "size")),
          md5,
          sha256,
          null,
          first(fields, "url"),
          list(fields, "license"),
          arch,
          longValue(first(fields, "builddate")),
          first(fields, "packager"),
          list(fields, "depend"),
          list(fields, "optdepend"),
          list(fields, "makedepend"),
          list(fields, "checkdepend"),
          list(fields, "conflict"),
          list(fields, "provides"),
          list(fields, "replaces"),
          list(fields, "group"));
      return new Inspected(parsed, desc);
    } catch (MavenExceptions.BadRequestException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      throw new MavenExceptions.BadRequestException("Invalid pacman package archive", error);
    }
  }

  private static InputStream decompress(InputStream input, String compression) throws IOException {
    BufferedInputStream buffered = new BufferedInputStream(input);
    return switch (compression) {
      case "tar.zst" -> new ZstdInputStream(buffered);
      case "tar.xz" -> new XZInputStream(buffered);
      case "tar.gz" -> new GzipCompressorInputStream(buffered);
      case "tar.bz2" -> new BZip2CompressorInputStream(buffered);
      case "tar" -> buffered;
      default -> throw new MavenExceptions.BadRequestException(
          "Unsupported pacman package compression: " + compression);
    };
  }

  private static Map<String, List<String>> parsePkginfo(byte[] bytes) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    for (String line : text.split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      int equals = trimmed.indexOf('=');
      if (equals <= 0) continue;
      String key = trimmed.substring(0, equals).trim();
      String value = trimmed.substring(equals + 1).trim();
      if (key.isEmpty() || value.isEmpty()) continue;
      fields.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }
    return fields;
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    byte[] bytes = input.readNBytes(limit + 1);
    if (bytes.length > limit) {
      throw new MavenExceptions.BadRequestException(".PKGINFO is too large");
    }
    return bytes;
  }

  private static String stripDotSlash(String name) {
    String value = name == null ? "" : name;
    while (value.startsWith("./")) value = value.substring(2);
    return value;
  }

  private static String first(Map<String, List<String>> fields, String key) {
    List<String> values = fields.get(key);
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  private static List<String> list(Map<String, List<String>> fields, String key) {
    List<String> values = fields.get(key);
    return values == null ? List.of() : List.copyOf(values);
  }

  private static Long longValue(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static final class CountingInputStream extends java.io.FilterInputStream {
    private final long limit;
    private long total;

    private CountingInputStream(InputStream input, long limit) {
      super(input);
      this.limit = Math.max(1, limit);
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) count(1);
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      if (read > 0) count(read);
      return read;
    }

    @Override
    public long skip(long bytes) throws IOException {
      long skipped = super.skip(bytes);
      if (skipped > 0) count(skipped);
      return skipped;
    }

    private void count(long bytes) throws IOException {
      total += bytes;
      if (total > limit) {
        throw new IOException("Package payload is too large to inspect");
      }
    }
  }

  record Inspected(ArchPackageFilename filename, ArchDesc desc) {
  }
}
