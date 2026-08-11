package com.github.klboke.kkrepo.core;

import java.util.Objects;

/** Collision-free, database-text-safe encoding for ordered identity parts. */
public final class DatabaseCompositeKey {
  private static final String VERSION = "v1";

  private DatabaseCompositeKey() {
  }

  public static String of(String... parts) {
    Objects.requireNonNull(parts, "parts");
    StringBuilder encoded = new StringBuilder(VERSION);
    for (String part : parts) {
      encoded.append('|');
      if (part == null) {
        encoded.append("-1:");
        continue;
      }
      if (part.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Database composite-key parts cannot contain NUL");
      }
      encoded.append(part.length()).append(':').append(part);
    }
    return encoded.toString();
  }
}
