package com.github.klboke.kkrepo.server.rubygems;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/** Data-only reader for RubyGems indexes. Never instantiates classes named by the wire format. */
final class RubygemsIndexMarshal {
  static final int MAX_ROWS = 3_000_000;
  static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
  record Version(String value) { }
  record Spec(String name, String version, String platform) { }

  static void readSpecs(InputStream input, Consumer<Spec> consumer) throws IOException {
    new Reader(input).rows(row -> {
      if (!(row instanceof List<?> fields) || fields.size() != 3
          || !(fields.get(0) instanceof String name) || !(fields.get(1) instanceof Version version)
          || !(fields.get(2) instanceof String platform)) throw invalid();
      consumer.accept(new Spec(name, version.value(), platform));
    });
  }

  static void readDependencies(InputStream input, Consumer<Map<String, Object>> consumer) throws IOException {
    new Reader(input).rows(row -> {
      if (!(row instanceof Map<?, ?> fields) || !(fields.get("name") instanceof String)
          || !(fields.get("number") instanceof String) || !(fields.get("platform") instanceof String)
          || (!(fields.get("dependencies") instanceof List<?>)
              && !(fields.get("dependencies") instanceof Map<?, ?>))) throw invalid();
      Map<String, Object> values = new LinkedHashMap<>();
      fields.forEach((key, value) -> values.put((String) key, value));
      // Older hosted metadata used a dependency hash. Normalize it while merging, so rolling
      // upgrades can still serve Bundler's array-of-string-pairs contract.
      if (values.get("dependencies") instanceof Map<?, ?> dependencies) {
        values.put("dependencies", dependencies.entrySet().stream()
            .map(entry -> List.of(entry.getKey(), entry.getValue())).toList());
      }
      consumer.accept(values);
    });
  }

