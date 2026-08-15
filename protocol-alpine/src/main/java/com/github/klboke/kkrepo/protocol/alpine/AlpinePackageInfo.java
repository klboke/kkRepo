package com.github.klboke.kkrepo.protocol.alpine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict, bounded projection of the APK v2 control-section {@code .PKGINFO}. */
public final class AlpinePackageInfo {
  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");
  private static final Pattern PACKAGE = Pattern.compile("[a-z0-9][a-z0-9+_.-]{0,127}");
  private static final Pattern ARCHITECTURE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
  private static final int MAX_BYTES = 1024 * 1024;
  private static final int MAX_FIELDS = 16_384;
  private static final int MAX_VALUE = 256 * 1024;

  private final Map<String, List<String>> fields;

  private AlpinePackageInfo(Map<String, List<String>> fields) {
    this.fields = fields;
    requireName(first("pkgname"));
    AlpineVersions.require(first("pkgver"));
    requireArchitecture(first("arch"));
    requireUnsignedLong("size", first("size"));
    String datahash = optional("datahash");
    if (datahash == null) {
      throw new IllegalArgumentException("APK v2 .PKGINFO is missing datahash");
    }
    AlpineChecksums.requireSha256(datahash);
  }

  public static AlpinePackageInfo parse(String content) {
    if (content == null) throw new IllegalArgumentException(".PKGINFO is required");
    if (content.length() > MAX_BYTES || content.indexOf('\0') >= 0 || content.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(".PKGINFO exceeds safety limits");
    }
    LinkedHashMap<String, List<String>> mutable = new LinkedHashMap<>();
    int count = 0;
    for (String line : content.split("\\n", -1)) {
      if (line.isEmpty() || line.startsWith("#")) continue;
      int separator = line.indexOf(" = ");
      if (separator <= 0) throw new IllegalArgumentException("Malformed .PKGINFO line");
      String key = line.substring(0, separator);
      String value = line.substring(separator + 3);
      if (!KEY.matcher(key).matches() || value.length() > MAX_VALUE
          || value.chars().anyMatch(character -> character < 0x20 && character != '\t')) {
        throw new IllegalArgumentException("Unsafe .PKGINFO field: " + key);
      }
      if (++count > MAX_FIELDS) throw new IllegalArgumentException("Too many .PKGINFO fields");
      mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }
    LinkedHashMap<String, List<String>> immutable = new LinkedHashMap<>();
    mutable.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
    return new AlpinePackageInfo(Collections.unmodifiableMap(immutable));
  }

  public String name() {
    return first("pkgname");
  }

  public String version() {
    return first("pkgver");
  }

  public String architecture() {
    return first("arch");
  }

  public long installedSize() {
    return requireUnsignedLong("size", first("size"));
  }

  public String dataSha256() {
    return AlpineChecksums.requireSha256(first("datahash"));
  }

  public String description() {
    return defaulted("pkgdesc", name());
  }

  public String url() {
    return defaulted("url", "");
  }

  public String license() {
    return defaulted("license", "unknown");
  }

  public String origin() {
    return optional("origin");
  }

  public String maintainer() {
    return optional("maintainer");
  }

  public Long buildTime() {
    String value = optional("builddate");
    return value == null ? null : requireUnsignedLong("builddate", value);
  }

  public String commit() {
    return optional("commit");
  }

  public Integer providerPriority() {
    String value = optional("provider_priority");
    if (value == null) return null;
    long parsed = requireUnsignedLong("provider_priority", value);
    if (parsed > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("provider_priority is too large");
    }
    return (int) parsed;
  }

  public List<String> dependencies() {
    return fields.getOrDefault("depend", List.of());
  }

  public List<String> provides() {
    return fields.getOrDefault("provides", List.of());
  }

  public List<String> installIf() {
    return fields.getOrDefault("install_if", List.of());
  }

  public Map<String, List<String>> fields() {
    return fields;
  }

  public AlpineIndexRecord indexRecord(String identity, long downloadSize) {
    if (downloadSize < 0) throw new IllegalArgumentException("Invalid APK download size");
    AlpineIndexRecord.Builder builder = AlpineIndexRecord.builder()
        .field('C', AlpineChecksums.requireV2Identity(identity))
        .field('P', name())
        .field('V', version())
        .field('A', architecture())
        .field('S', Long.toString(downloadSize))
        .field('I', Long.toString(installedSize()))
        .field('T', description())
        .field('U', url())
        .field('L', license());
    builder.optional('o', origin());
    builder.optional('m', maintainer());
    if (buildTime() != null && buildTime() != 0) builder.field('t', buildTime().toString());
    builder.optional('c', commit());
    if (providerPriority() != null && providerPriority() != 0) {
      builder.field('k', providerPriority().toString());
    }
    builder.joined('D', dependencies());
    builder.joined('p', provides());
    builder.joined('i', installIf());
    return builder.build();
  }

  private String first(String name) {
    List<String> values = fields.get(name);
    if (values == null || values.isEmpty() || values.getFirst().isEmpty()) {
      throw new IllegalArgumentException(".PKGINFO is missing " + name);
    }
    return values.getFirst();
  }

  private String optional(String name) {
    List<String> values = fields.get(name);
    return values == null || values.isEmpty() || values.getFirst().isEmpty()
        ? null : values.getFirst();
  }

  private String defaulted(String name, String fallback) {
    String value = optional(name);
    return value == null ? fallback : value;
  }

  private static String requireName(String value) {
    if (value == null || !PACKAGE.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Alpine package name: " + value);
    }
    return value;
  }

  private static String requireArchitecture(String value) {
    if (value == null || !ARCHITECTURE.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Alpine package architecture: " + value);
    }
    return value;
  }

  private static long requireUnsignedLong(String field, String value) {
    try {
      if (value == null || value.isEmpty() || value.charAt(0) == '+' || value.charAt(0) == '-') {
        throw new NumberFormatException();
      }
      long parsed = Long.parseLong(value);
      if (parsed < 0) throw new NumberFormatException();
      return parsed;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("Invalid .PKGINFO " + field, error);
    }
  }
}
