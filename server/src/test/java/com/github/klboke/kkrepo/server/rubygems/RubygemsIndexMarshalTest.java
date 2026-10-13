package com.github.klboke.kkrepo.server.rubygems;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RubygemsIndexMarshalTest {
  @Test
  void acceptsThreeMillionUnlinkedSpecRowsWithinTheExpandedByteBudget() throws Exception {
    // The hosted writer emits six objects and four fresh symbols for each tuple. Repeating
    // its tuple is valid Marshal and exercises the complete advertised row budget, without
    // allocating a second full index in this test or depending on a public upstream snapshot.
    byte[] one = RubygemsService.rubyMarshalSpecs(List.of(
        new RubygemsService.GemMetadata("g", "1", "ruby", List.of())));
    byte[] tuple = Arrays.copyOfRange(one, 4, one.length);
    int count = 3_000_000;
    byte[] header = {4, 8, '[', 3, (byte) count, (byte) (count >>> 8), (byte) (count >>> 16)};
    InputStream repeated = new InputStream() {
      private long remaining = (long) tuple.length * count;
      private int offset;

      @Override public int read() {
        if (remaining == 0) return -1;
        remaining--;
        int value = tuple[offset++] & 0xff;
        if (offset == tuple.length) offset = 0;
        return value;
      }

      @Override public int read(byte[] buffer, int start, int length) {
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int size = (int) Math.min(length, remaining);
        for (int written = 0; written < size;) {
          int chunk = Math.min(size - written, tuple.length - offset);
          System.arraycopy(tuple, offset, buffer, start + written, chunk);
          written += chunk;
          offset = (offset + chunk) % tuple.length;
        }
        remaining -= size;
        return size;
      }
    };
    int[] read = {0};
    try (InputStream input = new SequenceInputStream(new ByteArrayInputStream(header), repeated)) {
      RubygemsIndexMarshal.readSpecs(input, row -> {
        if (read[0] == 0 || read[0] == count - 1) {
          assertEquals(new RubygemsIndexMarshal.Spec("g", "1", "ruby"), row);
        }
        read[0]++;
      });
    }
    assertEquals(count, read[0]);
  }
}