  static byte[] specs(Collection<Spec> specs) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (OutputStream out = new BufferedOutputStream(new GZIPOutputStream(bytes))) {
      header(out, specs.size());
      Map<String, Integer> symbols = new LinkedHashMap<>();
      for (Spec spec : specs) write(out, List.of(spec.name(), new Version(spec.version()), spec.platform()), symbols);
    }
    return bytes.toByteArray();
  }

  static byte[] dependencies(Collection<Map<String, Object>> rows) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    header(out, rows.size());
    Map<String, Integer> symbols = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) write(out, row, symbols);
    return out.toByteArray();
  }

  private static void header(OutputStream out, int size) throws IOException {
    out.write(new byte[] {4, 8, '['});
    number(out, size);
  }

  private static void write(OutputStream out, Object value, Map<String, Integer> symbols) throws IOException {
    if (value instanceof String string) {
      out.write('I'); raw(out, '"', string); number(out, 1); symbol(out, "E", symbols); out.write('T');
    } else if (value instanceof Version version) {
      out.write('U'); symbol(out, "Gem::Version", symbols); write(out, List.of(version.value()), symbols);
    } else if (value instanceof List<?> list) {
      out.write('['); number(out, list.size());
      for (Object item : list) write(out, item, symbols);
    } else if (value instanceof Map<?, ?> map) {
      out.write('{'); number(out, map.size());
      for (var entry : map.entrySet()) { symbol(out, (String) entry.getKey(), symbols); write(out, entry.getValue(), symbols); }
    } else if (value == null) {
      out.write('0');
    } else {
      throw invalid();
    }
  }

  private static void symbol(OutputStream out, String value, Map<String, Integer> symbols) throws IOException {
    Integer index = symbols.get(value);
    if (index != null) { out.write(';'); number(out, index); }
    else { symbols.put(value, symbols.size()); raw(out, ':', value); }
  }

  private static void raw(OutputStream out, int tag, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    out.write(tag); number(out, bytes.length); out.write(bytes);
  }

  private static void number(OutputStream out, int value) throws IOException {
    if (value == 0) { out.write(0); return; }
    if (value < 123) { out.write(value + 5); return; }
    int count = (32 - Integer.numberOfLeadingZeros(value) + 7) / 8;
    out.write(count);
    for (int i = 0; i < count; i++) out.write(value >>> (8 * i));
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid or oversized RubyGems index");
  }

  private static final class Reader {
    // Public rubygems.org specs currently contain over 1.7 million tuples. Limit both the
    // decompressed input and allocation counts; an upstream must not control arbitrary allocation.
    // Unlinked specs use six objects (including the tuple and Version fields) and four
    // symbols per row. Include the root array so all MAX_ROWS rows fit these table budgets.
    private static final int MAX_OBJECTS = 1 + 6 * MAX_ROWS;
    private static final int MAX_SYMBOLS = 4 * MAX_ROWS;
    private final InputStream input;
    private final List<String> symbols = new ArrayList<>();
    private final List<Object> objects = new ArrayList<>();
    private long remaining = MAX_EXPANDED_BYTES;

    Reader(InputStream input) { this.input = new BufferedInputStream(input, 64 * 1024); }

    void rows(Consumer<Object> consumer) throws IOException {
      if (next() != 4 || next() != 8 || next() != '[') throw invalid();
      int count = size(MAX_ROWS);
      remember(null); // Root array can never be referenced as an index field.
      for (int i = 0; i < count; i++) {
        int rowIndex = objects.size();
        consumer.accept(value(0));
        // Tuple/hash rows are not link targets in the index contract. Release the completed
        // row, retaining scalar links used by Ruby's deduplicated strings and Gem::Version.
        if (objects.size() > rowIndex) objects.set(rowIndex, null);
      }
      if (input.read() != -1) throw invalid();
    }

    private Object value(int depth) throws IOException {
      if (depth > 16) throw invalid();
      return switch (next()) {
        case 'I' -> {
          Object value = value(depth + 1);
          int count = size(16);
          for (int i = 0; i < count; i++) { value(depth + 1); value(depth + 1); }
          yield value;
        }
        case '"' -> remember(string());
        case ':' -> {
          if (symbols.size() >= MAX_SYMBOLS) throw invalid();
          String symbol = string();
          symbol = switch (symbol) { case "E" -> "E"; case "Gem::Version" -> "Gem::Version"; default -> symbol; };
          symbols.add(symbol); yield symbol;
        }
        case ';' -> link(symbols);
        case '@' -> {
          Object target = link(objects);
          // Only immutable scalar references are legal; reject cycles and collection aliases.
          if (!(target instanceof String) && !(target instanceof Version)) throw invalid();
          yield target;
        }
        case '[' -> {
          int count = size(10_000);
          List<Object> values = new ArrayList<>(count); remember(values);
          for (int i = 0; i < count; i++) values.add(value(depth + 1));
          yield values;
        }
        case '{' -> {
          int count = size(32);
          Map<String, Object> values = new LinkedHashMap<>(); remember(values);
          for (int i = 0; i < count; i++) {
            Object key = value(depth + 1);
            if (!(key instanceof String string)) throw invalid();
            values.put(string, value(depth + 1));
          }
          yield values;
        }
        case 'U' -> {
          if (!"Gem::Version".equals(value(depth + 1))) throw invalid();
          int index = objects.size(); remember(null);
          Object fields = value(depth + 1);
          if (!(fields instanceof List<?> list) || list.size() != 1
              || !(list.getFirst() instanceof String version)) throw invalid();
          Version result = new Version(version); objects.set(index, result); yield result;
        }
        case 'T' -> Boolean.TRUE;
        case 'F' -> Boolean.FALSE;
        case '0' -> null;
        default -> throw invalid();
      };
    }

    private String string() throws IOException {
      int length = size(64 * 1024);
      if ((remaining -= length) < 0) throw invalid();
      byte[] bytes = input.readNBytes(length);
      if (bytes.length != length) throw new EOFException("Truncated RubyGems index");
      return new String(bytes, StandardCharsets.UTF_8);
    }

    private <T> T link(List<T> table) throws IOException {
      int index = number();
      if (index < 0 || index >= table.size()) throw invalid();
      return table.get(index);
    }

    private <T> T remember(T value) {
      if (objects.size() >= MAX_OBJECTS) throw invalid();
      objects.add(value); return value;
    }

    private int size(int max) throws IOException {
      int count = number();
      if (count < 0 || count > max) throw invalid();
      return count;
    }

    private int number() throws IOException {
      int first = (byte) next();
      if (first == 0) return 0;
      if (first > 4) return first - 5;
      if (first < -4) return first + 5;
      int value = first < 0 ? -1 : 0;
      for (int i = 0; i < Math.abs(first); i++) {
        value = (value & ~(0xff << (8 * i))) | (next() << (8 * i));
      }
      return value;
    }

    private int next() throws IOException {
      if (--remaining < 0) throw invalid();
      int value = input.read();
      if (value < 0) throw new EOFException("Truncated RubyGems index");
      return value;
    }
  }
}
