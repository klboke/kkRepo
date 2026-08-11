package com.github.klboke.kkrepo.protocol.conan;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, non-executing projection of the searchable sections in {@code conaninfo.txt}. */
public record ConanInfo(
    Map<String, String> settings,
    Map<String, String> options,
    Map<String, String> requires,
    String rawContent) {
  public static final String FILE_NAME = "conaninfo.txt";
  public static final int MAX_BYTES = 1024 * 1024;
  private static final int MAX_ENTRIES = 1024;
  private static final int MAX_KEY = 256;
  private static final int MAX_VALUE = 4096;

  public ConanInfo {
    settings = immutable(settings);
    options = immutable(options);
    requires = immutable(requires);
    if (rawContent == null || rawContent.length() > MAX_BYTES) {
      throw new IllegalArgumentException("Invalid conaninfo content");
    }
  }

  public static ConanInfo parse(byte[] bytes) {
    // A settings-independent Conan package legitimately carries a zero-byte conaninfo.txt.
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Invalid conaninfo size");
    }
    String raw;
    try {
      raw = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("Invalid conaninfo encoding", invalid);
    }
    if (raw.indexOf('\u0000') >= 0 || raw.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("Invalid conaninfo encoding");
    }
    Map<String, String> settings = new LinkedHashMap<>();
    Map<String, String> options = new LinkedHashMap<>();
    Map<String, String> requires = new LinkedHashMap<>();
    Map<String, String> current = null;
    int entries = 0;
    for (String line : raw.split("\n", -1)) {
      String value = line.trim();
      if (value.isEmpty() || value.startsWith("#")) continue;
      if (value.startsWith("[") && value.endsWith("]")) {
        String section = value.substring(1, value.length() - 1).trim();
        current = switch (section) {
          // Project the binary model used by package search. Build/target contexts may repeat
          // the same keys and remain available in the immutable conaninfo blob.
          case "settings" -> settings;
          case "options" -> options;
          case "requires" -> requires;
          default -> null;
        };
        continue;
      }
      if (current == null) continue;
      int separator = value.indexOf('=');
      String key = separator < 0 ? value : value.substring(0, separator).trim();
      String item = separator < 0 ? "" : value.substring(separator + 1).trim();
      if (key.isEmpty() || key.length() > MAX_KEY || item.length() > MAX_VALUE
          || containsControl(key) || containsControl(item) || current.putIfAbsent(key, item) != null) {
        throw new IllegalArgumentException("Invalid or duplicate conaninfo entry");
      }
      if (++entries > MAX_ENTRIES) {
        throw new IllegalArgumentException("conaninfo entry limit exceeded");
      }
    }
    return new ConanInfo(settings, options, requires, raw);
  }

  private static Map<String, String> immutable(Map<String, String> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
  }

  private static boolean containsControl(String value) {
    return value.chars().anyMatch(character -> character <= 0x1f || character == 0x7f);
  }
}
