package com.github.klboke.kkrepo.server.openvsx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.openvsx.OpenVsxCoordinates;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/**
 * Bounded, non-executing VSIX inspector.
 *
 * <p>Only the canonical {@code extension/package.json} (or a single root {@code package.json}) is
 * read. Symlinks, absolute entries, traversal and oversized members are rejected before a
 * publication becomes visible.
 */
@Component
final class OpenVsxVsixInspector {
  private static final int MAX_PACKAGE_JSON_BYTES = 4 * 1024 * 1024;
  private static final int MAX_ENTRIES = 100_000;
  private static final long MAX_TOTAL_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;

  private final ObjectMapper objectMapper;

  OpenVsxVsixInspector(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  Inspected inspect(Path file, String submittedFileName) {
    try (ZipInputStream zip = new BoundedZipInputStream(
        Files.newInputStream(file), MAX_TOTAL_ENTRY_BYTES)) {
      ZipEntry selected = null;
      Map<String, byte[]> candidates = new LinkedHashMap<>();
      ZipEntry entry;
      int entries = 0;
      long totalEntryBytes = 0;
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) {
          throw new MavenExceptions.BadRequestException("VSIX contains too many entries");
        }
        long declaredSize = entry.getSize();
        if (declaredSize > 0) {
          if (declaredSize > MAX_TOTAL_ENTRY_BYTES - totalEntryBytes) {
            throw new MavenExceptions.BadRequestException(
                "VSIX payload is too large to inspect");
          }
          totalEntryBytes += declaredSize;
        }
        String name = entry.getName();
        validateEntryName(name);
        if (entry.isDirectory()) continue;
        if (name.equals("extension/package.json") || name.equals("package.json")) {
          candidates.put(name, readBounded(zip, MAX_PACKAGE_JSON_BYTES));
          selected = entry;
        }
      }
      if (candidates.isEmpty()) {
        throw new MavenExceptions.BadRequestException(
            "VSIX does not contain extension/package.json");
      }
      byte[] packageJson = candidates.containsKey("extension/package.json")
          ? candidates.get("extension/package.json")
          : candidates.get("package.json");
      Map<String, Object> parsed = objectMapper.readValue(
          packageJson, new TypeReference<LinkedHashMap<String, Object>>() {});
      String publisher = string(parsed.get("publisher"));
      String name = string(parsed.get("name"));
      String version = string(parsed.get("version"));
      if (!OpenVsxCoordinates.validName(publisher) || !OpenVsxCoordinates.validName(name)) {
        throw new MavenExceptions.BadRequestException(
            "VSIX package.json must declare a valid publisher and name");
      }
      if (!OpenVsxCoordinates.validVersion(version)) {
        throw new MavenExceptions.BadRequestException(
            "VSIX package.json must declare a valid version");
      }
      String targetPlatform = firstNonBlank(
          string(parsed.get("targetPlatform")), string(parsed.get("targetPlatformName")));
      if (!OpenVsxCoordinates.validTargetPlatform(targetPlatform)) {
        targetPlatform = null;
      }
      boolean prerelease = Boolean.TRUE.equals(parsed.get("preview"))
          || version.contains("-");
      String platformSuffix = targetPlatform == null || targetPlatform.isBlank()
          || "universal".equals(targetPlatform) ? "" : "-" + targetPlatform;
      String fileName = submittedFileName == null || submittedFileName.isBlank()
          ? publisher + "." + name + "-" + version + platformSuffix + ".vsix"
          : safeFileName(submittedFileName);
      return new Inspected(publisher, name, version, targetPlatform, prerelease, fileName,
          string(parsed.get("displayName")), string(parsed.get("description")), parsed);
    } catch (IOException error) {
      throw new MavenExceptions.BadRequestException("Invalid VSIX archive", error);
    }
  }

  private static void validateEntryName(String name) {
    if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
        || name.indexOf('\0') >= 0) {
      throw new MavenExceptions.BadRequestException("Unsafe VSIX entry");
    }
    String normalized = name.replace('\\', '/');
    for (String segment : normalized.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        throw new MavenExceptions.BadRequestException("Unsafe VSIX entry");
      }
    }
    if (normalized.codePoints().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
      throw new MavenExceptions.BadRequestException("Unsafe VSIX entry");
    }
  }

  private static String safeFileName(String value) {
    String fileName = value.replace('\\', '/');
    int slash = fileName.lastIndexOf('/');
    if (slash >= 0) fileName = fileName.substring(slash + 1);
    if (fileName.isBlank() || fileName.contains("..")) {
      throw new MavenExceptions.BadRequestException("Invalid VSIX file name");
    }
    return fileName;
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      total += read;
      if (total > limit) {
        throw new MavenExceptions.BadRequestException("VSIX package.json is too large");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  record Inspected(
      String namespace,
      String name,
      String version,
      String targetPlatform,
      boolean prerelease,
      String fileName,
      String displayName,
      String description,
      Map<String, Object> packageJson) {
  }
}
