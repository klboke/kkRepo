package com.github.klboke.kkrepo.scanner;

import com.github.klboke.kkrepo.security.scan.ScannerArtifactType;
import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
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
  private static final byte[] RPM_LEAD_MAGIC =
      new byte[] {(byte) 0xed, (byte) 0xab, (byte) 0xee, (byte) 0xdb};
  private static final byte[] RPM_HEADER_MAGIC =
      new byte[] {(byte) 0x8e, (byte) 0xad, (byte) 0xe8, 0x01};
  private static final long RPM_LEAD_BYTES = 96;
  private static final int RPM_HEADER_FIXED_BYTES = 16;
  private static final int RPM_INDEX_ENTRY_BYTES = 16;

  public Inspection inspect(
      Path input, ResourceLimits limits, Path workspace, ScanDeadline deadline) {
    try {
      deadline.check();
      Budget budget = new Budget(limits, Files.size(input), deadline);
      inspectPath(input, "", 0, budget, workspace, false, false);
      deadline.check();
      return new Inspection(budget.entries, budget.expandedBytes, budget.nestedArchives, null);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "ARCHIVE_INVALID", "Input archive is malformed or unsupported", 422, false, e);
    }
  }

  /**
   * Applies the ordinary archive budgets while accepting only links that remain inside a Conda
   * package. Conda payload tarballs commonly use symlinks and hardlinks, but the scanner never
   * extracts or executes them.
   */
  public Inspection inspectConda(
      Path input, ResourceLimits limits, Path workspace, ScanDeadline deadline) {
    try {
      deadline.check();
      Budget budget = new Budget(limits, Files.size(input), deadline);
      inspectPath(input, "", 0, budget, workspace, false, true);
      deadline.check();
      return new Inspection(
          budget.entries, budget.expandedBytes, budget.nestedArchives, budget.condaIndex);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "ARCHIVE_INVALID", "Conda package is malformed or unsupported", 422, false, e);
    }
  }

  /**
   * Applies the ordinary budgets to a Conan package while accepting links that remain inside the
   * package root. The subsequent Conan staging step does not materialize links.
   */
  public Inspection inspectConan(
      Path input, ResourceLimits limits, Path workspace, ScanDeadline deadline) {
    try {
      deadline.check();
      Budget budget = new Budget(limits, Files.size(input), deadline);
      inspectPath(input, "", 0, budget, workspace, false, true);
      deadline.check();
      return new Inspection(budget.entries, budget.expandedBytes, budget.nestedArchives, null);
    } catch (IOException e) {
      throw new ScannerRequestException(
          "ARCHIVE_INVALID", "Conan package is malformed or unsupported", 422, false, e);
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
        inspectPath(layer, "", 0, budget, workspace, true, false);
      }
      deadline.check();
      return new Inspection(budget.entries, budget.expandedBytes, budget.nestedArchives, null);
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
      boolean allowImageFilesystemMetadata,
      boolean allowSafeArchiveLinks) {
    try {
      if (inspectAsRpm(
          input, depth, budget, workspace, allowImageFilesystemMetadata,
          allowSafeArchiveLinks)) return;
      if (inspectAsArchive(
          input, depth, budget, workspace, allowImageFilesystemMetadata,
          allowSafeArchiveLinks)) return;
      inspectAsCompressed(input, hint, depth, budget, workspace,
          allowImageFilesystemMetadata, allowSafeArchiveLinks);
    } catch (ScannerRequestException e) {
      throw e;
    } catch (IOException e) {
      throw new ScannerRequestException(
          "ARCHIVE_INVALID", "Input archive is malformed or unsupported", 422, false, e);
    }
  }

  /**
   * Removes the RPM lead and header envelope before applying the ordinary compressed-archive
   * limits to its CPIO payload. Commons Compress does not recognize the RPM envelope itself.
   */
  private boolean inspectAsRpm(
      Path input,
      int depth,
      Budget budget,
      Path workspace,
      boolean allowImageFilesystemMetadata,
      boolean allowSafeArchiveLinks)
      throws IOException, ArchiveException, CompressorException {
    if (!startsWith(input, RPM_LEAD_MAGIC)) {
      return false;
    }
    budget.check();
    long payloadOffset = rpmPayloadOffset(input);
    try (InputStream file = Files.newInputStream(input)) {
      file.skipNBytes(payloadOffset);
      try (BufferedInputStream rawPayload = new BufferedInputStream(file)) {
        InputStream decodedPayload = rawPayload;
        try {
          String compressor = CompressorStreamFactory.detect(rawPayload);
          decodedPayload = CompressorStreamFactory.getSingleton()
              .createCompressorInputStream(compressor, rawPayload, false);
        } catch (CompressorException uncompressedPayload) {
          // Legacy RPM payloads may contain a plain CPIO stream.
        }
        try (BufferedInputStream expanded = new BufferedInputStream(decodedPayload)) {
          String archiveType;
          try {
            archiveType = ArchiveStreamFactory.detect(expanded);
          } catch (ArchiveException malformedPayload) {
            throw rejected(
                "RPM_PAYLOAD_INVALID", "RPM payload is not a supported CPIO archive");
          }
          if (!ArchiveStreamFactory.CPIO.equals(archiveType)) {
            throw rejected(
                "RPM_PAYLOAD_INVALID", "RPM payload is not a supported CPIO archive");
          }
          try (ArchiveInputStream<?> archive =
              ArchiveStreamFactory.DEFAULT.createArchiveInputStream(archiveType, expanded)) {
            inspectEntries(
                archive, depth, budget, workspace, allowImageFilesystemMetadata,
                allowSafeArchiveLinks);
          }
        }
      }
    }
    return true;
  }

  private static long rpmPayloadOffset(Path input) throws IOException {
    long fileSize = Files.size(input);
    if (fileSize < RPM_LEAD_BYTES + RPM_HEADER_FIXED_BYTES * 2L) {
      throw rejected("RPM_INVALID", "RPM wrapper is truncated");
    }
    try (SeekableByteChannel channel =
        Files.newByteChannel(input, StandardOpenOption.READ)) {
      long signatureEnd = rpmHeaderEnd(channel, RPM_LEAD_BYTES, fileSize);
      long mainHeaderStart = alignToEight(signatureEnd);
      long payloadStart = rpmHeaderEnd(channel, mainHeaderStart, fileSize);
      if (payloadStart >= fileSize) {
        throw rejected("RPM_PAYLOAD_INVALID", "RPM payload is missing");
      }
      return payloadStart;
    }
  }

  private static long rpmHeaderEnd(
      SeekableByteChannel channel, long start, long fileSize) throws IOException {
    ByteBuffer fixed = readExactly(channel, start, RPM_HEADER_FIXED_BYTES);
    for (int i = 0; i < RPM_HEADER_MAGIC.length; i++) {
      if (fixed.get(i) != RPM_HEADER_MAGIC[i]) {
        throw rejected("RPM_INVALID", "RPM header magic is invalid");
      }
    }
    fixed.order(ByteOrder.BIG_ENDIAN);
    long indexCount = Integer.toUnsignedLong(fixed.getInt(8));
    long dataBytes = Integer.toUnsignedLong(fixed.getInt(12));
    long indexBytes;
    long end;
    try {
      indexBytes = Math.multiplyExact(indexCount, RPM_INDEX_ENTRY_BYTES);
      end = Math.addExact(
          start,
          Math.addExact(
              RPM_HEADER_FIXED_BYTES,
              Math.addExact(indexBytes, dataBytes)));
    } catch (ArithmeticException overflow) {
      throw rejected("RPM_INVALID", "RPM header length is invalid");
    }
    if (end > fileSize) {
      throw rejected("RPM_INVALID", "RPM header exceeds the input size");
    }
    return end;
  }

  private static long alignToEight(long value) {
    long padding = (8 - (value & 7)) & 7;
    try {
      return Math.addExact(value, padding);
    } catch (ArithmeticException overflow) {
      throw rejected("RPM_INVALID", "RPM header alignment is invalid");
    }
  }

  private static ByteBuffer readExactly(
      SeekableByteChannel channel, long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    channel.position(position);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        throw rejected("RPM_INVALID", "RPM wrapper is truncated");
      }
    }
    buffer.flip();
    return buffer;
  }

  private static boolean startsWith(Path input, byte[] expected) throws IOException {
    if (Files.size(input) < expected.length) {
      return false;
    }
    try (InputStream stream = Files.newInputStream(input)) {
      byte[] actual = stream.readNBytes(expected.length);
      return Arrays.equals(actual, expected);
    }
  }

  private boolean inspectAsArchive(
      Path input,
      int depth,
      Budget budget,
      Path workspace,
      boolean allowImageFilesystemMetadata,
      boolean allowSafeArchiveLinks)
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
            archive, depth, budget, workspace, allowImageFilesystemMetadata,
            allowSafeArchiveLinks);
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
      boolean allowImageFilesystemMetadata,
      boolean allowSafeArchiveLinks)
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
                archive, depth, budget, workspace, allowImageFilesystemMetadata,
                allowSafeArchiveLinks);
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
      boolean allowImageFilesystemMetadata,
      boolean allowSafeArchiveLinks)
      throws IOException {
    while (true) {
      budget.check();
      ArchiveEntry entry = archive.getNextEntry();
      budget.check();
      if (entry == null) break;
      budget.addEntry();
      validatePath(entry.getName());
      if (!allowImageFilesystemMetadata) validateType(entry, allowSafeArchiveLinks);
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

      if (allowSafeArchiveLinks && "info/index.json".equals(entry.getName())) {
        if (budget.condaIndex != null) {
          throw rejected(
              "CONDA_INDEX_DUPLICATE", "Conda package contains duplicate info/index.json");
        }
        budget.condaIndex = readEntry(
            archive, budget, Math.min(2L * 1024 * 1024, budget.limits.maxSingleFileBytes()));
      } else if (looksNested(entry.getName())) {
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
              allowImageFilesystemMetadata,
              allowSafeArchiveLinks);
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

  private static void validateType(ArchiveEntry entry, boolean allowSafeArchiveLinks) {
    if (entry instanceof ZipArchiveEntry zip && zip.isUnixSymlink()) {
      throw rejected("ARCHIVE_LINK_REJECTED", "Archive links are not permitted");
    }
    if (entry instanceof TarArchiveEntry tar) {
      if (tar.isCharacterDevice() || tar.isBlockDevice() || tar.isFIFO()) {
        throw rejected(
            "ARCHIVE_SPECIAL_FILE_REJECTED", "Archive special files are not permitted");
      }
      if (tar.isSymbolicLink() || tar.isLink()) {
        if (!allowSafeArchiveLinks) {
          throw rejected(
              "ARCHIVE_SPECIAL_FILE_REJECTED", "Archive special files are not permitted");
        }
        validateLinkTarget(tar.getName(), tar.getLinkName(), tar.isSymbolicLink());
      }
    }
    if (entry instanceof CpioArchiveEntry cpio
        && !cpio.isRegularFile()
        && !cpio.isDirectory()) {
      throw rejected("ARCHIVE_SPECIAL_FILE_REJECTED", "Archive special files are not permitted");
    }
  }

  static void validateLinkTarget(
      String rawEntryName, String rawTarget, boolean relativeToParent) {
    validatePath(rawEntryName);
    if (rawTarget == null || rawTarget.isBlank() || rawTarget.indexOf('\0') >= 0) {
      throw rejected("ARCHIVE_LINK_ESCAPE", "Archive contains an unsafe link target");
    }
    String target = Normalizer.normalize(rawTarget, Normalizer.Form.NFKC);
    if (target.indexOf('\\') >= 0
        || target.indexOf('\u2215') >= 0
        || target.indexOf('\u2044') >= 0
        || target.indexOf('\uff0f') >= 0
        || target.startsWith("/")
        || WINDOWS_ABSOLUTE.matcher(target).matches()) {
      throw rejected("ARCHIVE_LINK_ESCAPE", "Archive contains an unsafe link target");
    }
    try {
      Path entry = Path.of(Normalizer.normalize(rawEntryName, Normalizer.Form.NFKC)).normalize();
      Path base = relativeToParent && entry.getParent() != null
          ? entry.getParent() : Path.of("");
      Path resolved = base.resolve(target).normalize();
      if (resolved.isAbsolute() || resolved.startsWith("..")) {
        throw rejected("ARCHIVE_LINK_ESCAPE", "Archive link escapes the scanner workspace");
      }
    } catch (ScannerRequestException e) {
      throw e;
    } catch (RuntimeException e) {
      throw rejected("ARCHIVE_LINK_ESCAPE", "Archive contains an unsafe link target");
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

  private static byte[] readEntry(InputStream input, Budget budget, long maximum)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    long count = 0;
    byte[] buffer = new byte[16 * 1024];
    while (true) {
      budget.check();
      int read = input.read(buffer);
      budget.check();
      if (read < 0) break;
      if (read == 0) continue;
      count += read;
      budget.addBytes(read);
      if (count > maximum) {
        throw rejected("CONDA_INDEX_TOO_LARGE", "Conda info/index.json exceeds the limit");
      }
      output.write(buffer, 0, read);
    }
    if (output.size() == 0) {
      throw rejected("CONDA_INDEX_EMPTY", "Conda info/index.json is empty");
    }
    return output.toByteArray();
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
    private byte[] condaIndex;

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

  public record Inspection(
      int entries, long expandedBytes, int nestedArchives, byte[] condaIndex) {
    public Inspection(int entries, long expandedBytes, int nestedArchives) {
      this(entries, expandedBytes, nestedArchives, null);
    }

    public Inspection {
      condaIndex = condaIndex == null ? null : condaIndex.clone();
    }

    @Override
    public byte[] condaIndex() {
      return condaIndex == null ? null : condaIndex.clone();
    }
  }
}
