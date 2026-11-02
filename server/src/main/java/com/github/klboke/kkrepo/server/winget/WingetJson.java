package com.github.klboke.kkrepo.server.winget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.protocol.winget.WingetException;
import com.github.klboke.kkrepo.protocol.winget.WingetManifest;
import com.github.klboke.kkrepo.protocol.winget.WingetManifestParser;
import com.github.klboke.kkrepo.protocol.winget.WingetPackage;
import com.github.klboke.kkrepo.protocol.winget.WingetVersionSummary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Wire helpers shared by the WinGet REST handler and proxy. */
public final class WingetJson {
  public static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

  private WingetJson() {}

  public static JsonNode data(JsonNode root) {
    if (root != null && root.has("Data")) {
      return root.get("Data");
    }
    return root;
  }

  public static WingetManifest manifest(JsonNode root, ObjectMapper mapper) {
    JsonNode data = data(root);
    if (data == null || !data.isObject()) {
      throw WingetException.upstream("WinGet upstream did not return a package manifest");
    }
    return new WingetManifestParser().parse(data);
  }

  public static List<WingetPackage> packages(JsonNode root) {
    JsonNode data = data(root);
    if (data == null || !data.isArray()) {
      throw WingetException.upstream("WinGet upstream did not return a package search result");
    }
    List<WingetPackage> packages = new ArrayList<>();
    for (JsonNode item : data) {
      String identifier = text(item.get("PackageIdentifier"));
      if (identifier == null) continue;
      List<WingetVersionSummary> versions = new ArrayList<>();
      JsonNode versionsNode = item.get("Versions");
      if (versionsNode != null && versionsNode.isArray()) {
        for (JsonNode version : versionsNode) {
          String packageVersion = text(version.get("PackageVersion"));
          if (packageVersion == null) continue;
          versions.add(new WingetVersionSummary(
              packageVersion,
              nullToEmpty(text(version.get("Channel"))),
              strings(version.get("PackageFamilyNames")),
              strings(version.get("ProductCodes")),
              strings(version.get("AppsAndFeaturesEntryVersions")),
              strings(version.get("UpgradeCodes"))));
        }
      }
      packages.add(new WingetPackage(
          identifier, nullToEmpty(text(item.get("PackageName"))),
          nullToEmpty(text(item.get("Publisher"))), versions));
    }
    return packages;
  }

  public static byte[] readBody(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      total += read;
      if (total > MAX_BODY_BYTES) {
        throw WingetException.payloadTooLarge("The WinGet request body exceeds "
            + MAX_BODY_BYTES + " bytes");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static List<String> strings(JsonNode node) {
    if (node == null || !node.isArray()) return List.of();
    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      if (value.isTextual() && !value.asText().isBlank()) {
        values.add(value.asText());
      }
    }
    return values;
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual()) return null;
    String value = node.asText().trim();
    return value.isEmpty() ? null : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
