package com.github.klboke.kkrepo.server.rubygems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    int[] read = {0};
    try (InputStream input = rows(count, new byte[0], tuple, count)) {
      RubygemsIndexMarshal.readSpecs(input, row -> {
        if (read[0] == 0 || read[0] == count - 1) {
          assertEquals(new RubygemsIndexMarshal.Spec("g", "1", "ruby"), row);
        }
        read[0]++;
      });
    }
    assertEquals(count, read[0]);
  }

  @Test
  void acceptsThreeMillionDependencyRowsWithMoreObjectsThanSpecs() throws Exception {
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("name", "g"); expected.put("number", "1"); expected.put("platform", "ruby");
    expected.put("dependencies", List.of(List.of("d", ">= 0")));
    byte[] one = RubygemsIndexMarshal.dependencies(List.of(expected));
    byte[] two = RubygemsIndexMarshal.dependencies(List.of(expected, expected));
    byte[] first = Arrays.copyOfRange(one, 4, one.length);
    // The second row uses the first row's symbol definitions but no object links. Each
    // hash with its dependency pair consumes eight object slots, rather than a spec's six.
    byte[] next = Arrays.copyOfRange(two, one.length, two.length);
    int count = 3_000_000;
    assertTrue(7L + first.length + (long) next.length * (count - 1) < RubygemsIndexMarshal.MAX_EXPANDED_BYTES);
    int[] read = {0};
    try (InputStream input = rows(count, first, next, count - 1)) {
      RubygemsIndexMarshal.readDependencies(input, row -> {
        if (read[0] == 0 || read[0] == count - 1) assertEquals(expected, row);
        read[0]++;
      });
    }
    assertEquals(count, read[0]);
  }

  private static InputStream rows(int count, byte[] first, byte[] tuple, int repetitions) {
    byte[] header = {4, 8, '[', 3, (byte) count, (byte) (count >>> 8), (byte) (count >>> 16)};
    return new SequenceInputStream(Collections.enumeration(List.of(new ByteArrayInputStream(header),
        new ByteArrayInputStream(first), repeated(tuple, repetitions))));
  }

  @Test
  void rejectsNestedAllocationAmplificationWithinTheWireBudget() throws Exception {
    // One malformed row contains 10,000 arrays of 10,000 nils. The wire fits 256 MiB,
    // but retaining all 100 million decoded references would exceed ordinary JVM heaps.
    byte[] array = {'[', 2, 16, 39}; // Marshal array length 10,000.
    byte[] child = new byte[10_004];
    System.arraycopy(array, 0, child, 0, array.length);
    Arrays.fill(child, array.length, child.length, (byte) '0');
    try (InputStream input = rows(1, array, child, 10_000)) {
      var failure = assertThrows(IllegalArgumentException.class,
          () -> RubygemsIndexMarshal.readSpecs(input, ignored -> { }));
      assertEquals("RubyGems index row exceeds allocation limit", failure.getMessage());
    }
  }

  private static InputStream repeated(byte[] tuple, int count) {
    return new InputStream() {
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
  }
}
