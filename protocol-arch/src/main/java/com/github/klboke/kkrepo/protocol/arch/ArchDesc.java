package com.github.klboke.kkrepo.protocol.arch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical {@code desc} record inside a pacman database.
 *
 * <p>The renderer is deterministic: field order follows repo-add, list values retain their stored
 * order, and empty fields are omitted. This makes generated databases byte-stable for a given
 * durable package set, which keeps ETag-based client caching correct across replicas.
 */
public record ArchDesc(
    String fileName,
    String name,
    String base,
    String version,
    String description,
    Long compressedSize,
    Long installedSize,
    String md5,
    String sha256,
    String pgpSignature,
    String url,
    List<String> licenses,
    String architecture,
    Long buildDate,
    String packager,
    List<String> depends,
    List<String> optionalDepends,
    List<String> makeDepends,
    List<String> checkDepends,
    List<String> conflicts,
    List<String> provides,
    List<String> replaces,
    List<String> groups) {

  public ArchDesc {
    licenses = copy(licenses);
    depends = copy(depends);
    optionalDepends = copy(optionalDepends);
    makeDepends = copy(makeDepends);
    checkDepends = copy(checkDepends);
    conflicts = copy(conflicts);
    provides = copy(provides);
    replaces = copy(replaces);
    groups = copy(groups);
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  public String directory() {
    return name + "-" + version;
  }

  public String render() {
    StringBuilder out = new StringBuilder();
    field(out, "%FILENAME%", fileName);
    field(out, "%NAME%", name);
    field(out, "%BASE%", base);
    field(out, "%VERSION%", version);
    field(out, "%DESC%", description);
    field(out, "%CSIZE%", compressedSize == null ? null : compressedSize.toString());
    field(out, "%ISIZE%", installedSize == null ? null : installedSize.toString());
    field(out, "%MD5SUM%", md5);
    field(out, "%SHA256SUM%", sha256);
    field(out, "%PGPSIG%", pgpSignature);
    field(out, "%URL%", url);
    list(out, "%LICENSE%", licenses);
    field(out, "%ARCH%", architecture);
    field(out, "%BUILDDATE%", buildDate == null ? null : buildDate.toString());
    field(out, "%PACKAGER%", packager);
    list(out, "%DEPENDS%", depends);
    list(out, "%OPTDEPENDS%", optionalDepends);
    list(out, "%MAKEDEPENDS%", makeDepends);
    list(out, "%CHECKDEPENDS%", checkDepends);
    list(out, "%CONFLICTS%", conflicts);
    list(out, "%PROVIDES%", provides);
    list(out, "%REPLACES%", replaces);
    list(out, "%GROUPS%", groups);
    return out.toString();
  }

  public Map<String, List<String>> asMap() {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    put(fields, "FILENAME", fileName);
    put(fields, "NAME", name);
    put(fields, "BASE", base);
    put(fields, "VERSION", version);
    put(fields, "DESC", description);
    put(fields, "CSIZE", compressedSize == null ? null : compressedSize.toString());
    put(fields, "ISIZE", installedSize == null ? null : installedSize.toString());
    put(fields, "MD5SUM", md5);
    put(fields, "SHA256SUM", sha256);
    put(fields, "PGPSIG", pgpSignature);
    put(fields, "URL", url);
    putAll(fields, "LICENSE", licenses);
    put(fields, "ARCH", architecture);
    put(fields, "BUILDDATE", buildDate == null ? null : buildDate.toString());
    put(fields, "PACKAGER", packager);
    putAll(fields, "DEPENDS", depends);
    putAll(fields, "OPTDEPENDS", optionalDepends);
    putAll(fields, "MAKEDEPENDS", makeDepends);
    putAll(fields, "CHECKDEPENDS", checkDepends);
    putAll(fields, "CONFLICTS", conflicts);
    putAll(fields, "PROVIDES", provides);
    putAll(fields, "REPLACES", replaces);
    putAll(fields, "GROUPS", groups);
    return fields;
  }

  public static ArchDesc parse(String text) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    String current = null;
    for (String line : text.split("\n", -1)) {
      if (line.isEmpty()) {
        current = null;
        continue;
      }
      if (line.startsWith("%") && line.endsWith("%") && line.length() > 2) {
        current = line.substring(1, line.length() - 1);
        fields.computeIfAbsent(current, ignored -> new ArrayList<>());
      } else if (current != null) {
        fields.get(current).add(line);
      }
    }
    return fromMap(fields);
  }

  public static ArchDesc fromMap(Map<String, List<String>> fields) {
    return new ArchDesc(
        first(fields, "FILENAME"), first(fields, "NAME"), first(fields, "BASE"),
        first(fields, "VERSION"), first(fields, "DESC"), longValue(first(fields, "CSIZE")),
        longValue(first(fields, "ISIZE")), first(fields, "MD5SUM"), first(fields, "SHA256SUM"),
        first(fields, "PGPSIG"), first(fields, "URL"), list(fields, "LICENSE"),
        first(fields, "ARCH"), longValue(first(fields, "BUILDDATE")), first(fields, "PACKAGER"),
        list(fields, "DEPENDS"), list(fields, "OPTDEPENDS"), list(fields, "MAKEDEPENDS"),
        list(fields, "CHECKDEPENDS"), list(fields, "CONFLICTS"), list(fields, "PROVIDES"),
        list(fields, "REPLACES"), list(fields, "GROUPS"));
  }

  private static void field(StringBuilder out, String name, String value) {
    if (value == null || value.isBlank()) return;
    out.append(name).append('\n').append(value).append('\n').append('\n');
  }

  private static void list(StringBuilder out, String name, List<String> values) {
    if (values == null || values.isEmpty()) return;
    out.append(name).append('\n');
    for (String value : values) {
      if (value != null && !value.isBlank()) out.append(value).append('\n');
    }
    out.append('\n');
  }

  private static void put(Map<String, List<String>> fields, String key, String value) {
    if (value != null && !value.isBlank()) fields.put(key, List.of(value));
  }

  private static void putAll(Map<String, List<String>> fields, String key, List<String> values) {
    if (values != null && !values.isEmpty()) fields.put(key, List.copyOf(values));
  }

  private static String first(Map<String, List<String>> fields, String key) {
    List<String> values = fields.get(key);
    return values == null || values.isEmpty() ? null : values.getFirst();
  }

  private static List<String> list(Map<String, List<String>> fields, String key) {
    List<String> values = fields.get(key);
    return values == null ? List.of() : List.copyOf(values);
  }

  private static Long longValue(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
