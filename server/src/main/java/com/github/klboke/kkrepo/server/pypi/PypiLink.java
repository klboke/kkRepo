package com.github.klboke.kkrepo.server.pypi;

import java.time.Instant;

/**
 * One file entry of a simple index. {@code uploadTime} is the PEP 700 {@code upload-time}; it is
 * only known for indexes fetched from an upstream that publishes it and is null otherwise.
 */
public record PypiLink(String file, String href, String requiresPython, Instant uploadTime) {
  public PypiLink {
    if (file == null) file = "";
    if (href == null) href = "";
    if (requiresPython == null) requiresPython = "";
  }

  public PypiLink(String file, String href, String requiresPython) {
    this(file, href, requiresPython, null);
  }
}
