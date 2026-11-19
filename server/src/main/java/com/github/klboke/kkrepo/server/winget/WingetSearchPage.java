package com.github.klboke.kkrepo.server.winget;

import com.github.klboke.kkrepo.protocol.winget.WingetPackage;

import java.util.List;

/**
 * One WinGet manifest search page plus the hosted offset of the next page. Only hosted search
 * paginates: proxy and group searches return a single page with a {@code null} next offset.
 */
public record WingetSearchPage(List<WingetPackage> packages, Integer nextOffset) {
  public WingetSearchPage {
    packages = packages == null ? List.of() : List.copyOf(packages);
  }

  public static WingetSearchPage empty() {
    return new WingetSearchPage(List.of(), null);
  }
}
