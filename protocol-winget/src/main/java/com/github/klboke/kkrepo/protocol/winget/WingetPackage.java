package com.github.klboke.kkrepo.protocol.winget;

import java.util.List;

/** One package entry in a WinGet manifest search response. */
public record WingetPackage(
    String packageIdentifier,
    String packageName,
    String publisher,
    List<WingetVersionSummary> versions) {
  public WingetPackage {
    versions = versions == null ? List.of() : List.copyOf(versions);
  }
}
