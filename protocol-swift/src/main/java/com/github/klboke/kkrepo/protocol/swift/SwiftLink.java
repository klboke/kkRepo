package com.github.klboke.kkrepo.protocol.swift;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** One RFC 8288 Link value with CRLF-safe rendering. */
public record SwiftLink(URI target, String relation, Map<String, String> attributes) {
  private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");

  public SwiftLink {
    if (target == null || unsafe(target.toASCIIString())) {
      throw new IllegalArgumentException("Invalid Swift Link target");
    }
    if (relation == null || relation.isBlank() || unsafe(relation)) {
      throw new IllegalArgumentException("Invalid Swift Link relation");
    }
    LinkedHashMap<String, String> copied = new LinkedHashMap<>();
    if (attributes != null) {
      attributes.forEach((name, value) -> {
        if (name == null || !TOKEN.matcher(name).matches() || name.equalsIgnoreCase("rel")
            || value == null || unsafe(value)) {
          throw new IllegalArgumentException("Invalid Swift Link attribute");
        }
        copied.put(name, value);
      });
    }
    attributes = Collections.unmodifiableMap(copied);
  }

  public static SwiftLink of(String target, String relation) {
    return new SwiftLink(URI.create(target), relation, Map.of());
  }

  public static SwiftLink alternateManifest(
      String target, String filename, String swiftToolsVersion) {
    String toolsVersion = SwiftToolsVersions.require(swiftToolsVersion);
    if (!SwiftToolsVersions.fromManifestFilename(filename).orElse("").equals(toolsVersion)) {
      throw new IllegalArgumentException("Manifest filename does not match Swift tools version");
    }
    return new SwiftLink(
        URI.create(target),
        "alternate",
        Map.of("filename", filename, "swift-tools-version", toolsVersion));
  }

  public String render() {
    StringBuilder result = new StringBuilder("<")
        .append(target.toASCIIString())
        .append(">; rel=\"")
        .append(quoted(relation))
        .append('"');
    attributes.forEach((name, value) -> result
        .append("; ")
        .append(name)
        .append("=\"")
        .append(quoted(value))
        .append('"'));
    return result.toString();
  }

  private static String quoted(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static boolean unsafe(String value) {
    return value.indexOf('<') >= 0 || value.indexOf('>') >= 0
        || value.chars().anyMatch(ch -> ch <= 0x1f || ch == 0x7f);
  }
}
