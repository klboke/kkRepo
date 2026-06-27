package com.github.klboke.kkrepo.server.cargo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class CargoCrateInspector {
  private CargoCrateInspector() {
  }

  static Manifest inspect(Path crateFile) {
    try (var file = Files.newInputStream(crateFile);
        var gzip = new GzipCompressorInputStream(file);
        var tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!entry.isFile() || !entry.getName().endsWith("/Cargo.toml")) {
          continue;
        }
        return parseManifest(tar);
      }
    } catch (IOException e) {
      throw new CargoExceptions.BadRequestException("Invalid .crate archive", e);
    }
    throw new CargoExceptions.BadRequestException(".crate archive does not contain Cargo.toml");
  }

  private static Manifest parseManifest(TarArchiveInputStream in) throws IOException {
    String section = "";
    String name = null;
    String version = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = stripComment(line).trim();
        if (trimmed.isBlank()) {
          continue;
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
          section = trimmed.substring(1, trimmed.length() - 1).trim();
          continue;
        }
        if (!"package".equals(section)) {
          continue;
        }
        int equals = trimmed.indexOf('=');
        if (equals < 0) {
          continue;
        }
        String key = trimmed.substring(0, equals).trim();
        String value = unquote(trimmed.substring(equals + 1).trim());
        if ("name".equals(key)) {
          name = value;
        } else if ("version".equals(key)) {
          version = value;
        }
      }
    }
    if (name == null || version == null) {
      throw new CargoExceptions.BadRequestException("Cargo.toml is missing package name or version");
    }
    return new Manifest(name, version);
  }

  private static String stripComment(String line) {
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
        continue;
      }
      if (c == '#' && !inString) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static String unquote(String value) {
    String trimmed = value.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  record Manifest(String name, String version) {
  }
}
