package com.github.klboke.kkrepo.protocol.r;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded parser and deterministic renderer for Debian Control File records used by R. */
public final class RDcf {
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  public static final int MAX_RECORDS = 2_000_000;
  public static final int MAX_FIELDS = 256;
  public static final int MAX_FIELD_BYTES = 1024 * 1024;
  private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9._@-]{0,63}");

  private RDcf() {
  }

  public static List<Map<String, String>> parse(byte[] bytes) {
    if (bytes == null || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("R DCF exceeds safety limits");
    }
    String content = decode(bytes).replace("\r\n", "\n").replace('\r', '\n');
    List<Map<String, String>> records = new ArrayList<>();
    LinkedHashMap<String, StringBuilder> current = new LinkedHashMap<>();
    String currentField = null;
    for (String line : content.split("\n", -1)) {
      if (line.isEmpty()) {
        if (!current.isEmpty()) {
          records.add(freeze(current));
          if (records.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("R DCF contains too many records");
          }
          current.clear();
          currentField = null;
        }
        continue;
      }
      if (line.charAt(0) == '#') continue;
      if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
        if (currentField == null) {
          throw new IllegalArgumentException("R DCF continuation has no field");
        }
        String continuation = line.substring(1);
        StringBuilder value = current.get(currentField);
        if (value.length() + continuation.length() + 1 > MAX_FIELD_BYTES) {
          throw new IllegalArgumentException("R DCF field exceeds safety limits");
        }
        value.append('\n').append(continuation);
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) throw new IllegalArgumentException("Invalid R DCF field");
      String name = line.substring(0, colon);
      if (!FIELD.matcher(name).matches() || current.containsKey(name)) {
        throw new IllegalArgumentException("Invalid or duplicate R DCF field: " + name);
      }
      String value = line.substring(colon + 1);
      if (value.startsWith(" ")) value = value.substring(1);
      if (value.length() > MAX_FIELD_BYTES || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("R DCF field exceeds safety limits");
      }
      current.put(name, new StringBuilder(value));
      if (current.size() > MAX_FIELDS) {
        throw new IllegalArgumentException("R DCF record contains too many fields");
      }
      currentField = name;
    }
    if (!current.isEmpty()) records.add(freeze(current));
    return List.copyOf(records);
  }

  public static Map<String, String> parseOne(byte[] bytes) {
    List<Map<String, String>> records = parse(bytes);
    if (records.size() != 1) {
      throw new IllegalArgumentException("Expected exactly one R DCF record");
    }
    return records.getFirst();
  }

  public static String renderRecord(Map<String, String> fields, List<String> fieldOrder) {
    if (fields == null || fields.isEmpty()) return "";
    StringBuilder output = new StringBuilder();
    List<String> names = new ArrayList<>();
    if (fieldOrder != null) {
      for (String name : fieldOrder) if (fields.containsKey(name)) names.add(name);
    }
    fields.keySet().stream().filter(name -> !names.contains(name)).sorted().forEach(names::add);
    for (String name : names) {
      if (!FIELD.matcher(name).matches()) throw new IllegalArgumentException("Invalid R DCF field: " + name);
      String value = fields.get(name);
      if (value == null || value.indexOf('\0') >= 0) continue;
      String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
      output.append(name).append(": ").append(lines[0]).append('\n');
      for (int index = 1; index < lines.length; index++) {
        output.append(' ').append(lines[index]).append('\n');
      }
    }
    return output.toString();
  }

  private static Map<String, String> freeze(Map<String, StringBuilder> source) {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    source.forEach((name, value) -> result.put(name, value.toString()));
    return Collections.unmodifiableMap(result);
  }

  private static String decode(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException error) {
      return StandardCharsets.ISO_8859_1.decode(ByteBuffer.wrap(bytes)).toString();
    }
  }
}
