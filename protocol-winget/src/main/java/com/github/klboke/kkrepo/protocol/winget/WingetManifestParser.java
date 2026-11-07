package com.github.klboke.kkrepo.protocol.winget;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates WinGet combined package manifests.
 *
 * <p>The parser accepts the {@code packageManifests} schema used by WinGet clients and the
 * reference REST source. Unknown fields are preserved in the stored version JSON so newer client
 * schemas keep round-tripping.
 */
public final class WingetManifestParser {
  private static final int MAX_IDENTIFIER_LENGTH = 128;
  private static final int MAX_VERSION_LENGTH = 128;
  private static final int MAX_CHANNEL_LENGTH = 128;
  private static final int MAX_TEXT_LENGTH = 512;
  private static final int MAX_LIST_VALUES = 500;

  public WingetManifest parse(byte[] body, ObjectMapper mapper) {
    if (body == null || body.length == 0) {
      throw WingetException.badRequest("The request body is empty");
    }
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (java.io.IOException invalid) {
      throw WingetException.badRequest("The request body is not valid JSON");
    }
    return parse(root);
  }

  public WingetManifest parse(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw WingetException.badRequest("A WinGet manifest must be a JSON object");
    }
    String packageIdentifier = requiredText(root, "PackageIdentifier", "manifest");
    validateIdentifier(packageIdentifier);
    JsonNode versionsNode = root.get("Versions");
    if (versionsNode == null || !versionsNode.isArray() || versionsNode.isEmpty()) {
      throw WingetException.badRequest("Versions must be a non-empty array");
    }
    List<WingetVersion> versions = new ArrayList<>(versionsNode.size());
    Set<String> identities = new HashSet<>();
    for (JsonNode versionNode : versionsNode) {
      WingetVersion version = parseVersion(versionNode, packageIdentifier);
      String identity = version.packageVersion() + '\u0000' + WingetVersions.channelKey(version.channel());
      if (!identities.add(identity)) {
        throw WingetException.badRequest("Duplicate version " + version.packageVersion()
            + " for channel '" + version.channel() + "'");
      }
      versions.add(version);
    }
    return new WingetManifest(packageIdentifier, versions);
  }

  public WingetVersion parseVersion(JsonNode versionNode, String expectedIdentifier) {
    if (versionNode == null || !versionNode.isObject()) {
      throw WingetException.badRequest("Each entry of Versions must be a JSON object");
    }
    String versionIdentifier = optionalText(versionNode, "PackageIdentifier");
    String packageIdentifier = versionIdentifier == null ? expectedIdentifier : versionIdentifier;
    if (packageIdentifier == null || packageIdentifier.isBlank()) {
      throw WingetException.badRequest("PackageIdentifier is required");
    }
    validateIdentifier(packageIdentifier);
    if (expectedIdentifier != null && !expectedIdentifier.equals(packageIdentifier)) {
      throw WingetException.badRequest("Version PackageIdentifier '" + packageIdentifier
          + "' does not match the manifest PackageIdentifier '" + expectedIdentifier + "'");
    }
    if (versionIdentifier == null && versionNode.isObject()) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) versionNode)
          .put("PackageIdentifier", packageIdentifier);
    }

    String packageVersion = requiredText(versionNode, "PackageVersion", "version");
    if (packageVersion.length() > MAX_VERSION_LENGTH
        || packageVersion.chars().anyMatch(Character::isWhitespace)
        || hasControl(packageVersion)) {
      throw WingetException.badRequest("PackageVersion is invalid");
    }

    String channel = optionalText(versionNode, "Channel");
    if (channel == null) {
      channel = "";
    }
    if (channel.length() > MAX_CHANNEL_LENGTH) {
      throw WingetException.badRequest("Channel must not exceed " + MAX_CHANNEL_LENGTH
          + " characters");
    }
    if (channel.indexOf('/') >= 0 || channel.indexOf('\\') >= 0 || hasControl(channel)) {
      throw WingetException.badRequest("Channel contains characters that are not allowed");
    }

    JsonNode defaultLocale = versionNode.get("DefaultLocale");
    if (defaultLocale == null || !defaultLocale.isObject()) {
      throw WingetException.badRequest("DefaultLocale is required");
    }
    String packageLocale = requiredText(defaultLocale, "PackageLocale", "DefaultLocale");
    if (packageLocale.length() > 64 || hasControl(packageLocale)) {
      throw WingetException.badRequest("DefaultLocale.PackageLocale is invalid");
    }
    String packageName = requiredText(defaultLocale, "PackageName", "DefaultLocale");
    if (packageName.length() > MAX_TEXT_LENGTH) {
      throw WingetException.badRequest("DefaultLocale.PackageName is too long");
    }
    String publisher = requiredText(defaultLocale, "Publisher", "DefaultLocale");
    if (publisher.length() > MAX_TEXT_LENGTH) {
      throw WingetException.badRequest("DefaultLocale.Publisher is too long");
    }
    String moniker = optionalText(defaultLocale, "Moniker");
    if (moniker != null && (moniker.length() > MAX_CHANNEL_LENGTH || hasControl(moniker))) {
      throw WingetException.badRequest("DefaultLocale.Moniker is invalid");
    }

    List<String> tags = new ArrayList<>();
    addAll(tags, defaultLocale.get("Tags"));
    addAll(tags, versionNode.get("Tags"));
    List<String> commands = new ArrayList<>();
    addAll(commands, defaultLocale.get("Commands"));
    addAll(commands, versionNode.get("Commands"));

    List<String> packageFamilyNames = new ArrayList<>();
    List<String> productCodes = new ArrayList<>();
    List<String> installerTypes = new ArrayList<>();
    for (JsonNode installer : array(versionNode, "Installers")) {
      addText(packageFamilyNames, installer.get("PackageFamilyName"));
      addText(productCodes, installer.get("ProductCode"));
      addText(installerTypes, installer.get("InstallerType"));
    }
    List<String> upgradeCodes = new ArrayList<>();
    List<String> appsAndFeaturesVersions = new ArrayList<>();
    addAppsAndFeatures(versionNode.get("AppsAndFeaturesEntries"), productCodes, upgradeCodes,
        appsAndFeaturesVersions);
    for (JsonNode installer : array(versionNode, "Installers")) {
      addAppsAndFeatures(installer.get("AppsAndFeaturesEntries"), productCodes, upgradeCodes,
          appsAndFeaturesVersions);
    }
    List<String> markets = new ArrayList<>();
    addAll(markets, versionNode.get("Markets"));

    String normalized = WingetIndex.normalizeNameAndPublisher(packageName, publisher);
    WingetIndex index = new WingetIndex(packageIdentifier, packageVersion, channel, packageName,
        publisher, moniker == null ? "" : moniker, packageLocale, distinct(tags),
        distinct(commands), distinct(packageFamilyNames), distinct(productCodes),
        distinct(upgradeCodes), distinct(appsAndFeaturesVersions), distinct(installerTypes),
        distinct(markets), normalized, "");
    index = new WingetIndex(index.packageIdentifier(), index.packageVersion(), index.channel(),
        index.packageName(), index.publisher(), index.moniker(), index.defaultLocale(), index.tags(),
        index.commands(), index.packageFamilyNames(), index.productCodes(), index.upgradeCodes(),
        index.appsAndFeaturesEntryVersions(), index.installerTypes(), index.markets(),
        index.normalizedPackageNameAndPublisher(), WingetIndex.buildSearchText(index));
    return new WingetVersion(index, versionNode);
  }

  /** Restores a search index from its persisted summary JSON. */
  public static WingetIndex readIndex(JsonNode summary, ObjectMapper mapper) {
    try {
      return mapper.treeToValue(summary, WingetIndex.class);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Persisted WinGet index is not readable", invalid);
    }
  }

  private static void addAppsAndFeatures(JsonNode entries, List<String> productCodes,
      List<String> upgradeCodes, List<String> versions) {
    if (entries == null || !entries.isArray()) {
      return;
    }
    for (JsonNode entry : entries) {
      addText(productCodes, entry.get("ProductCode"));
      addText(upgradeCodes, entry.get("UpgradeCode"));
      addText(versions, entry.get("DisplayVersion"));
    }
  }

  private static Iterable<JsonNode> array(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    return value == null || !value.isArray() ? List.of() : value;
  }

  private static void addAll(List<String> values, JsonNode array) {
    if (array == null || !array.isArray()) {
      return;
    }
    for (JsonNode value : array) {
      addText(values, value);
    }
  }

  private static void addText(List<String> values, JsonNode value) {
    if (value != null && value.isTextual()) {
      String text = value.asText().trim();
      if (!text.isEmpty() && text.length() <= MAX_TEXT_LENGTH) {
        values.add(text);
      }
    }
  }

  private static List<String> distinct(List<String> values) {
    Set<String> seen = new HashSet<>();
    List<String> result = new ArrayList<>();
    for (String value : values) {
      if (seen.add(value.toLowerCase(Locale.ROOT))) {
        result.add(value);
        if (result.size() >= MAX_LIST_VALUES) {
          break;
        }
      }
    }
    return result;
  }

  private static void validateIdentifier(String identifier) {
    if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
      throw WingetException.badRequest("PackageIdentifier must not exceed "
          + MAX_IDENTIFIER_LENGTH + " characters");
    }
    if (identifier.isBlank() || identifier.indexOf('/') >= 0 || identifier.indexOf('\\') >= 0
        || hasControl(identifier) || identifier.chars().anyMatch(Character::isWhitespace)) {
      throw WingetException.badRequest("PackageIdentifier contains characters that are not allowed");
    }
  }

  private static String requiredText(JsonNode node, String field, String scope) {
    String value = optionalText(node, field);
    if (value == null) {
      throw WingetException.badRequest(scope + "." + field + " is required");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || !value.isTextual()) {
      return null;
    }
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }

  private static boolean hasControl(String value) {
    return value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
  }
}
