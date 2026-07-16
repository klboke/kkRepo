package com.github.klboke.kkrepo.server.swift;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Buffers and inspects untrusted Swift source archives without executing package content. */
@Component
final class SwiftArchiveInspector {
  private static final Pattern VERSIONED_MANIFEST =
      Pattern.compile("Package@swift-(\\d+(?:\\.\\d+){0,2})\\.swift");
  private static final Pattern TOOLS_VERSION =
      Pattern.compile("^\\s*//\\s*swift-tools-version\\s*:\\s*(\\d+\\.\\d+(?:\\.\\d+)?).*$");
  private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
  private static final long RATIO_FLOOR = 1024L * 1024;
  private static final long DEFAULT_MAX_COMPRESSION_RATIO = 200;
  private static final Duration DEFAULT_MAX_INSPECTION_TIME = Duration.ofMinutes(2);

  private final long maxCompressedBytes;
  private final long maxExpandedBytes;
  private final long maxEntryBytes;
  private final int maxEntries;
  private final long maxCompressionRatio;
  private final long maxInspectionNanos;

  @Autowired
  SwiftArchiveInspector(
      @Value("${kkrepo.swift.archive.max-compressed-bytes:1073741824}") long maxCompressedBytes,
      @Value("${kkrepo.swift.archive.max-expanded-bytes:2147483648}") long maxExpandedBytes,
      @Value("${kkrepo.swift.archive.max-entries:100000}") int maxEntries,
      @Value("${kkrepo.swift.archive.max-entry-bytes:1073741824}") long maxEntryBytes,
      @Value("${kkrepo.swift.archive.max-compression-ratio:200}") long maxCompressionRatio,
      @Value("${kkrepo.swift.archive.max-inspection-seconds:120}") long maxInspectionSeconds) {
    this.maxCompressedBytes = Math.max(1L, maxCompressedBytes);
    this.maxExpandedBytes = Math.max(1L, maxExpandedBytes);
    this.maxEntryBytes = Math.max(1L, Math.min(maxEntryBytes, this.maxExpandedBytes));
    this.maxEntries = Math.max(1, maxEntries);
    this.maxCompressionRatio = Math.max(1L, maxCompressionRatio);
    this.maxInspectionNanos = Duration.ofSeconds(Math.max(1L, maxInspectionSeconds)).toNanos();
  }

  SwiftArchiveInspector(long maxCompressedBytes, long maxExpandedBytes, int maxEntries) {
    this(maxCompressedBytes, maxExpandedBytes, maxEntries, maxExpandedBytes);
  }

  SwiftArchiveInspector(
      long maxCompressedBytes, long maxExpandedBytes, int maxEntries, long maxEntryBytes) {
    this(maxCompressedBytes, maxExpandedBytes, maxEntries, maxEntryBytes,
        DEFAULT_MAX_COMPRESSION_RATIO, DEFAULT_MAX_INSPECTION_TIME.toSeconds());
  }

  InspectedArchive inspect(InputStream body) {
    Path buffered = null;
    try {
      buffered = Files.createTempFile("kkrepo-swift-", ".zip");
      MessageDigest archiveDigest = sha256();
      long compressed = copyBounded(body, buffered, archiveDigest);
      ensureZipMagic(buffered);
      InspectedEntries entries = inspectEntries(buffered, compressed);
      List<ManifestEntry> manifests = selectPackageRoot(entries);
      byte[] archiveHash = archiveDigest.digest();
      return new InspectedArchive(
          buffered,
          compressed,
          HexFormat.of().formatHex(archiveHash),
          Base64.getEncoder().encodeToString(archiveHash),
          manifests);
    } catch (IOException | RuntimeException e) {
      if (buffered != null) {
        try {
          Files.deleteIfExists(buffered);
        } catch (IOException ignored) {
        }
      }
      if (e instanceof SwiftExceptions.SwiftException swiftFailure) {
        throw swiftFailure;
      }
      throw new SwiftExceptions.UnprocessableEntity("Unable to inspect Swift source archive", e);
    }
  }

