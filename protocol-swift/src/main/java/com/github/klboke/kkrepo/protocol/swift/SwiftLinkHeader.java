package com.github.klboke.kkrepo.protocol.swift;

import java.util.Collection;
import java.util.stream.Collectors;

public final class SwiftLinkHeader {
  private SwiftLinkHeader() {
  }

  public static String render(Collection<SwiftLink> links) {
    if (links == null) {
      throw new IllegalArgumentException("Swift links must not be null");
    }
    return links.stream().map(SwiftLink::render).collect(Collectors.joining(", "));
  }
}
