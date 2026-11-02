package com.github.klboke.kkrepo.protocol.winget;

import com.fasterxml.jackson.databind.JsonNode;

/** One version entry of a WinGet manifest, preserving the original version JSON. */
public record WingetVersion(WingetIndex index, JsonNode manifest) {
  public String packageIdentifier() {
    return index.packageIdentifier();
  }

  public String packageVersion() {
    return index.packageVersion();
  }

  public String channel() {
    return index.channel();
  }
}
