package com.github.klboke.kkrepo.server.composer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.composer.ComposerPackageName;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

@Component
final class ComposerArchiveInspector {
  private static final int MAX_ENTRIES = 20_000;
  private static final long MAX_UNCOMPRESSED = 2L * 1024 * 1024 * 1024;
  private static final int MAX_COMPOSER_JSON = 2 * 1024 * 1024;
  private final ObjectMapper objectMapper;

  ComposerArchiveInspector(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  Inspected inspect(Path file, String fileName, String nameOverride, String versionOverride) {
    try {
      byte[] json = isZip(file, fileName) ? inspectZip(file) : inspectTar(file, fileName);
      Map<String, Object> metadata = objectMapper.readValue(json, new TypeReference<>() {});
      String archiveName = string(metadata.get("name"));
      String normalizedArchiveName = strictPackageName(archiveName, "composer.json name");
      String normalizedOverride = strictPackageName(nameOverride, "composer.name");
      if (normalizedArchiveName != null && normalizedOverride != null
          && !normalizedArchiveName.equals(normalizedOverride)) {
        throw bad("Composer upload name does not match archive composer.json");
      }
      String effectiveName = nonBlank(normalizedOverride, normalizedArchiveName);
      if (effectiveName == null) throw bad("Composer upload requires composer.name or composer.json name");
      String archiveVersion = string(metadata.get("version"));
      String version = nonBlank(versionOverride, archiveVersion);
      if (version == null) throw bad("Composer upload requires composer.version or composer.json version");
      version = requireVersion(version);
      if (archiveVersion != null && versionOverride != null && !archiveVersion.trim().equals(versionOverride.trim())) {
        throw bad("Composer upload version does not match archive composer.json");
      }
      Map<String, Object> normalized = new LinkedHashMap<>(metadata);
      normalized.put("name", effectiveName);
      normalized.put("version", version);
      normalized.remove("repositories");
      normalized.remove("config");
      return new Inspected(
          effectiveName, version, Collections.unmodifiableMap(new LinkedHashMap<>(normalized)));
    } catch (ComposerExceptions.BadRequestException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw bad("Invalid Composer archive: " + e.getMessage(), e);
    }
  }

  private byte[] inspectZip(Path file) throws IOException {
    byte[] found = null;
    String composerPath = null;
    Set<String> paths = new HashSet<>();
    int entries = 0;
    long expanded = 0;
    try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String path = validateEntry(entry.getName());
        if (!paths.add(path)) throw bad("Composer archive contains duplicate entries");
        if (++entries > MAX_ENTRIES) throw bad("Composer archive has too many entries");
        if (entry.getSize() > 0 && (expanded += entry.getSize()) > MAX_UNCOMPRESSED) {
          throw bad("Composer archive expands beyond the configured safety limit");
        }
        if (!entry.isDirectory() && isComposerJson(path)) {
          if (found != null) throw bad("Composer archive contains multiple composer.json files");
          composerPath = path;
          found = readLimited(zip);
        }
      }
    }
    if (found == null) throw bad("Composer archive does not contain composer.json");
    validateLayout(paths, composerPath);
    return found;
  }

  private byte[] inspectTar(Path file, String fileName) throws IOException {
    byte[] found = null;
    String composerPath = null;
    Set<String> paths = new HashSet<>();
    int entries = 0;
    long expanded = 0;
    try (InputStream source = Files.newInputStream(file);
        InputStream decompressed = decompressor(source, fileName);
        TarArchiveInputStream tar = new TarArchiveInputStream(decompressed)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextTarEntry()) != null) {
        String path = validateEntry(entry.getName());
        if (!paths.add(path)) throw bad("Composer archive contains duplicate entries");
        if (entry.isSymbolicLink() || entry.isLink()) throw bad("Composer archive contains links");
        if (++entries > MAX_ENTRIES) throw bad("Composer archive has too many entries");
        if (entry.getSize() > 0 && (expanded += entry.getSize()) > MAX_UNCOMPRESSED) {
          throw bad("Composer archive expands beyond the configured safety limit");
        }
        if (entry.isFile() && isComposerJson(path)) {
          if (found != null) throw bad("Composer archive contains multiple composer.json files");
          composerPath = path;
          found = readLimited(tar);
        }
      }
    }
    if (found == null) throw bad("Composer archive does not contain composer.json");
    validateLayout(paths, composerPath);
    return found;
  }

  private static InputStream decompressor(InputStream source, String fileName) throws IOException {
    String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
      return new GzipCompressorInputStream(source);
    }
    if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) {
      return new BZip2CompressorInputStream(source);
    }
    if (lower.endsWith(".tar")) return source;
    throw bad("Unsupported Composer archive type: " + fileName);
  }

  private static boolean isZip(Path file, String fileName) throws IOException {
    String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".zip")) return true;
    try (InputStream in = Files.newInputStream(file)) {
      return in.read() == 'P' && in.read() == 'K';
    }
  }

  private static byte[] readLimited(InputStream input) throws IOException {
    byte[] value = input.readNBytes(MAX_COMPOSER_JSON + 1);
    if (value.length > MAX_COMPOSER_JSON) throw bad("composer.json is too large");
    return value;
  }

  private static boolean isComposerJson(String path) {
    String value = path.replace('\\', '/');
    return value.equals("composer.json")
        || (value.endsWith("/composer.json") && value.indexOf('/') == value.lastIndexOf('/'));
  }

  private static String validateEntry(String name) {
    if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")) {
      throw bad("Composer archive contains an invalid path");
    }
    String normalized = name.replace('\\', '/');
    if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    if (normalized.isBlank()) throw bad("Composer archive contains an invalid path");
    for (String part : normalized.split("/", -1)) {
      if (part.equals("..") || part.isEmpty()) throw bad("Composer archive contains path traversal");
    }
    return normalized;
  }

  private static void validateLayout(Set<String> paths, String composerPath) {
    int slash = composerPath == null ? -1 : composerPath.indexOf('/');
    if (slash < 0) return;
    String topLevel = composerPath.substring(0, slash);
    for (String path : paths) {
      if (!path.equals(topLevel) && !path.startsWith(topLevel + "/")) {
        throw bad("Composer archive must contain a single top-level directory");
      }
    }
  }

  private static String requireVersion(String value) {
    String version = value == null ? null : value.trim();
    if (version == null || version.isEmpty() || version.length() > 255
        || version.chars().anyMatch(Character::isWhitespace)) {
      throw bad("Invalid Composer version: " + value);
    }
    return version;
  }

  private static String strictPackageName(String value, String field) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    String normalized = ComposerPackageName.normalize(trimmed);
    if (!trimmed.equals(normalized) || !ComposerPackageName.isValid(normalized)) {
      throw bad("Invalid " + field + ": " + value);
    }
    return normalized;
  }

  private static String nonBlank(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) return preferred.trim();
    if (fallback != null && !fallback.isBlank()) return fallback.trim();
    return null;
  }

  private static String string(Object value) {
    return value instanceof String s && !s.isBlank() ? s.trim() : null;
  }

  private static ComposerExceptions.BadRequestException bad(String message) {
    return new ComposerExceptions.BadRequestException(message);
  }

  private static ComposerExceptions.BadRequestException bad(String message, Throwable cause) {
    return new ComposerExceptions.BadRequestException(message, cause);
  }

  record Inspected(String name, String version, Map<String, Object> metadata) {
  }
}
