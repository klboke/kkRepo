package com.github.klboke.kkrepo.server.pypi;

import java.util.Locale;
import java.util.Map;

final class PypiPaths {
  static final String INDEX_PREFIX = "simple/";
  static final String PACKAGES_PREFIX = "packages/";
  private static final Map<String, String> KNOWN_EXTENSIONS = Map.ofEntries(
      Map.entry(".tar.bz2", ".tar.bz2"),
      Map.entry(".tbz", ".tbz"),
      Map.entry(".tar.gz", ".tar.gz"),
      Map.entry(".tgz", ".tgz"),
      Map.entry(".tlz", ".tlz"),
      Map.entry(".tar.lz", ".tar.lz"),
      Map.entry(".tar.lzma", ".tar.lzma"),
      Map.entry(".tar.xz", ".tar.xz"),
      Map.entry(".txz", ".txz"),
      Map.entry(".tar.z", ".tar.z"),
      Map.entry(".tz", ".tz"),
      Map.entry(".taz", ".taz"),
      Map.entry(".tar", ".tar"),
      Map.entry(".zip", ".zip"),
      Map.entry(".whl", ".whl"),
      Map.entry(".egg", ".egg"));

  private PypiPaths() {
  }

  static String normalizeName(String name) {
    return safe(name).replaceAll("[-_.]+", "-").toLowerCase(Locale.ENGLISH);
  }

  static String indexPath(String name) {
    String normalized = normalizeName(name);
    return INDEX_PREFIX + normalized + "/";
  }

  static String packagePath(String name, String version, String filename) {
    return PACKAGES_PREFIX + normalizeName(name) + "/" + safe(version) + "/" + safe(filename);
  }

  static String packageNameFromPath(String path) {
    String[] parts = safe(path).split("/");
    return parts.length >= 2 && PACKAGES_PREFIX.substring(0, PACKAGES_PREFIX.length() - 1).equals(parts[0])
        ? parts[1]
        : "";
  }

  static String versionFromPath(String path) {
    String[] parts = safe(path).split("/");
    return parts.length >= 3 ? parts[2] : "";
  }

  static String fileName(String path) {
    String normalized = safe(path);
    int slash = normalized.lastIndexOf('/');
    return slash < 0 ? normalized : normalized.substring(slash + 1);
  }

  static String versionFromFilename(String filename) {
    String base = removeExtension(filename);
    int begin;
    int end;
    if (filename.endsWith(".whl")) {
      begin = base.indexOf('-') + 1;
      end = begin <= 0 ? -1 : base.indexOf('-', begin);
    } else {
      begin = filenameVersionStart(filename) + 1;
      end = base.indexOf('-', begin);
      if (end < 0) end = base.length();
    }
    if (begin <= 0 || begin >= base.length()) return "";
    if (end < 0 || end <= begin) end = base.length();
    return base.substring(begin, end);
  }

  static String nameFromFilename(String filename) {
    int start = filenameVersionStart(filename);
    return start <= 0 ? removeExtension(filename) : filename.substring(0, start);
  }

  private static int filenameVersionStart(String filename) {
    for (int i = 0; i < filename.length() - 1; i++) {
      if (filename.charAt(i) == '-' && Character.isDigit(filename.charAt(i + 1))) {
        return i;
      }
    }
    int dash = filename.indexOf('-');
    return dash >= 0 ? dash : 0;
  }

  private static String removeExtension(String filename) {
    String lower = filename.toLowerCase(Locale.ENGLISH);
    for (String ext : KNOWN_EXTENSIONS.keySet()) {
      if (lower.endsWith(ext)) {
        return filename.substring(0, filename.length() - ext.length());
      }
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? filename : filename.substring(0, dot);
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
