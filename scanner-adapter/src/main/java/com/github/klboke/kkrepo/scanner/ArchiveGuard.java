package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.stereotype.Component;

/**
 * Rejects archive structures that could escape a scanner workspace or exhaust configured limits.
 * It never extracts entries and never executes input content.
 */
@Component
public class ArchiveGuard {
  private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[/\\\\].*");
  private static final Set<String> NESTED_SUFFIXES =
      Arrays.stream(ScannerArtifactType.values())
          .map(ScannerArtifactType::suffix)
          .filter(suffix -> !suffix.isEmpty())
          .collect(Collectors.toUnmodifiableSet());
  private static final long MAX_EXPANSION_RATIO = 1000;
  private static final long MIN_RATIO_BUDGET_BYTES = 1024L * 1024;

  public Inspection inspect(
      Path input, ResourceLimits limits, Path workspace, ScanDeadline deadline) {
    try {
      deadline.check();
      Budget budget = new Budget(limits, Files.size(input), deadline);
      inspectPath(input, "", 0, budget, workspace, false);
      deadline.check();
      return new Inspection(budget.entries, budget.expandedBytes, budget.nestedArchives);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "ARCHIVE_INVALID", "Input archive is malformed or unsupported", 422, false, e);
    }
  }

  /**
   * Inspects all OCI layers against one request-wide archive/decompression budget.
   *
   * <p>Layer links and whiteout special entries are valid image filesystem metadata and are not
   * extracted by this guard, so they are accepted while paths, entry counts, per-file bytes,
   * aggregate expanded bytes, nesting, and expansion ratio remain bounded.
   */
  public Inspection inspectOciLayers(
      List<Path> layers, ResourceLimits limits, Path workspace, ScanDeadline deadline) {
    try {
      deadline.check();
      long compressedBytes = 0;
      for (Path layer : layers) {
        long size = Files.size(layer);
        if (size > Long.MAX_VALUE - compressedBytes) {
          throw rejected("OCI_INPUT_TOO_LARGE", "OCI layer bytes exceed the input limit");
        }
        compressedBytes += size;
      }
      if (compressedBytes > limits.maxInputBytes()) {
        throw rejected("OCI_INPUT_TOO_LARGE", "OCI layer bytes exceed the input limit");
      }
      Budget budget = new Budget(limits, compressedBytes, deadline);
      for (Path layer : layers) {
        inspectPath(layer, "", 0, budget, workspace, true);
      }
      deadline.check();
      return new Inspection(budget.entries, budget.expandedBytes, budget.nestedArchives);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "OCI_LAYER_INVALID", "OCI layer is malformed or unsupported", 422, false, e);
    }
  }

  private void inspectPath(
      Path input,
      String hint,
      int depth,
      Budget budget,
      Path workspace,
      boolean allowImageFilesystemMetadata) {
    try {
      if (inspectAsArchive(
          input, depth, budget, workspace, allowImageFilesystemMetadata)) return;
      inspectAsCompressed(
          input, hint, depth, budget, workspace, allowImageFilesystemMetadata);
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException e) {
      throw new ScannerRequestException(
          "ARCHIVE_INVALID", "Input archive is malformed or unsupported", 422, false, e);
    }
  }

  private boolean inspectAsArchive(
      Path input,
      int depth,
      Budget budget,
      Path workspace,
      boolean allowImageFilesystemMetadata)
      throws IOException, ArchiveException {
    budget.check();
    try (BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(input))) {
      String type;
      try {
        type = ArchiveStreamFactory.detect(raw);
        budget.check();
      } catch (ArchiveException notArchive) {
        return false;
      }
      try (ArchiveInputStream<?> archive =
          ArchiveStreamFactory.DEFAULT.createArchiveInputStream(type, raw)) {
        inspectEntries(
            archive, depth, budget, workspace, allowImageFilesystemMetadata);
      }
      return true;
    }
  }

  private void inspectAsCompressed(
      Path input,
      String hint,
      int depth,
      Budget budget,
      Path workspace,
      boolean allowImageFilesystemMetadata)
      throws IOException, CompressorException, ArchiveException {
    budget.check();
    try (BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(input))) {
      String compressor;
      try {
        compressor = CompressorStreamFactory.detect(raw);
        budget.check();
      } catch (CompressorException notCompressed) {
        return;
      }
      try (CompressorInputStream compressed =
          CompressorStreamFactory.getSingleton()
              .createCompressorInputStream(compressor, raw, false);
          BufferedInputStream expanded = new BufferedInputStream(compressed)) {
        try {
          String type = ArchiveStreamFactory.detect(expanded);
          try (ArchiveInputStream<?> archive =
              ArchiveStreamFactory.DEFAULT.createArchiveInputStream(type, expanded)) {
            inspectEntries(
                archive, depth, budget, workspace, allowImageFilesystemMetadata);
          }
        } catch (ArchiveException singleCompressedFile) {
          drain(expanded, budget, budget.limits.maxSingleFileBytes());
        }
      }
    }
  }

  private void inspectEntries(
      ArchiveInputStream<?> archive,
      int depth,
      Budget budget,
      Path workspace,
      boolean allowImageFilesystemMetadata)
      throws IOException {
    while (true) {
      budget.check();
      ArchiveEntry entry = archive.getNextEntry();
      budget.check();
      if (entry == null) break;
      budget.addEntry();
      validatePath(entry.getName());
      if (!allowImageFilesystemMetadata) validateType(entry);
      long declaredSize = entry.getSize();
      if (declaredSize > budget.limits.maxSingleFileBytes()) {
        throw rejected("ARCHIVE_ENTRY_TOO_LARGE", "Archive entry exceeds the single-file limit");
      }
      if (entry instanceof ZipArchiveEntry zip
          && zip.getCompressedSize() > 0
          && zip.getSize() > 0
          && zip.getSize() / Math.max(1, zip.getCompressedSize()) > 1000) {
        throw rejected("ARCHIVE_EXPANSION_RATIO", "Archive entry expansion ratio is unsafe");
      }
      if (entry.isDirectory()) continue;
      if (!archive.canReadEntryData(entry)) {
        throw rejected("ARCHIVE_ENTRY_UNREADABLE", "Archive contains an unsupported entry");
      }

      if (looksNested(entry.getName())) {
        if (depth >= budget.limits.maxNestedDepth()) {
          throw rejected("ARCHIVE_NESTING_LIMIT", "Archive nesting exceeds the configured limit");
        }
        budget.nestedArchives++;
        Path nested = Files.createTempFile(workspace, "nested-", ".archive");
        try {
          copyEntry(archive, nested, budget, budget.limits.maxSingleFileBytes());
          inspectPath(
              nested,
              entry.getName(),
              depth + 1,
              budget,
              workspace,
              allowImageFilesystemMetadata);
        } finally {
          Files.deleteIfExists(nested);
        }
      } else {
        drain(archive, budget, budget.limits.maxSingleFileBytes());
      }
    }
  }

  static void validatePath(String rawName) {
    if (rawName == null || rawName.isBlank() || rawName.indexOf('\0') >= 0) {
      throw rejected("ARCHIVE_PATH_INVALID", "Archive contains an invalid path");
    }
    String normalized = Normalizer.normalize(rawName, Normalizer.Form.NFKC);
    if (normalized.indexOf('\\') >= 0
        || normalized.indexOf('\u2215') >= 0
        || normalized.indexOf('\u2044') >= 0
        || normalized.indexOf('\uff0f') >= 0
        || normalized.startsWith("/")
        || WINDOWS_ABSOLUTE.matcher(normalized).matches()) {
      throw rejected("ARCHIVE_PATH_ESCAPE", "Archive contains an unsafe path");
    }
    Path path;
    try {
      path = Path.of(normalized).normalize();
    } catch (RuntimeException e) {
      throw rejected("ARCHIVE_PATH_INVALID", "Archive contains an invalid path");
    }
    if (path.isAbsolute()
        || path.startsWith("..")
        || normalized.equals("..")
        || normalized.startsWith("../")
        || normalized.contains("/../")) {
      throw rejected("ARCHIVE_PATH_ESCAPE", "Archive path escapes the scanner workspace");
    }
  }

  private static void validateType(ArchiveEntry entry) {
    if (entry instanceof ZipArchiveEntry zip && zip.isUnixSymlink()) {
      throw rejected("ARCHIVE_LINK_REJECTED", "Archive links are not permitted");
    }
    if (entry instanceof TarArchiveEntry tar
        && (tar.isSymbolicLink()
            || tar.isLink()
            || tar.isCharacterDevice()
            || tar.isBlockDevice()
            || tar.isFIFO())) {
      throw rejected("ARCHIVE_SPECIAL_FILE_REJECTED", "Archive special files are not permitted");
    }
  }

  private static boolean looksNested(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return NESTED_SUFFIXES.stream().anyMatch(lower::endsWith);
  }

  private static void copyEntry(
      InputStream input, Path output, Budget budget, long maximum) throws IOException {
    long count = 0;
    byte[] buffer = new byte[32 * 1024];
    try (var target = Files.newOutputStream(output)) {
      int read;
      while (true) {
        budget.check();
        read = input.read(buffer);
        budget.check();
        if (read < 0) break;
        if (read == 0) continue;
        count += read;
        budget.addBytes(read);
        if (count > maximum) {
          throw rejected("ARCHIVE_ENTRY_TOO_LARGE", "Archive entry exceeds the single-file limit");
        }
        target.write(buffer, 0, read);
        budget.check();
      }
    }
  }

  private static void drain(InputStream input, Budget budget, long maximum) throws IOException {
    long count = 0;
    byte[] buffer = new byte[32 * 1024];
    while (true) {
      budget.check();
      int read = input.read(buffer);
      budget.check();
      if (read < 0) break;
      if (read == 0) continue;
      count += read;
      budget.addBytes(read);
      if (count > maximum) {
        throw rejected("ARCHIVE_ENTRY_TOO_LARGE", "Archive entry exceeds the single-file limit");
      }
    }
  }

  private static ScannerRequestException rejected(String code, String message) {
    return new ScannerRequestException(code, message, 422, false);
  }

  private static final class Budget {
    private final ResourceLimits limits;
    private final long ratioBudgetBytes;
    private final ScanDeadline deadline;
    private int entries;
    private long expandedBytes;
    private int nestedArchives;

    private Budget(ResourceLimits limits, long compressedBytes, ScanDeadline deadline) {
      this.limits = limits;
      this.deadline = deadline;
      long scaled = compressedBytes > Long.MAX_VALUE / MAX_EXPANSION_RATIO
          ? Long.MAX_VALUE : compressedBytes * MAX_EXPANSION_RATIO;
      this.ratioBudgetBytes = Math.max(MIN_RATIO_BUDGET_BYTES, scaled);
    }

    private void addEntry() {
      check();
      entries++;
      if (entries > limits.maxArchiveEntries()) {
        throw rejected("ARCHIVE_ENTRY_LIMIT", "Archive contains too many entries");
      }
    }

    private void addBytes(long count) {
      check();
      expandedBytes += count;
      if (expandedBytes > limits.maxUncompressedBytes()) {
        throw rejected("ARCHIVE_EXPANDED_LIMIT", "Archive expanded size exceeds the limit");
      }
      if (expandedBytes > ratioBudgetBytes) {
        throw rejected("ARCHIVE_EXPANSION_RATIO", "Archive expansion ratio is unsafe");
      }
    }

    private void check() {
      deadline.check();
    }
  }

  public record Inspection(int entries, long expandedBytes, int nestedArchives) {}
}
