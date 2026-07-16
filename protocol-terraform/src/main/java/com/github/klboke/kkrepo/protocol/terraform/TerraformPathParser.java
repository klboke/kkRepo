package com.github.klboke.kkrepo.protocol.terraform;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strict parser for the HashiCorp/Nexus Terraform registry paths. */
public final class TerraformPathParser {
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
  private static final Pattern SAFE_FILENAME = Pattern.compile(
      "^[A-Za-z0-9._~!$&'()*+,;=:@-]+$");
  private static final Pattern VERSION = Pattern.compile(
      "^(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*)){2}(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

  public TerraformPath parse(String rawPath) {
    String raw = normalize(rawPath);
    List<String> segments = splitAndValidate(raw);
    if (segments.size() < 2 || !"v1".equals(segments.get(0))) return unknown(raw);
    return switch (segments.get(1)) {
      case "modules" -> parseModules(raw, segments);
      case "providers" -> parseProviders(raw, segments);
      default -> unknown(raw);
    };
  }

  /**
   * Parses an optional Nexus URL-token segment and returns the canonical path consumed by protocol
   * services. Direct protocol routes always win, so a namespace is never guessed to be a token.
   */
  public ParsedRequest parseRequestPath(String rawPath) {
    String raw = normalize(rawPath);
    TerraformPath direct;
    IllegalArgumentException directFailure = null;
    try {
      direct = parse(raw);
    } catch (IllegalArgumentException e) {
      direct = unknown(raw);
      directFailure = e;
    }
    if (direct.kind() != TerraformPath.Kind.UNKNOWN) return new ParsedRequest(raw, null, direct);
    List<String> segments = splitRequestSegments(raw);
    if (segments.size() <= 2 || !"v1".equals(segments.get(0))
        || !("modules".equals(segments.get(1)) || "providers".equals(segments.get(1)))) {
      if (directFailure != null) throw directFailure;
      return new ParsedRequest(raw, null, direct);
    }
    List<String> canonical = new ArrayList<>(segments);
    canonical.remove(2);
    String normalized = String.join("/", canonical);
    TerraformPath parsed;
    try {
      parsed = parse(normalized);
    } catch (IllegalArgumentException e) {
      if (directFailure != null) throw directFailure;
      throw e;
    }
    if (parsed.kind() == TerraformPath.Kind.UNKNOWN) {
      if (directFailure != null) throw directFailure;
      return new ParsedRequest(raw, null, direct);
    }
    String credential = decodeOnce(segments.get(2));
    if (credential.length() > 4096) throw new IllegalArgumentException("Terraform URL token is too long");
    return new ParsedRequest(normalized, credential, parsed);
  }

  private TerraformPath parseModules(String raw, List<String> input) {
    List<String> s = input;
    if (s.size() == 6 && "versions".equals(s.get(5))) {
      return module(TerraformPath.Kind.MODULE_VERSIONS, s, null, null, null, raw);
    }
    if (s.size() == 7 && "download".equals(s.get(6))) {
      requireVersion(s.get(5));
      return module(TerraformPath.Kind.MODULE_DOWNLOAD, s, null, s.get(5), null, raw);
    }
    if (s.size() == 7) {
      requireVersion(s.get(5));
      requireFilename(s.get(6));
      return module(TerraformPath.Kind.MODULE_ARCHIVE, s, null, s.get(5), s.get(6), raw);
    }
    return unknown(raw);
  }

  private TerraformPath parseProviders(String raw, List<String> input) {
    List<String> s = input;
    if (s.size() == 5 && ("versions".equals(s.get(4)) || "versions.json".equals(s.get(4)))) {
      return provider(TerraformPath.Kind.PROVIDER_VERSIONS, s, null, null, null, null, raw);
    }
    if (s.size() == 8 && "download".equals(s.get(5))) {
      requireVersion(s.get(4));
      return provider(TerraformPath.Kind.PROVIDER_DOWNLOAD, s, null, s.get(4), s.get(6), s.get(7), raw);
    }
    if (s.size() == 9 && "download".equals(s.get(5))) {
      requireVersion(s.get(4));
      String filename = s.get(8);
      requireFilename(filename);
      TerraformPath.Kind kind = "SHA256SUMS.sig".equals(filename)
          ? TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE
          : "SHA256SUMS".equals(filename)
              ? TerraformPath.Kind.PROVIDER_SHA256SUMS
              : TerraformPath.Kind.PROVIDER_ARCHIVE;
      return provider(
          kind, s, null, s.get(4), s.get(6), s.get(7), filename, raw);
    }
    if (s.size() == 8 && "package".equals(s.get(5))) {
      requireVersion(s.get(4));
      requireFilename(s.get(7));
      return provider(TerraformPath.Kind.PROVIDER_ARCHIVE, s, null, s.get(4), s.get(6), null, s.get(7), raw);
    }
    if (s.size() == 7) {
      requireVersion(s.get(4));
      String filename = s.get(6);
      requireFilename(filename);
      TerraformPath.Kind kind = filename.endsWith("_SHA256SUMS.sig")
          ? TerraformPath.Kind.PROVIDER_SHA256SUMS_SIGNATURE
          : filename.endsWith("_SHA256SUMS")
              ? TerraformPath.Kind.PROVIDER_SHA256SUMS
              : TerraformPath.Kind.UNKNOWN;
      return provider(kind, s, null, s.get(4), null, null, filename, raw);
    }
    return unknown(raw);
  }

  private TerraformPath module(TerraformPath.Kind kind, List<String> s, String credential,
      String version, String filename, String raw) {
    requireId(s.get(2), "namespace");
    requireId(s.get(3), "module name");
    requireId(s.get(4), "system");
    return new TerraformPath(kind, s.get(2), s.get(3), s.get(4), version,
        null, null, filename, credential, raw);
  }

  private TerraformPath provider(TerraformPath.Kind kind, List<String> s, String credential,
      String version, String os, String arch, String raw) {
    return provider(kind, s, credential, version, os, arch, null, raw);
  }

  private TerraformPath provider(TerraformPath.Kind kind, List<String> s, String credential,
      String version, String os, String arch, String filename, String raw) {
    requireId(s.get(2), "namespace");
    requireId(s.get(3), "provider type");
    if (os != null) requireId(os, "os");
    if (arch != null) requireId(arch, "architecture");
    return new TerraformPath(kind, s.get(2), s.get(3), null, version,
        os, arch, filename, credential, raw);
  }

  private static List<String> splitAndValidate(String raw) {
    if (raw.indexOf('\0') >= 0 || raw.toLowerCase(Locale.ROOT).contains("%00")
        || raw.toLowerCase(Locale.ROOT).contains("%2f") || raw.toLowerCase(Locale.ROOT).contains("%5c")
        || raw.toLowerCase(Locale.ROOT).contains("%25")) {
      throw new IllegalArgumentException("Unsafe Terraform repository path");
    }
    String[] values = raw.split("/", -1);
    List<String> result = new ArrayList<>(values.length);
    for (String value : values) {
      if (value.isEmpty() || ".".equals(value) || "..".equals(value) || value.indexOf('\\') >= 0) {
        throw new IllegalArgumentException("Unsafe Terraform repository path segment");
      }
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static List<String> splitRequestSegments(String raw) {
    if (raw.indexOf('\0') >= 0 || raw.toLowerCase(Locale.ROOT).contains("%00")) {
      throw new IllegalArgumentException("Unsafe Terraform repository path");
    }
    String[] values = raw.split("/", -1);
    List<String> result = new ArrayList<>(values.length);
    for (String value : values) {
      if (value.isEmpty() || ".".equals(value) || "..".equals(value) || value.indexOf('\\') >= 0) {
        throw new IllegalArgumentException("Unsafe Terraform repository path segment");
      }
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static String normalize(String rawPath) {
    String value = rawPath == null ? "" : rawPath.trim();
    while (value.startsWith("/")) value = value.substring(1);
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static void requireId(String value, String label) {
    if (!ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid Terraform " + label);
  }

  private static void requireVersion(String value) {
    if (!VERSION.matcher(value).matches()) throw new IllegalArgumentException("Invalid Terraform semantic version");
  }

  public static void requireFilename(String value) {
    if (value == null || value.isBlank() || value.length() > 255 || value.contains("/")
        || value.contains("\\") || value.contains("%") || value.contains("\r") || value.contains("\n")
        || ".".equals(value) || "..".equals(value) || !SAFE_FILENAME.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid Terraform archive filename");
    }
  }

  private static String decodeOnce(String value) {
    try {
      // URLDecoder implements form semantics, where a literal '+' means a space. This value is a
      // path segment, so protect literal plus signs while still decoding percent-encoded bytes.
      return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid Terraform URL token encoding", e);
    }
  }

  private static TerraformPath unknown(String raw) {
    return new TerraformPath(TerraformPath.Kind.UNKNOWN, null, null, null,
        null, null, null, null, null, raw);
  }

  public record ParsedRequest(String canonicalPath, String credentialSegment, TerraformPath path) {}
}
