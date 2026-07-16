package com.github.klboke.kkrepo.core.security;

import java.util.Locale;

/** Nexus-compatible serialization for OpenPGP key identifiers. */
public final class OpenPgpKeyIds {
  private OpenPgpKeyIds() {
  }

  public static String format(long keyId) {
    return Long.toUnsignedString(keyId, 16).toUpperCase(Locale.ROOT);
  }
}
