package com.github.klboke.kkrepo.protocol.alpine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One APKINDEX record, preserving unknown legal fields and their order. */
public final class AlpineIndexRecord {
  private static final int MAX_FIELDS = 4_096;
  private static final int MAX_VALUE = 256 * 1024;
  private final List<Field> fields;

  public AlpineIndexRecord(List<Field> fields) {
    if (fields == null || fields.isEmpty() || fields.size() > MAX_FIELDS) {
      throw new IllegalArgumentException("Invalid APKINDEX record field count");
    }
    this.fields = List.copyOf(fields);
    AlpineChecksums.requireV2Identity(require('C'));
    requireSafeText("package", require('P'), 128);
    AlpineVersions.require(require('V'));
    requireSafeText("architecture", require('A'), 64);
    requireUnsignedLong('S');
    requireUnsignedLong('I');
  }

  public List<Field> fields() {
    return fields;
  }

  public String get(char name) {
    return fields.stream().filter(field -> field.name() == name)
        .map(Field::value).findFirst().orElse(null);
  }

  public String require(char name) {
    String value = get(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("APKINDEX record is missing " + name);
    }
    return value;
  }

  public String packageName() {
    return require('P');
  }

  public String version() {
    return require('V');
  }

  public String architecture() {
    return require('A');
  }

  public String identity() {
    return require('C');
  }

  public long downloadSize() {
    return Long.parseLong(require('S'));
  }

  public String render() {
    StringBuilder result = new StringBuilder();
    for (Field field : fields) {
      result.append(field.name()).append(':').append(field.value()).append('\n');
    }
    return result.toString();
  }

  public static Builder builder() {
    return new Builder();
  }

  private void requireUnsignedLong(char field) {
    String value = require(field);
    try {
      if (value.charAt(0) == '+' || value.charAt(0) == '-') throw new NumberFormatException();
      long parsed = Long.parseLong(value);
      if (parsed < 0) throw new NumberFormatException();
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("Invalid APKINDEX " + field, error);
    }
  }

  private static String requireSafeText(String name, String value, int maxLength) {
    if (value == null || value.isEmpty() || value.length() > maxLength
        || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
      throw new IllegalArgumentException("Invalid APKINDEX " + name);
    }
    return value;
  }

  public record Field(char name, String value) {
    public Field {
      if (!(name >= 'A' && name <= 'Z') && !(name >= 'a' && name <= 'z')) {
        throw new IllegalArgumentException("Invalid APKINDEX field name: " + name);
      }
      if (value == null || value.length() > MAX_VALUE || value.indexOf('\0') >= 0
          || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("Invalid APKINDEX field value: " + name);
      }
    }
  }

  public static final class Builder {
    private final ArrayList<Field> fields = new ArrayList<>();

    public Builder field(char name, String value) {
      fields.add(new Field(name, value));
      return this;
    }

    public Builder optional(char name, String value) {
      if (value != null && !value.isEmpty()) field(name, value);
      return this;
    }

    public Builder joined(char name, List<String> values) {
      if (values != null && !values.isEmpty()) field(name, String.join(" ", values));
      return this;
    }

    public AlpineIndexRecord build() {
      return new AlpineIndexRecord(Collections.unmodifiableList(fields));
    }
  }
}
