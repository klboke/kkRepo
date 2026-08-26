package com.github.klboke.kkrepo.server.goartifact;

import com.github.klboke.kkrepo.protocol.goartifact.GoModulePaths;
import com.github.klboke.kkrepo.protocol.goartifact.GoVersions;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Component;

/** Bounded validation for the official Go module zip layout. */
@Component
public class GoModuleArchiveInspector {
  static final long MAX_COMPRESSED_BYTES = 500L * 1024 * 1024;
  static final long MAX_EXPANDED_BYTES = 500L * 1024 * 1024;
  static final long MAX_GO_MOD_OR_LICENSE_BYTES = 16L * 1024 * 1024;
  static final int MAX_ENTRIES = 100_000;
  private static final Pattern MODULE_DIRECTIVE = Pattern.compile(
      "(?m)^[\\t ]*module[\\t ]+(?:\"([^\"\\r\\n]+)\"|([^\\s]+))[\\t ]*(?://.*)?$");
  private static final Set<String> WINDOWS_RESERVED = Set.of(
      "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6",
      "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6",
      "lpt7", "lpt8", "lpt9");

  private final long maxCompressedBytes;
  private final long maxExpandedBytes;
  private final long maxGoModOrLicenseBytes;
  private final int maxEntries;

  public GoModuleArchiveInspector() {
    this(MAX_COMPRESSED_BYTES, MAX_EXPANDED_BYTES, MAX_GO_MOD_OR_LICENSE_BYTES, MAX_ENTRIES);
  }

  GoModuleArchiveInspector(
      long maxCompressedBytes,
      long maxExpandedBytes,
      long maxGoModOrLicenseBytes,
      int maxEntries) {
    this.maxCompressedBytes = Math.max(1, maxCompressedBytes);
    this.maxExpandedBytes = Math.max(1, maxExpandedBytes);
    this.maxGoModOrLicenseBytes = Math.max(1, maxGoModOrLicenseBytes);
    this.maxEntries = Math.max(1, maxEntries);
  }

  public Inspected inspect(Path archive, String requestedVersion) {
    String version = GoVersions.requireCanonical(requestedVersion);
    try {
      long compressed = Files.size(archive);
      if (compressed == 0) throw bad("Go module archive is empty");
      if (compressed > maxCompressedBytes) throw bad("Go module archive exceeds 500 MiB");
      return inspectEntries(archive, version);
    } catch (IOException error) {
      throw new IllegalArgumentException("Unable to inspect Go module archive", error);
    }
  }

  private Inspected inspectEntries(Path archive, String requestedVersion) throws IOException {
    String root = null;
    String module = null;
    long expanded = 0;
    int entries = 0;
    byte[] goMod = null;
    PathCollisionChecker collisions = new PathCollisionChecker();
    try (ZipArchiveInputStream input = new ZipArchiveInputStream(
        new BufferedInputStream(Files.newInputStream(archive)), "UTF-8", true, true)) {
      for (ZipArchiveEntry entry; (entry = input.getNextEntry()) != null;) {
        if (++entries > maxEntries) throw bad("Go module archive contains too many entries");
        if (!input.canReadEntryData(entry)) throw bad("Go module archive contains an unsupported entry");
        if (entry.isUnixSymlink() || irregularEntry(entry)) {
          throw bad("Go module archive must contain only regular files and directories");
        }
        boolean directory = entry.isDirectory();
        String name = safeName(entry.getName());
        String coordinateRoot = coordinateRoot(name);
        if (root == null) {
          root = coordinateRoot;
          Coordinate coordinate = coordinate(root);
          module = coordinate.module();
          if (!requestedVersion.equals(coordinate.version())) {
            throw bad("Archive version " + coordinate.version()
                + " does not match upload version " + requestedVersion);
          }
        } else if (!root.equals(coordinateRoot)) {
          throw bad("Go module archive must contain exactly one top-level module directory");
        }

        String normalizedName = trimTrailingSlash(name);
        if (normalizedName.equals(root)) {
          if (!directory) {
            throw bad("Go module files must be below the <module>@<version> directory");
          }
          continue;
        }
        if (!normalizedName.startsWith(root + "/")) {
          throw bad("Go module files must be below the <module>@<version> directory");
        }
        String relative = normalizedName.substring(root.length() + 1);
        requireFilePath(relative);
        collisions.add(relative, directory);
        // Directory records do not contribute module content bytes, but their paths and
        // case-fold collisions are still part of the official module ZIP validation.
        if (directory) continue;
        long remainingExpandedBytes = Math.max(0, maxExpandedBytes - expanded);
        long read;
        if (relative.equals("go.mod")) {
          if (goMod != null) throw bad("Go module archive contains more than one root go.mod");
          goMod = readBounded(
              input, Math.min(maxGoModOrLicenseBytes, remainingExpandedBytes), "go.mod");
          read = goMod.length;
        } else {
          String leaf = relative.substring(relative.lastIndexOf('/') + 1);
          if (leaf.equalsIgnoreCase("go.mod")) {
            throw bad("Go module archive must contain only one correctly-cased root go.mod");
          }
          long entryLimit = relative.equals("LICENSE")
              ? maxGoModOrLicenseBytes
              : maxExpandedBytes;
          read = drainBounded(input, Math.min(entryLimit, remainingExpandedBytes), relative);
        }
        if (entry.getSize() >= 0 && entry.getSize() != read) {
          throw bad("Go module archive entry size does not match its ZIP header: " + relative);
        }
        expanded += read;
        if (expanded > maxExpandedBytes) throw bad("Go module archive expands beyond 500 MiB");
      }
    }
    if (root == null || module == null) throw bad("Go module archive contains no entries");
    byte[] effectiveGoMod = goMod == null
        ? ("module " + module + "\n").getBytes(StandardCharsets.UTF_8)
        : validateGoMod(module, goMod);
    return new Inspected(module, GoModulePaths.escape(module), requestedVersion, effectiveGoMod);
  }

