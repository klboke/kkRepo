package com.github.klboke.kkrepo.protocol.apt;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Bounded parser and deterministic renderer for Debian deb822 control stanzas. */
public final class AptDeb822 {
  public static final int DEFAULT_MAX_BYTES = 16 * 1024 * 1024;
  public static final int DEFAULT_MAX_STANZAS = 100_000;
  public static final int DEFAULT_MAX_FIELDS = 256;
  public static final int DEFAULT_MAX_LINE_LENGTH = 64 * 1024;
  private static final Pattern FIELD_NAME = Pattern.compile("[!-9;-~]+");

  private AptDeb822() {
  }

  public static Stanza parseSingle(String value) {
    List<Stanza> stanzas = parse(value, 2);
    if (stanzas.size() != 1) {
      throw new IllegalArgumentException("Expected exactly one deb822 stanza");
    }
    return stanzas.getFirst();
  }

  public static List<Stanza> parse(String value) {
    return parse(value, DEFAULT_MAX_STANZAS);
  }

  public static List<Stanza> parse(String value, int maxStanzas) {
    byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
    try {
      return parse(new ByteArrayInputStream(bytes), bytes.length, maxStanzas,
          DEFAULT_MAX_FIELDS, DEFAULT_MAX_LINE_LENGTH);
    } catch (IOException error) {
      throw new IllegalArgumentException("Invalid deb822 input", error);
    }
  }

  public static List<Stanza> parse(InputStream input) throws IOException {
    return parse(input, DEFAULT_MAX_BYTES, DEFAULT_MAX_STANZAS,
        DEFAULT_MAX_FIELDS, DEFAULT_MAX_LINE_LENGTH);
  }

  public static List<Stanza> parse(
      InputStream input, int maxBytes, int maxStanzas, int maxFields, int maxLineLength)
      throws IOException {
    ArrayList<Stanza> result = new ArrayList<>();
    forEach(input, maxBytes, maxStanzas, maxFields, maxLineLength, result::add);
    return List.copyOf(result);
  }

  /** Streams bounded stanzas without retaining a complete remote Packages index in memory. */
  public static void forEach(
      InputStream input,
      long maxBytes,
      int maxStanzas,
      int maxFields,
      int maxLineLength,
      Consumer<Stanza> consumer) throws IOException {
    if (maxBytes < 1 || maxStanzas < 1 || maxFields < 1 || maxLineLength < 1) {
      throw new IllegalArgumentException("deb822 limits must be positive");
    }
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(consumer, "consumer");
    var decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    StanzaConsumer parser = new StanzaConsumer(maxStanzas, maxFields, consumer);
    try (Reader reader = new InputStreamReader(new BoundedInputStream(input, maxBytes), decoder)) {
      char[] chars = new char[8192];
      StringBuilder line = new StringBuilder(Math.min(maxLineLength, 1024));
      int read;
      while ((read = reader.read(chars)) >= 0) {
        for (int index = 0; index < read; index++) {
          char value = chars[index];
          if (value == '\n') {
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
              line.setLength(line.length() - 1);
            }
            parser.line(line.toString());
            line.setLength(0);
          } else {
            line.append(value);
            if (line.length() > maxLineLength) {
              throw new IllegalArgumentException("deb822 line is too long");
            }
          }
        }
      }
      if (!line.isEmpty()) {
        if (line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
        parser.line(line.toString());
      }
    } catch (java.nio.charset.CharacterCodingException error) {
      throw new IllegalArgumentException("deb822 input is not valid UTF-8", error);
    }
    parser.finish();
  }

  public static String render(List<Stanza> stanzas) {
    StringBuilder output = new StringBuilder();
    for (int index = 0; index < stanzas.size(); index++) {
      if (index > 0) output.append('\n');
      output.append(stanzas.get(index).render());
    }
    return output.toString();
  }

  private static final class StanzaConsumer {
    private final int maxStanzas;
    private final int maxFields;
    private final Consumer<Stanza> consumer;
    private LinkedHashMap<String, String> fields = new LinkedHashMap<>();
    private String currentName;
    private int count;

