package com.github.klboke.kkrepo.protocol.arch;

import com.github.klboke.kkrepo.core.http.UriPathDecoder;

/** Strict parser for the flat pacman repository layout. */
public final class ArchPathParser {
  public ArchPath parse(String rawPath) {
    String raw = rawPath == null ? "" : rawPath;
    while (raw.startsWith("/")) raw = raw.substring(1);
    while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);
    if (raw.isEmpty()) {
      return new ArchPath(ArchPath.Kind.ROOT, "", null, null, null, null, null, null);
    }
    String[] segments = raw.split("/", -1);
    if (segments.length != 1) {
      // pacman repositories are flat: no nested paths are part of the protocol.
      return new ArchPath(ArchPath.Kind.UNKNOWN, raw, null, null, null, null, null, null);
    }
    String fileName = UriPathDecoder.decodeSegment(segments[0]);
    if (fileName.equals(".") || fileName.equals("..")) {
      throw new IllegalArgumentException("Invalid pacman path segment");
    }
    if (fileName.equals("public-key") || fileName.equals("gpg.key")) {
      return new ArchPath(ArchPath.Kind.PUBLIC_KEY, raw, fileName, null, null, null, null,
          null);
    }

    if (fileName.endsWith(".sig")) {
      String base = fileName.substring(0, fileName.length() - 4);
      if (isDatabase(base) || isFiles(base) || ArchPackageFilename.looksLikePackage(base)) {
        return new ArchPath(ArchPath.Kind.SIGNATURE, raw, fileName, databaseBase(base), null,
            null, null, null);
      }
      return new ArchPath(ArchPath.Kind.UNKNOWN, raw, fileName, null, null, null, null, null);
    }

    String databaseName = databaseName(fileName);
    if (databaseName != null) {
      if (fileName.equals(databaseName + ".db")) {
        return new ArchPath(ArchPath.Kind.DATABASE, raw, fileName, databaseName, null, null,
            null, "tar.gz");
      }
      if (fileName.startsWith(databaseName + ".db")) {
        return new ArchPath(ArchPath.Kind.DATABASE_TAR, raw, fileName, databaseName, null, null,
            null, compression(fileName));
      }
      if (fileName.equals(databaseName + ".files")) {
        return new ArchPath(ArchPath.Kind.FILES, raw, fileName, databaseName, null, null,
            null, "tar.gz");
      }
      return new ArchPath(ArchPath.Kind.FILES_TAR, raw, fileName, databaseName, null, null,
          null, compression(fileName));
    }

    ArchPackageFilename packageFile = ArchPackageFilename.parse(fileName);
    if (packageFile != null) {
      return new ArchPath(ArchPath.Kind.PACKAGE, raw, fileName, null, packageFile.name(),
          packageFile.version(), packageFile.architecture(), packageFile.compression());
    }
    return new ArchPath(ArchPath.Kind.UNKNOWN, raw, fileName, null, null, null, null, null);
  }

  private static boolean isDatabase(String base) {
    return databaseName(base) != null;
  }

  private static boolean isFiles(String base) {
    return databaseName(base) != null;
  }

  private static String databaseName(String fileName) {
    for (String suffix : new String[] {".db.tar.gz", ".db.tar.xz", ".db.tar.zst", ".db",
        ".files.tar.gz", ".files.tar.xz", ".files.tar.zst", ".files"}) {
      if (fileName.endsWith(suffix)) {
        String name = fileName.substring(0, fileName.length() - suffix.length());
        if (!name.isBlank() && name.indexOf('.') < 0) return name;
      }
    }
    return null;
  }

  private static String databaseBase(String base) {
    for (String suffix : new String[] {".db.tar.gz", ".db.tar.xz", ".db.tar.zst", ".db",
        ".files.tar.gz", ".files.tar.xz", ".files.tar.zst", ".files"}) {
      if (base.endsWith(suffix)) {
        return base.substring(0, base.length() - suffix.length());
      }
    }
    return null;
  }

  private static String compression(String fileName) {
    if (fileName.endsWith(".xz")) return "tar.xz";
    if (fileName.endsWith(".zst")) return "tar.zst";
    return "tar.gz";
  }
}