  private long copyBounded(InputStream body, Path target, MessageDigest digest) throws IOException {
    if (body == null) {
      throw new SwiftExceptions.BadRequest("source-archive is required");
    }
    long total = 0;
    try (var output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[64 * 1024];
      for (int read; (read = body.read(buffer)) >= 0;) {
        total += read;
        if (total > maxCompressedBytes) {
          throw new SwiftExceptions.ContentTooLarge("Swift source archive exceeds the upload limit");
        }
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }
    if (total == 0) {
      throw new SwiftExceptions.UnprocessableEntity("Swift source archive is empty");
    }
    return total;
  }

  private static void ensureZipMagic(Path file) throws IOException {
    try (InputStream input = Files.newInputStream(file)) {
      int first = input.read();
      int second = input.read();
      if (first != 'P' || second != 'K') {
        throw new SwiftExceptions.UnsupportedMediaType("source-archive must be a ZIP archive");
      }
    }
  }

  private InspectedEntries inspectEntries(Path file, long compressedSize) throws IOException {
    long started = System.nanoTime();
    long expanded = 0;
    int entries = 0;
    Set<String> exactNames = new HashSet<>();
    Set<String> caseFoldedNames = new HashSet<>();
    Set<String> fileNames = new HashSet<>();
    Set<String> requiredDirectories = new HashSet<>();
    Set<String> topLevelDirectories = new HashSet<>();
    List<Candidate> candidates = new ArrayList<>();
    try (ZipArchiveInputStream input = new ZipArchiveInputStream(
        new BufferedInputStream(Files.newInputStream(file)), "UTF-8", true, true)) {
      for (ZipArchiveEntry entry; (entry = input.getNextEntry()) != null;) {
        ensureWithinTime(started);
        if (++entries > maxEntries) {
          throw new SwiftExceptions.UnprocessableEntity("Swift source archive contains too many entries");
        }
        String name = safeName(entry);
        if (!exactNames.add(name) || !caseFoldedNames.add(name.toLowerCase(Locale.ROOT))) {
          throw new SwiftExceptions.UnprocessableEntity(
              "Swift source archive contains duplicate or case-conflicting entries");
        }
        ensureNoFileDirectoryConflict(
            name.toLowerCase(Locale.ROOT), entry.isDirectory(), fileNames, requiredDirectories);
        topLevelDirectories.add(topLevelDirectory(name, entry.isDirectory()));
        if (topLevelDirectories.size() > 1) {
          throw new SwiftExceptions.UnprocessableEntity(
              "Swift source archive must contain exactly one top-level directory");
        }
        if (entry.isDirectory()) {
          continue;
        }
        String leaf = leaf(name);
        boolean manifest = "Package.swift".equals(leaf)
            || VERSIONED_MANIFEST.matcher(leaf).matches();
        ByteArrayOutputStream captured = manifest ? new ByteArrayOutputStream() : null;
        long entryExpanded = 0;
        byte[] buffer = new byte[32 * 1024];
        for (int read; (read = input.read(buffer)) >= 0;) {
          expanded += read;
          entryExpanded += read;
          if (entryExpanded > maxEntryBytes) {
            throw new SwiftExceptions.ContentTooLarge(
                "Swift source archive entry exceeds the safe limit");
          }
          if (expanded > maxExpandedBytes) {
            throw new SwiftExceptions.ContentTooLarge(
                "Swift source archive expands beyond the safe limit");
          }
          if (expanded > RATIO_FLOOR && exceedsCompressionRatio(expanded, compressedSize)) {
            throw new SwiftExceptions.UnprocessableEntity(
                "Swift source archive compression ratio exceeds the safe limit");
          }
          ensureWithinTime(started);
          if (captured != null) {
            if (captured.size() + read > MAX_MANIFEST_BYTES) {
              throw new SwiftExceptions.ContentTooLarge("Swift package manifest is too large");
            }
            captured.write(buffer, 0, read);
          }
        }
        if (captured != null) {
          candidates.add(new Candidate(name, leaf, captured.toByteArray()));
        }
      }
    }
    if (topLevelDirectories.size() != 1) {
      throw new SwiftExceptions.UnprocessableEntity(
          "Swift source archive must contain exactly one top-level directory");
    }
    return new InspectedEntries(topLevelDirectories.iterator().next(), List.copyOf(candidates));
  }

  private boolean exceedsCompressionRatio(long expanded, long compressed) {
    long base = Math.max(1L, compressed);
    long whole = expanded / base;
    return whole > maxCompressionRatio
        || (whole == maxCompressionRatio && expanded % base != 0);
  }

  private static void ensureNoFileDirectoryConflict(
      String name,
      boolean directory,
      Set<String> fileNames,
      Set<String> requiredDirectories) {
    int slash = name.indexOf('/');
    while (slash >= 0) {
      String ancestor = name.substring(0, slash);
      if (fileNames.contains(ancestor)) {
        throw new SwiftExceptions.UnprocessableEntity(
            "Swift source archive contains a file/directory path conflict");
      }
      requiredDirectories.add(ancestor);
      slash = name.indexOf('/', slash + 1);
    }
    if (directory) {
      if (fileNames.contains(name)) {
        throw new SwiftExceptions.UnprocessableEntity(
            "Swift source archive contains a file/directory path conflict");
      }
      requiredDirectories.add(name);
    } else {
      if (requiredDirectories.contains(name)) {
        throw new SwiftExceptions.UnprocessableEntity(
            "Swift source archive contains a file/directory path conflict");
      }
      fileNames.add(name);
    }
  }

  private void ensureWithinTime(long started) {
    if (System.nanoTime() - started > maxInspectionNanos) {
      throw new SwiftExceptions.UnprocessableEntity(
          "Swift source archive inspection exceeded the time limit");
    }
  }

  private static String topLevelDirectory(String name, boolean directory) {
    int slash = name.indexOf('/');
    if (slash < 0) {
      if (directory) {
        return name;
      }
      throw new SwiftExceptions.UnprocessableEntity(
          "Swift source archive entries must be nested under one top-level directory");
    }
    return name.substring(0, slash);
  }

  private static String safeName(ZipArchiveEntry entry) {
    String raw = entry.getName();
    if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0
        || raw.startsWith("/") || raw.startsWith("//") || raw.matches("^[A-Za-z]:.*")
        || entry.isUnixSymlink()) {
      throw new SwiftExceptions.UnprocessableEntity("Swift source archive contains an unsafe entry");
    }
    String name = raw;
    while (name.endsWith("/") && name.length() > 1) {
      name = name.substring(0, name.length() - 1);
    }
    for (String segment : name.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw new SwiftExceptions.UnprocessableEntity(
            "Swift source archive contains path traversal");
      }
    }
    return name;
  }

  private static List<ManifestEntry> selectPackageRoot(InspectedEntries entries) {
    String expectedManifest = entries.topLevelDirectory() + "/Package.swift";
    Candidate root = entries.candidates().stream()
        .filter(candidate -> expectedManifest.equals(candidate.path()))
        .findFirst()
        .orElseThrow(() -> new SwiftExceptions.UnprocessableEntity(
            "Swift source archive must contain Package.swift at the top-level directory root"));
    String parent = parent(root.path());
    Map<String, ManifestEntry> selected = new LinkedHashMap<>();
    for (Candidate candidate : entries.candidates()) {
      if (!parent(candidate.path()).equals(parent)) {
        continue;
      }
      String filename = candidate.filename();
      String declaredTools = declaredToolsVersion(candidate.bytes(), filename);
      Matcher versioned = VERSIONED_MANIFEST.matcher(filename);
      String lookupVersion = "Package.swift".equals(filename)
          ? ""
          : versioned.matches() ? versioned.group(1) : declaredTools;
      ManifestEntry manifest = new ManifestEntry(
          filename,
          lookupVersion,
          declaredTools,
          candidate.bytes(),
          HexFormat.of().formatHex(sha256().digest(candidate.bytes())));
      if (selected.putIfAbsent(lookupVersion, manifest) != null) {
        throw new SwiftExceptions.UnprocessableEntity(
            "Swift source archive contains duplicate manifests for tools version " + lookupVersion);
      }
    }
    return List.copyOf(selected.values());
  }

  private static String declaredToolsVersion(byte[] bytes, String filename) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    int newline = text.indexOf('\n');
    String firstLine = newline < 0 ? text : text.substring(0, newline);
    Matcher tools = TOOLS_VERSION.matcher(firstLine.replace("\r", ""));
    if (!tools.matches()) {
      throw new SwiftExceptions.UnprocessableEntity(
          filename + " must declare // swift-tools-version on the first line");
    }
    String declared = tools.group(1);
    Matcher versioned = VERSIONED_MANIFEST.matcher(filename);
    if (versioned.matches() && !compatibleToolsVersion(versioned.group(1), declared)) {
      throw new SwiftExceptions.UnprocessableEntity(
          filename + " does not match its declared Swift tools version " + declared);
    }
    return declared;
  }

  private static boolean compatibleToolsVersion(String filenameVersion, String declared) {
    String[] expected = filenameVersion.split("\\.");
    String[] actual = declared.split("\\.");
    if (actual.length < expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if (!expected[i].equals(actual[i])) {
        return false;
      }
    }
    return true;
  }

  private static String leaf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  record InspectedArchive(
      Path file,
      long size,
      String sha256Hex,
      String sha256Base64,
      List<ManifestEntry> manifests) {}

  record ManifestEntry(
      String filename,
      String lookupToolsVersion,
      String declaredToolsVersion,
      byte[] bytes,
      String sha256Hex) {}

  private record InspectedEntries(String topLevelDirectory, List<Candidate> candidates) {}

  private record Candidate(String path, String filename, byte[] bytes) {}
}