    private StanzaConsumer(int maxStanzas, int maxFields, Consumer<Stanza> consumer) {
      this.maxStanzas = maxStanzas;
      this.maxFields = maxFields;
      this.consumer = consumer;
    }

    private void line(String line) {
      if (line.isEmpty() || line.chars().allMatch(ch -> ch == ' ' || ch == '\t')) {
        emit();
        return;
      }
      if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
        if (currentName == null) {
          throw new IllegalArgumentException("Orphan deb822 continuation line");
        }
        fields.put(currentName, fields.get(currentName) + "\n" + line.substring(1));
        return;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) throw new IllegalArgumentException("Invalid deb822 field line");
      String name = line.substring(0, colon);
      if (!validFieldName(name)) {
        throw new IllegalArgumentException("Invalid deb822 field name: " + name);
      }
      String canonical = canonicalName(name);
      if (fields.keySet().stream().map(AptDeb822::canonicalName).anyMatch(canonical::equals)) {
        throw new IllegalArgumentException("Duplicate deb822 field: " + name);
      }
      if (fields.size() >= maxFields) {
        throw new IllegalArgumentException("Too many deb822 fields");
      }
      String value = line.substring(colon + 1);
      if (value.startsWith(" ") || value.startsWith("\t")) value = value.substring(1);
      fields.put(name, value.stripTrailing());
      currentName = name;
    }

    private void finish() {
      emit();
    }

    private void emit() {
      if (fields.isEmpty()) return;
      count++;
      if (count > maxStanzas) throw new IllegalArgumentException("Too many deb822 stanzas");
      consumer.accept(new Stanza(fields));
      fields = new LinkedHashMap<>();
      currentName = null;
    }
  }

  private static final class BoundedInputStream extends FilterInputStream {
    private final long maxBytes;
    private long count;

    private BoundedInputStream(InputStream input, long maxBytes) {
      super(input);
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) increment(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = super.read(bytes, offset, length);
      if (read > 0) increment(read);
      return read;
    }

    private void increment(int bytes) {
      count += bytes;
      if (count > maxBytes) {
        throw new IllegalArgumentException("deb822 input exceeds byte limit");
      }
    }
  }

  private static boolean validFieldName(String name) {
    return !name.startsWith("#") && !name.startsWith("-") && FIELD_NAME.matcher(name).matches();
  }

  private static String canonicalName(String name) {
    return name.toLowerCase(Locale.ROOT);
  }

  public static final class Stanza {
    private final Map<String, String> fields;
    private final Map<String, String> namesByCanonical;

    public Stanza(Map<String, String> fields) {
      LinkedHashMap<String, String> copy = new LinkedHashMap<>();
      LinkedHashMap<String, String> canonical = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : fields.entrySet()) {
        String name = Objects.requireNonNull(entry.getKey(), "field name");
        String value = Objects.requireNonNull(entry.getValue(), "field value");
        if (!validFieldName(name) || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
          throw new IllegalArgumentException("Invalid deb822 field: " + name);
        }
        String key = canonicalName(name);
        if (canonical.putIfAbsent(key, name) != null) {
          throw new IllegalArgumentException("Duplicate deb822 field: " + name);
        }
        copy.put(name, value);
      }
      this.fields = Collections.unmodifiableMap(copy);
      this.namesByCanonical = Collections.unmodifiableMap(canonical);
    }

    public Map<String, String> fields() {
      return fields;
    }

    public String get(String name) {
      String actual = namesByCanonical.get(canonicalName(name));
      return actual == null ? null : fields.get(actual);
    }

    public String require(String name) {
      String value = get(name);
      if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing deb822 field: " + name);
      return value;
    }

    public String render() {
      StringBuilder output = new StringBuilder();
      for (Map.Entry<String, String> entry : fields.entrySet()) {
        String[] lines = entry.getValue().split("\n", -1);
        output.append(entry.getKey()).append(':');
        if (!lines[0].isEmpty()) output.append(' ').append(lines[0]);
        output.append('\n');
        for (int index = 1; index < lines.length; index++) {
          output.append(' ').append(lines[index].isEmpty() ? "." : lines[index]).append('\n');
        }
      }
      return output.toString();
    }
  }
}
