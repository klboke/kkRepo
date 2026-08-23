package com.github.klboke.kkrepo.protocol.r;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical bounded projection of an R package DESCRIPTION or PACKAGES record. */
public record RPackageMetadata(
    String packageName,
    String version,
    Map<String, String> fields) {
  private static final List<String> DEPENDENCY_FIELDS = List.of(
      "Depends", "Imports", "LinkingTo", "Suggests", "Enhances");

  public RPackageMetadata {
    if (!RPathParser.validPackageName(packageName)) {
      throw new IllegalArgumentException("Invalid R package name: " + packageName);
    }
    RVersions.require(version);
    fields = fields == null ? Map.of() : Map.copyOf(fields);
  }

  public static RPackageMetadata fromDescription(byte[] bytes, String expectedFilename) {
    Map<String, String> parsed = RDcf.parseOne(bytes);
    RPackageMetadata metadata = requireIdentity(parsed);
    require(parsed, "Title");
    require(parsed, "Description");
    require(parsed, "License");
    if (blank(parsed.get("Authors@R"))
        && (blank(parsed.get("Author")) || blank(parsed.get("Maintainer")))) {
      throw new IllegalArgumentException(
          "R DESCRIPTION requires Authors@R or Author and Maintainer");
    }
    if (expectedFilename != null
        && !RPathParser.sourceFilename(metadata.packageName(), metadata.version())
            .equals(expectedFilename)) {
      throw new IllegalArgumentException("R package filename does not match DESCRIPTION identity");
    }
    return metadata;
  }

  public static RPackageMetadata fromIndexRecord(Map<String, String> fields) {
    return requireIdentity(fields);
  }

  public Map<String, String> indexFields(String md5, String filename) {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    result.put("Package", packageName);
    result.put("Version", version);
    copy(fields, result, "Priority");
    for (String dependency : DEPENDENCY_FIELDS) copy(fields, result, dependency);
    copy(fields, result, "License");
    copy(fields, result, "OS_type");
    copy(fields, result, "Archs");
    copy(fields, result, "NeedsCompilation");
    if (!blank(md5)) result.put("MD5sum", md5.toLowerCase(Locale.ROOT));
    if (!blank(filename)
        && !RPathParser.sourceFilename(packageName, version).equals(filename)) {
      result.put("File", filename);
    }
    return Map.copyOf(result);
  }

  public Map<String, String> dependencies() {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    for (String name : DEPENDENCY_FIELDS) copy(fields, result, name);
    return Map.copyOf(result);
  }

  private static RPackageMetadata requireIdentity(Map<String, String> fields) {
    if (fields == null) throw new IllegalArgumentException("Missing R package metadata");
    String packageName = require(fields, "Package");
    String version = require(fields, "Version");
    return new RPackageMetadata(packageName, version, Map.copyOf(fields));
  }

  private static String require(Map<String, String> fields, String name) {
    String value = fields.get(name);
    if (blank(value)) throw new IllegalArgumentException("R metadata is missing " + name);
    return value.trim();
  }

  private static void copy(Map<String, String> source, Map<String, String> target, String name) {
    String value = source.get(name);
    if (!blank(value)) target.put(name, canonical(value));
  }

  private static String canonical(String value) {
    return value.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
