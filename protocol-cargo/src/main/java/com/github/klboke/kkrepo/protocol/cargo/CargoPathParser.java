package com.github.klboke.kkrepo.protocol.cargo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CargoPathParser {
  public CargoPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    String path = normalize(percentDecode(raw));
    if (path.isBlank()) {
      return new CargoPath(CargoPath.Kind.ROOT, raw, null, null);
    }
    if (CargoIndexPath.CONFIG.equals(path)) {
      return new CargoPath(CargoPath.Kind.CONFIG, raw, null, null);
    }
    if (path.equals("api/v1/crates")) {
      return new CargoPath(CargoPath.Kind.SEARCH, raw, null, null);
    }
    if (path.equals("api/v1/crates/new")) {
      return new CargoPath(CargoPath.Kind.PUBLISH, raw, null, null);
    }
    if (path.startsWith("api/v1/crates/")) {
      return parseApi(raw, path.substring("api/v1/crates/".length()));
    }
    if (path.startsWith("crates/")) {
      return parseCrateDownload(raw, path.substring("crates/".length()));
    }
    return parseIndex(raw, path);
  }

  private static CargoPath parseApi(String raw, String suffix) {
    String[] parts = Arrays.stream(suffix.split("/"))
        .filter(s -> !s.isBlank())
        .toArray(String[]::new);
    if (parts.length == 3 && parts[2].equals("owners") && CargoCrateName.isValid(parts[0])) {
      return new CargoPath(CargoPath.Kind.OWNERS, raw, parts[0], null);
    }
    if (parts.length != 3 || !CargoCrateName.isValid(parts[0]) || parts[1].isBlank()) {
      return new CargoPath(CargoPath.Kind.UNKNOWN, raw, null, null);
    }
    return switch (parts[2]) {
      case "download" -> new CargoPath(CargoPath.Kind.DOWNLOAD, raw, parts[0], parts[1]);
      case "yank" -> new CargoPath(CargoPath.Kind.YANK, raw, parts[0], parts[1]);
      case "unyank" -> new CargoPath(CargoPath.Kind.UNYANK, raw, parts[0], parts[1]);
      default -> new CargoPath(CargoPath.Kind.UNKNOWN, raw, null, null);
    };
  }

  private static CargoPath parseCrateDownload(String raw, String suffix) {
    String[] parts = Arrays.stream(suffix.split("/"))
        .filter(s -> !s.isBlank())
        .toArray(String[]::new);
    if (parts.length != 3 || !CargoCrateName.isValid(parts[0]) || parts[1].isBlank()) {
      return new CargoPath(CargoPath.Kind.UNKNOWN, raw, null, null);
    }
    if (parts[2].equals("download")
        || parts[2].equals(parts[0] + "-" + parts[1] + ".crate")) {
      return new CargoPath(CargoPath.Kind.DOWNLOAD, raw, parts[0], parts[1]);
    }
    return new CargoPath(CargoPath.Kind.UNKNOWN, raw, null, null);
  }

  private static CargoPath parseIndex(String raw, String path) {
    String[] parts = path.split("/");
    String candidate = parts.length == 0 ? "" : parts[parts.length - 1];
    if (!CargoCrateName.isValid(candidate)) {
      return new CargoPath(CargoPath.Kind.UNKNOWN, raw, null, null);
    }
    String expected = CargoIndexPath.forCrate(candidate);
    if (!expected.equals(path)) {
      return new CargoPath(CargoPath.Kind.UNKNOWN, raw, null, null);
    }
    return new CargoPath(CargoPath.Kind.INDEX, raw, candidate, null);
  }

  private static String normalize(String path) {
    String normalized = path == null ? "" : path.trim().replaceAll("/+", "/");
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String percentDecode(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    byte[] decoded = new byte[bytes.length];
    int out = 0;
    for (int i = 0; i < bytes.length; i++) {
      byte b = bytes[i];
      if (b == '%' && i + 2 < bytes.length) {
        int hi = hex(bytes[i + 1]);
        int lo = hex(bytes[i + 2]);
        if (hi >= 0 && lo >= 0) {
          decoded[out++] = (byte) ((hi << 4) + lo);
          i += 2;
          continue;
        }
      }
      decoded[out++] = b;
    }
    return new String(decoded, 0, out, StandardCharsets.UTF_8);
  }

  private static int hex(byte b) {
    if (b >= '0' && b <= '9') return b - '0';
    if (b >= 'a' && b <= 'f') return b - 'a' + 10;
    if (b >= 'A' && b <= 'F') return b - 'A' + 10;
    return -1;
  }
}