  private Coordinate coordinate(String root) {
    int separator = root.lastIndexOf('@');
    if (separator <= 0 || separator == root.length() - 1) {
      throw bad("Top-level directory must be <module>@<version>");
    }
    // Unlike proxy URL paths, the module and version inside a module zip are stored directly,
    // without the !-based case encoding.
    String module = GoModulePaths.require(root.substring(0, separator));
    String version = GoVersions.requireCanonical(root.substring(separator + 1));
    GoModulePaths.requireVersionSuffix(module, version);
    return new Coordinate(module, version);
  }

  private byte[] validateGoMod(String module, byte[] bytes) {
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw bad("Root go.mod must be valid UTF-8");
    }
    Matcher matcher = MODULE_DIRECTIVE.matcher(text);
    if (!matcher.find()) throw bad("Root go.mod is missing a module directive");
    String declared = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
    if (!module.equals(declared)) {
      throw bad("go.mod module directive does not match archive root: " + declared);
    }
    return bytes;
  }

  private static String safeName(String raw) {
    if (raw == null || raw.isBlank() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0
        || raw.startsWith("/") || raw.contains("//")) {
      throw bad("Go module archive contains an unsafe path");
    }
    String name = raw;
    String normalized = trimTrailingSlash(name);
    for (String segment : normalized.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw bad("Go module archive contains an unsafe path: " + raw);
      }
    }
    return name;
  }

  private static String coordinateRoot(String name) {
    String normalized = trimTrailingSlash(name);
    int separator = normalized.indexOf('@');
    if (separator <= 0) {
      throw bad("Top-level directory must be <module>@<version>");
    }
    int slash = normalized.indexOf('/', separator + 1);
    return slash < 0 ? normalized : normalized.substring(0, slash);
  }

  private static String trimTrailingSlash(String value) {
    String normalized = value;
    while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized;
  }

  private static void requireFilePath(String relative) {
    if (relative.isBlank() || relative.startsWith("/") || relative.endsWith("/")
        || relative.contains("//")) {
      throw bad("Go module archive contains an invalid file path: " + relative);
    }
    for (String segment : relative.split("/", -1)) {
      if (segment.isEmpty() || segment.codePoints().allMatch(ch -> ch == '.')
          || segment.endsWith(".")) {
        throw bad("Go module archive contains an invalid file path: " + relative);
      }
      String base = segment;
      int dot = base.indexOf('.');
      if (dot >= 0) base = base.substring(0, dot);
      if (isWindowsReserved(base)) {
        throw bad("Go module archive contains a Windows-reserved file path: " + relative);
      }
      if (!segment.codePoints().allMatch(GoModuleArchiveInspector::fileNameCharacter)) {
        throw bad("Go module archive contains an invalid file path: " + relative);
      }
    }
  }

  private static boolean fileNameCharacter(int ch) {
    if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z') {
      return true;
    }
    if (ch < 128) return "!#$%&()+,-.=@[]^_{}~ ".indexOf(ch) >= 0;
    return Character.isLetter(ch);
  }

  private static boolean isWindowsReserved(String value) {
    return WINDOWS_RESERVED.contains(value.toLowerCase(Locale.ROOT));
  }

  private static boolean irregularEntry(ZipArchiveEntry entry) {
    int type = entry.getUnixMode() & 0170000;
    return type != 0 && type != 0100000 && type != 0040000;
  }

  private static String foldCase(String value) {
    return value.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
  }

  private static final class PathCollisionChecker {
    private final Map<String, PathInfo> paths = new HashMap<>();

    void add(String path, boolean directory) {
      String folded = foldCase(path);
      PathInfo prior = paths.get(folded);
      if (prior == null) {
        paths.put(folded, new PathInfo(path, directory));
      } else if (!prior.path().equals(path)) {
        throw bad("Go module archive contains case-conflicting paths: "
            + prior.path() + " and " + path);
      } else if (prior.directory() != directory) {
        throw bad("Go module archive path is both a file and directory: " + path);
      } else if (!directory) {
        throw bad("Go module archive contains duplicate file: " + path);
      }
      int separator = path.lastIndexOf('/');
      if (separator > 0) add(path.substring(0, separator), true);
    }
  }

  private record PathInfo(String path, boolean directory) {
  }

  private static byte[] readBounded(InputStream input, long limit, String name) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[32 * 1024];
    long total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      total += read;
      if (total > limit) throw bad("Go module archive entry is too large: " + name);
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static long drainBounded(InputStream input, long limit, String name) throws IOException {
    byte[] buffer = new byte[32 * 1024];
    long total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      total += read;
      if (total > limit) throw bad("Go module archive entry is too large: " + name);
    }
    return total;
  }

  private static IllegalArgumentException bad(String message) {
    return new IllegalArgumentException(message);
  }

  public record Inspected(String module, String escapedModule, String version, byte[] goMod) {
    public Inspected {
      goMod = goMod.clone();
    }

    @Override
    public byte[] goMod() {
      return goMod.clone();
    }
  }

  private record Coordinate(String module, String version) {
  }
}
