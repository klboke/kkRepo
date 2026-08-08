package com.github.klboke.kkrepo.protocol.apt;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validated binary-package control metadata used to generate Packages stanzas. */
public record AptPackageControl(
    String packageName,
    String version,
    String architecture,
    String maintainer,
    String description,
    String source,
    String section,
    String priority,
    String multiArch,
    Map<String, String> fields) {

  private static final Pattern PACKAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9+.-]{1,127}");
  private static final Pattern SOURCE = Pattern.compile(
      "([a-z0-9][a-z0-9+.-]{1,127})(?:[ \\t]*\\(([^()\\s]+)\\))?");
  private static final Pattern ARCHITECTURE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final Set<String> MULTI_ARCH = Set.of("no", "same", "foreign", "allowed");

  public AptPackageControl {
    packageName = requirePackageName(packageName);
    version = DebianVersions.require(version);
    architecture = requireArchitecture(architecture);
    maintainer = requireText("Maintainer", maintainer, 4096);
    description = requireText("Description", description, 256 * 1024);
    source = requireSource(source);
    section = optionalText("Section", section, 256);
    priority = optionalText("Priority", priority, 64);
    multiArch = optionalText("Multi-Arch", multiArch, 32);
    if (multiArch != null && !MULTI_ARCH.contains(multiArch.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Invalid Multi-Arch value: " + multiArch);
    }
    fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields == null ? Map.of() : fields));
  }

  public static AptPackageControl parse(String control) {
    return from(AptDeb822.parseSingle(control));
  }

  public static AptPackageControl from(AptDeb822.Stanza stanza) {
    return new AptPackageControl(
        stanza.require("Package"),
        stanza.require("Version"),
        stanza.require("Architecture"),
        stanza.require("Maintainer"),
        stanza.require("Description"),
        stanza.get("Source"),
        stanza.get("Section"),
        stanza.get("Priority"),
        stanza.get("Multi-Arch"),
        stanza.fields());
  }

  public AptDeb822.Stanza packagesStanza(
      String filename, long size, String md5, String sha1, String sha256) {
    if (!safeRelativePath(filename)) {
      throw new IllegalArgumentException("Invalid package filename: " + filename);
    }
    if (size < 0) throw new IllegalArgumentException("Invalid package size");
    LinkedHashMap<String, String> output = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : fields.entrySet()) {
      String name = entry.getKey();
      if (!isGenerated(name)) output.put(name, entry.getValue());
    }
    output.putIfAbsent("Package", packageName);
    output.putIfAbsent("Version", version);
    output.putIfAbsent("Architecture", architecture);
    output.putIfAbsent("Maintainer", maintainer);
    output.putIfAbsent("Description", description);
    output.put("Filename", filename);
    output.put("Size", Long.toString(size));
    if (md5 != null && !md5.isBlank()) output.put("MD5sum", requireDigest("MD5", md5, 32));
    if (sha1 != null && !sha1.isBlank()) output.put("SHA1", requireDigest("SHA1", sha1, 40));
    output.put("SHA256", requireDigest("SHA256", sha256, 64));
    return new AptDeb822.Stanza(output);
  }

  public String sourcePackageName() {
    if (source == null) return packageName;
    Matcher matcher = SOURCE.matcher(source);
    if (!matcher.matches()) throw new IllegalStateException("APT source was not validated");
    return matcher.group(1);
  }

  private static boolean isGenerated(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "filename", "size", "md5sum", "sha1", "sha256", "sha512" -> true;
      default -> false;
    };
  }

  private static boolean safeRelativePath(String value) {
    if (value == null || value.isBlank() || value.startsWith("/")
        || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
      return false;
    }
    for (String segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
    }
    return true;
  }

  private static String requirePackageName(String value) {
    if (value == null || !PACKAGE_NAME.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Debian package name: " + value);
    }
    return value;
  }

  private static String requireArchitecture(String value) {
    if (value == null || !ARCHITECTURE.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Debian architecture: " + value);
    }
    return value;
  }

  private static String requireSource(String value) {
    if (value == null) return null;
    String normalized = optionalText("Source", value, 512).trim();
    Matcher matcher = SOURCE.matcher(normalized);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid Debian source package: " + value);
    }
    if (matcher.group(2) != null) DebianVersions.require(matcher.group(2));
    return normalized;
  }

  private static String requireText(String field, String value, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
    return optionalText(field, value, maxLength);
  }

  private static String optionalText(String field, String value, int maxLength) {
    if (value == null) return null;
    if (value.length() > maxLength || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(field + " exceeds safety limits");
    }
    return value;
  }

  private static String requireDigest(String algorithm, String value, int length) {
    if (value == null || value.length() != length
        || !value.chars().allMatch(character -> Character.digit(character, 16) >= 0)) {
      throw new IllegalArgumentException("Invalid " + algorithm + " digest");
    }
    return value.toLowerCase(Locale.ROOT);
  }
}
