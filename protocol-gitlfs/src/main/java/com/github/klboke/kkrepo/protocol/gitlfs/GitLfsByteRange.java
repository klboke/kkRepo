package com.github.klboke.kkrepo.protocol.gitlfs;

/** Satisfiable byte range of a stored object: {@code length} bytes starting at {@code start}. */
public record GitLfsByteRange(long start, long length) {

  public GitLfsByteRange {
    if (start < 0 || length < 1) {
      throw new IllegalArgumentException("Invalid byte range: start=" + start + ", length=" + length);
    }
  }

  /** Last byte position, inclusive. */
  public long end() {
    return start + length - 1;
  }
}
