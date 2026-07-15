package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.springframework.stereotype.Component;

/** Inspects untrusted Terraform archives without extracting or executing their content. */
@Component
final class TerraformArchiveInspector {
  private static final long MAX_UPLOAD = 1024L * 1024 * 1024;
  private static final long MAX_EXPANDED = 2L * 1024 * 1024 * 1024;
  private static final long RATIO_FLOOR = 1024L * 1024;
  private static final long MAX_COMPRESSION_RATIO = 200;
  private static final int MAX_ENTRIES = 20_000;
  private static final long MAX_INSPECTION_NANOS = Duration.ofMinutes(2).toNanos();

  Path bufferAndInspect(InputStream body, String filename, boolean module, String providerName) {
    Path file = null;
    try {
      file = Files.createTempFile("kkrepo-terraform-", suffix(filename));
      try (var out = Files.newOutputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int read; (read = body.read(buffer)) >= 0;) {
          total += read;
          if (total > MAX_UPLOAD) throw bad("Terraform archive exceeds the upload limit");
          out.write(buffer, 0, read);
        }
      }
      boolean valid = filename.toLowerCase(Locale.ROOT).endsWith(".zip")
          ? inspectZip(file, module, providerName)
          : inspectTar(file, filename, module, providerName);
      if (!valid) {
        throw bad(module
            ? "Terraform module archive must contain at least one .tf or .tf.json file"
            : "Terraform provider archive does not contain the expected provider binary");
      }
      return file;
    } catch (IOException | RuntimeException e) {
      if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) {}
      if (e instanceof RuntimeException runtime) throw runtime;
      throw bad("Unable to inspect Terraform archive", e);
    }
  }

  private boolean inspectZip(Path file, boolean module, String providerName) throws IOException {
    try (ZipArchiveInputStream in = new ZipArchiveInputStream(
        new BufferedInputStream(Files.newInputStream(file)), "UTF-8", true, true)) {
      ZipArchiveEntry entry;
      State state = new State(Files.size(file));
      while ((entry = in.getNextEntry()) != null) {
        validateEntry(entry.getName(), entry.isUnixSymlink(), entry.isDirectory(), state);
        if (!entry.isDirectory()) {
          state.match |= matches(entry.getName(), module, providerName);
          drain(in, state);
        }
      }
      return state.match;
    }
  }

  private boolean inspectTar(Path file, String filename, boolean module, String providerName) throws IOException {
    try (InputStream raw = Files.newInputStream(file);
         InputStream compressed = decompressor(raw, filename);
         TarArchiveInputStream in = new TarArchiveInputStream(compressed)) {
      TarArchiveEntry entry;
      State state = new State(Files.size(file));
      while ((entry = in.getNextEntry()) != null) {
        validateEntry(entry.getName(), entry.isSymbolicLink() || entry.isLink(), entry.isDirectory(), state);
        if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
          throw bad("Terraform archive contains a device or FIFO entry");
        }
        if (!entry.isDirectory()) {
          state.match |= matches(entry.getName(), module, providerName);
          drain(in, state);
        }
      }
      return state.match;
    }
  }

  private static InputStream decompressor(InputStream raw, String filename) throws IOException {
    String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
      return new GZIPInputStream(raw);
    }
    if (lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".xz")) {
      return new XZCompressorInputStream(raw, true);
    }
    if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) {
      return new BZip2CompressorInputStream(raw, true);
    }
    throw bad("Unsupported Terraform module archive compression");
  }

  private static void validateEntry(String rawName, boolean link, boolean directory, State state) {
    if (++state.entries > MAX_ENTRIES) throw bad("Terraform archive contains too many entries");
    String name = rawName == null ? "" : rawName.replace('\\', '/');
    if (name.isBlank() || name.startsWith("/") || name.startsWith("//")
        || name.matches("^[A-Za-z]:.*") || name.indexOf('\0') >= 0 || link) {
      throw bad("Terraform archive contains an unsafe entry");
    }
    for (String segment : name.split("/", -1)) {
      if (segment.isEmpty() && !directory || ".".equals(segment) || "..".equals(segment)) {
        throw bad("Terraform archive contains path traversal");
      }
    }
    if (!state.names.add(name)) {
      throw bad("Terraform archive contains duplicate entries");
    }
  }

  private static boolean matches(String name, boolean module, String providerName) {
    String leaf = name.replace('\\', '/');
    leaf = leaf.substring(leaf.lastIndexOf('/') + 1);
    if (module) return leaf.endsWith(".tf") || leaf.endsWith(".tf.json");
    String executable = "terraform-provider-" + providerName;
    if (!leaf.startsWith(executable)) return false;
    String suffix = leaf.substring(executable.length());
    return suffix.isEmpty() || suffix.startsWith("_") || ".exe".equalsIgnoreCase(suffix);
  }

  private static void drain(InputStream in, State state) throws IOException {
    byte[] buffer = new byte[32 * 1024];
    for (int read; (read = in.read(buffer)) >= 0;) {
      state.expanded += read;
      if (state.expanded > MAX_EXPANDED) throw bad("Terraform archive expands beyond the safe limit");
      if (state.expanded > RATIO_FLOOR
          && state.expanded > Math.max(1L, state.compressedSize) * MAX_COMPRESSION_RATIO) {
        throw bad("Terraform archive compression ratio exceeds the safe limit");
      }
      if (System.nanoTime() - state.startedNanos > MAX_INSPECTION_NANOS) {
        throw bad("Terraform archive inspection exceeded the time limit");
      }
    }
  }

  private static String suffix(String filename) {
    String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return ".tar.gz";
    if (lower.endsWith(".tar.xz") || lower.endsWith(".txz") || lower.endsWith(".xz")) return ".tar.xz";
    if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) return ".tar.bz2";
    if (lower.endsWith(".zip")) return ".zip";
    throw bad("Terraform module archive must be .zip, .tar.gz, .tgz, .txz, .xz, .tar.xz, .tar.bz2, or .tbz2");
  }

  private static MavenExceptions.BadRequestException bad(String message) {
    return new MavenExceptions.BadRequestException(message);
  }

  private static MavenExceptions.BadRequestException bad(String message, Throwable cause) {
    return new MavenExceptions.BadRequestException(message, cause);
  }

  private static final class State {
    final long compressedSize;
    final long startedNanos = System.nanoTime();
    final Set<String> names = new HashSet<>();
    int entries;
    long expanded;
    boolean match;

    State(long compressedSize) {
      this.compressedSize = compressedSize;
    }
  }
}
