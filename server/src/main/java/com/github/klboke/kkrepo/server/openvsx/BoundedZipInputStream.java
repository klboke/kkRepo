package com.github.klboke.kkrepo.server.openvsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

/**
 * ZIP reader that enforces an aggregate decompressed-byte budget.
 *
 * <p>{@link ZipInputStream#getNextEntry()} drains skipped entries through {@code read}, so a
 * plain per-entry size check is not enough for hostile archives that declare small members but
 * inflate to gigabytes. Every byte emitted by the inflater is counted here.
 */
final class BoundedZipInputStream extends ZipInputStream {
  private final long limit;
  private long total;

  BoundedZipInputStream(InputStream input, long limit) {
    super(input);
    this.limit = Math.max(1, limit);
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value >= 0) {
      count(1);
    }
    return value;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    int read = super.read(buffer, offset, length);
    if (read > 0) {
      count(read);
    }
    return read;
  }

  private void count(int bytes) throws IOException {
    total += bytes;
    if (total > limit) {
      throw new IOException("VSIX payload is too large");
    }
  }
}
