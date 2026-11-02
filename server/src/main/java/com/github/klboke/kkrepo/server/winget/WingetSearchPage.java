package com.github.klboke.kkrepo.server.winget;

import com.github.klboke.kkrepo.protocol.winget.WingetPackage;

import java.util.List;

/** One WinGet manifest search page plus the cursor needed for the next page. */
public record WingetSearchPage(
    List<WingetPackage> packages, Integer nextOffset, String upstreamContinuationToken) {
  public WingetSearchPage {
    packages = packages == null ? List.of() : List.copyOf(packages);
  }

  public static WingetSearchPage empty() {
    return new WingetSearchPage(List.of(), null, null);
  }
}
