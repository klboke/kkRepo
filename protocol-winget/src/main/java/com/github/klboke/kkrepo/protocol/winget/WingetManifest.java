package com.github.klboke.kkrepo.protocol.winget;

import java.util.List;

/** A validated WinGet package manifest containing one or more versions. */
public record WingetManifest(String packageIdentifier, List<WingetVersion> versions) {
  public WingetManifest {
    versions = versions == null ? List.of() : List.copyOf(versions);
  }
}
