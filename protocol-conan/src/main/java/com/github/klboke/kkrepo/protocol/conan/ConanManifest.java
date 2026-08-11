package com.github.klboke.kkrepo.protocol.conan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded parser for the official {@code FileTreeManifest} wire representation. */
public record ConanManifest(long timestamp, Map<String, String> md5ByPath) {
  public static final String FILE_NAME = "conanmanifest.txt";
  public static final int MAX_BYTES = 1024 * 1024;
  public static final int MAX_ENTRIES = 4096;
  private static final Pattern MD5 = Pattern.compile("[0-9a-fA-F]{32}");

  public ConanManifest {
    if (timestamp < 0) throw new IllegalArgumentException("Invalid Conan manifest timestamp");
    if (md5ByPath == null || md5ByPath.size() > MAX_ENTRIES) {
      throw new IllegalArgumentException("Conan manifest entry limit exceeded");
    }
    LinkedHashMap<String, String> copy = new LinkedHashMap<>();
    md5ByPath.forEach((path, checksum) -> {
      if (!ConanPathParser.validFilePath(path) || FILE_NAME.equals(path)
          || checksum == null || !MD5.matcher(checksum).matches()
          || copy.putIfAbsent(path, checksum.toLowerCase(java.util.Locale.ROOT)) != null) {
        throw new IllegalArgumentException("Invalid or duplicate Conan manifest entry: " + path);
      }
    });
    md5ByPath = Collections.unmodifiableMap(copy);
  }

  public static ConanManifest parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Invalid Conan manifest size");
    }
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.indexOf('\u0000') >= 0 || text.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("Invalid Conan manifest encoding");
    }
    String[] lines = text.split("\n", -1);
    if (lines.length < 2) throw new IllegalArgumentException("Invalid Conan manifest");
    final long timestamp;
    try {
      timestamp = Long.parseLong(lines[0]);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Invalid Conan manifest timestamp", invalid);
    }
    LinkedHashMap<String, String> files = new LinkedHashMap<>();
    for (int index = 1; index < lines.length; index++) {
      String line = lines[index];
      if (line.isEmpty()) continue;
      int separator = line.lastIndexOf(": ");
      if (separator <= 0) throw new IllegalArgumentException("Invalid Conan manifest entry");
      String path = line.substring(0, separator);
      String digest = line.substring(separator + 2);
      if (files.putIfAbsent(path, digest) != null) {
        throw new IllegalArgumentException("Duplicate Conan manifest path: " + path);
      }
      if (files.size() > MAX_ENTRIES) {
        throw new IllegalArgumentException("Conan manifest entry limit exceeded");
      }
    }
    return new ConanManifest(timestamp, files);
  }

  /** Matches Conan's {@code FileTreeManifest.summary_hash}. */
  public String summaryHash() {
    List<String> paths = new ArrayList<>(md5ByPath.keySet());
    Collections.sort(paths);
    StringBuilder content = new StringBuilder();
    for (String path : paths) {
      content.append(path).append(": ").append(md5ByPath.get(path)).append('\n');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(digest.digest(
          content.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("MD5 is unavailable", impossible);
    }
  }
}
