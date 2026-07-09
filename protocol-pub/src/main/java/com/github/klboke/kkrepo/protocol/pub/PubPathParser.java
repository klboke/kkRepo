package com.github.klboke.kkrepo.protocol.pub;

import java.nio.charset.StandardCharsets;

public final class PubPathParser {
  public PubPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    String path = normalize(percentDecode(raw));
    if (path.isBlank()) {
      return new PubPath(PubPath.Kind.ROOT, raw, null, null, null);
    }
    if (path.equals("api/packages/versions/new")) {
      return new PubPath(PubPath.Kind.PUBLISH_INIT, raw, null, null, null);
    }
    if (path.startsWith("api/packages/versions/upload/")) {
      String session = path.substring("api/packages/versions/upload/".length());
      return validSession(session)
          ? new PubPath(PubPath.Kind.PUBLISH_UPLOAD, raw, null, null, session)
          : new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    if (path.startsWith("api/packages/versions/finalize/")) {
      String session = path.substring("api/packages/versions/finalize/".length());
      return validSession(session)
          ? new PubPath(PubPath.Kind.PUBLISH_FINALIZE, raw, null, null, session)
          : new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    if (path.equals("api/package-names")) {
      return new PubPath(PubPath.Kind.PACKAGE_NAMES, raw, null, null, null);
    }
    if (path.equals("api/package-name-completion-data")) {
      return new PubPath(PubPath.Kind.PACKAGE_NAME_COMPLETION, raw, null, null, null);
    }
    if (path.startsWith("api/archives/")) {
      return parseNexusArchive(raw, path.substring("api/archives/".length()));
    }
    if (path.startsWith("api/packages/")) {
      return parsePackageApi(raw, path.substring("api/packages/".length()));
    }
    if (path.startsWith("packages/")) {
      return parseArchive(raw, path.substring("packages/".length()));
    }
    PubPath nexus = parseNexusContentPath(raw, path);
    if (nexus.kind() != PubPath.Kind.UNKNOWN) {
      return nexus;
    }
    return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
  }

  private static PubPath parseNexusArchive(String raw, String suffix) {
    if (suffix.contains("/") || !suffix.endsWith(".tar.gz")) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    String file = suffix.substring(0, suffix.length() - ".tar.gz".length());
    int separator = file.indexOf('-');
    if (separator <= 0 || separator == file.length() - 1) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    String packageName = file.substring(0, separator);
    String version = file.substring(separator + 1);
    if (!PubPackageName.isValid(packageName) || !PubVersions.isValid(version)) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    return new PubPath(PubPath.Kind.ARCHIVE, raw, packageName, version, null);
  }

  private static PubPath parsePackageApi(String raw, String suffix) {
    String[] parts = split(suffix);
    if (parts.length == 1 && PubPackageName.isValid(parts[0])) {
      return new PubPath(PubPath.Kind.PACKAGE_METADATA, raw, parts[0], null, null);
    }
    if (parts.length == 2 && parts[1].equals("advisories") && PubPackageName.isValid(parts[0])) {
      return new PubPath(PubPath.Kind.ADVISORIES, raw, parts[0], null, null);
    }
    if (parts.length == 3 && parts[1].equals("versions")
        && PubPackageName.isValid(parts[0]) && PubVersions.isValid(parts[2])) {
      return new PubPath(PubPath.Kind.VERSION_METADATA, raw, parts[0], parts[2], null);
    }
    return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
  }

  private static PubPath parseArchive(String raw, String suffix) {
    String[] parts = split(suffix);
    if (parts.length != 3 || !parts[1].equals("versions")) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    String file = parts[2];
    if (!file.endsWith(".tar.gz")) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    String version = file.substring(0, file.length() - ".tar.gz".length());
    if (!PubPackageName.isValid(parts[0]) || !PubVersions.isValid(version)) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    return new PubPath(PubPath.Kind.ARCHIVE, raw, parts[0], version, null);
  }

  private static PubPath parseNexusContentPath(String raw, String path) {
    String[] parts = split(path);
    if (parts.length != 3 || !PubPackageName.isValid(parts[0]) || !PubVersions.isValid(parts[1])) {
      return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
    }
    if (parts[2].equals("version.json")) {
      return new PubPath(PubPath.Kind.VERSION_JSON, raw, parts[0], parts[1], null);
    }
    String archive = parts[0] + "-" + parts[1] + ".tar.gz";
    if (parts[2].equals(archive)) {
      return new PubPath(PubPath.Kind.ARCHIVE, raw, parts[0], parts[1], null);
    }
    return new PubPath(PubPath.Kind.UNKNOWN, raw, null, null, null);
  }

  private static String[] split(String suffix) {
    return suffix.split("/", -1);
  }

  private static boolean validSession(String session) {
    return session != null
        && session.length() <= 128
        && session.chars().allMatch(ch ->
            (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '_');
  }

  private static String normalize(String path) {
    String normalized = path == null ? "" : path.trim();
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
