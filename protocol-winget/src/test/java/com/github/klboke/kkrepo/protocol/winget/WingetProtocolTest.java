package com.github.klboke.kkrepo.protocol.winget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class WingetProtocolTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final WingetManifestParser parser = new WingetManifestParser();

  @Test
  void parsesManifestAndBuildsSearchIndex() {
    WingetManifest manifest = parser.parse(body("Contoso.Tool", "1.2.3", null));

    assertEquals("Contoso.Tool", manifest.packageIdentifier());
    assertEquals(1, manifest.versions().size());
    WingetIndex index = manifest.versions().getFirst().index();
    assertEquals("Contoso Tool", index.packageName());
    assertEquals("Contoso", index.publisher());
    assertEquals("contoso", index.moniker());
    assertEquals(List.of("cli", "tool"), index.tags());
    assertEquals(List.of("contoso.exe"), index.commands());
    assertEquals(List.of("Contoso.Tool_8wekyb3d8bbwe"), index.packageFamilyNames());
    assertEquals(List.of("{PRODUCT-CODE}"), index.productCodes());
    assertEquals(List.of("{UPGRADE-CODE}"), index.upgradeCodes());
    assertEquals(List.of("1.2.3"), index.appsAndFeaturesEntryVersions());
    assertEquals(List.of("msi"), index.installerTypes());
    assertTrue(index.searchText().contains("contoso tool"));
    assertEquals("contosotoolcontoso", index.normalizedPackageNameAndPublisher());
  }

  @Test
  void rejectsDuplicateVersionAndChannel() {
    WingetException error = assertThrows(WingetException.class,
        () -> parser.parse(body("Contoso.Tool", "1.2.3", "1.2.3")));
    assertEquals(400, error.status());
  }

  @Test
  void rejectsMismatchedVersionIdentifier() throws Exception {
    var root = body("Contoso.Tool", "1.2.3", null);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("Versions").get(0))
        .put("PackageIdentifier", "Other.Tool");
    WingetException error = assertThrows(WingetException.class, () -> parser.parse(root));
    assertEquals(400, error.status());
  }

  @Test
  void searchMatchesQueryInclusionsAndFilters() throws Exception {
    WingetManifest manifest = parser.parse(body("Contoso.Tool", "1.2.3", null));
    WingetIndex index = manifest.versions().getFirst().index();

    WingetSearchRequest query = WingetSearchRequest.parse(mapper.readTree("""
        {"Query":{"KeyWord":"contoso","MatchType":"SubstringIgnoreCase"},"MaximumResults":10}
        """));
    assertTrue(WingetSearch.matches(index, query));

    WingetSearchRequest inclusion = WingetSearchRequest.parse(mapper.readTree("""
        {"Inclusions":[
          {"PackageMatchField":"PackageName","RequestMatch":{"KeyWord":"Unrelated","MatchType":"Exact"}},
          {"PackageMatchField":"Moniker","RequestMatch":{"KeyWord":"contoso","MatchType":"Exact"}}
        ]}
        """));
    assertTrue(WingetSearch.matches(index, inclusion));

    WingetSearchRequest filter = WingetSearchRequest.parse(mapper.readTree("""
        {"Filters":[{"PackageMatchField":"HasInstallerType",
          "RequestMatch":{"KeyWord":"msi","MatchType":"Exact"}}]}
        """));
    assertTrue(WingetSearch.matches(index, filter));

    WingetSearchRequest publisher = WingetSearchRequest.parse(mapper.readTree("""
        {"Filters":[{"PackageMatchField":"Publisher",
          "RequestMatch":{"KeyWord":"contoso","MatchType":"CaseInsensitive"}}]}
        """));
    assertTrue(WingetSearch.matches(index, publisher));

    WingetSearchRequest prefix = WingetSearchRequest.parse(mapper.readTree("""
        {"Query":{"KeyWord":"Cont","MatchType":"StartsWith"}}
        """));
    assertTrue(WingetSearch.matches(index, prefix));

    WingetSearchRequest mismatched = WingetSearchRequest.parse(mapper.readTree("""
        {"Filters":[{"PackageMatchField":"PackageFamilyName",
          "RequestMatch":{"KeyWord":"Other_123","MatchType":"Exact"}}]}
        """));
    assertFalse(WingetSearch.matches(index, mismatched));
  }

  @Test
  void searchResponseUsesVersionSummaries() throws Exception {
    WingetManifest manifest = parser.parse(body("Contoso.Tool", "1.2.3", null));
    WingetSearchRequest request = WingetSearchRequest.parse(mapper.readTree("""
        {"Query":{"KeyWord":"tool","MatchType":"SubstringIgnoreCase"}}
        """));
    List<WingetPackage> packages = WingetSearch.search(
        manifest.versions().stream().map(WingetVersion::index).toList(), request, 20);
    assertEquals(1, packages.size());
    assertEquals("Contoso.Tool", packages.getFirst().packageIdentifier());
    assertEquals("{PRODUCT-CODE}", packages.getFirst().versions().getFirst().productCodes().getFirst());
  }

  @Test
  void sortsVersionsNumericallyAndPrereleaseLast() {
    assertTrue(WingetVersions.compare("1.0-1", "2.0-1") < 0);
    assertTrue(WingetVersions.compare("1.2.3", "1.10.0") < 0);
    assertTrue(WingetVersions.compare("1.0.0-beta", "1.0.0") < 0);
    assertTrue(WingetVersions.compare("1.0.0-beta.2", "1.0.0-beta.10") < 0);
    assertEquals(0, WingetVersions.compare("1.0", "1.0.0"));
  }

  @Test
  void parsesRoutesWithAndWithoutApiPrefix() {
    WingetPath.WingetPathParser paths = WingetPath.parser();
    assertEquals(WingetPath.Kind.INFORMATION, paths.parse("api/information").kind());
    assertEquals(WingetPath.Kind.INFORMATION, paths.parse("/api/information/").kind());
    assertEquals(WingetPath.Kind.MANIFEST_SEARCH, paths.parse("api/manifestSearch").kind());
    assertEquals(WingetPath.Kind.MANIFEST, paths.parse("api/packageManifests/Contoso.Tool").kind());
    assertEquals("Contoso.Tool", paths.parse("api/packageManifests/Contoso.Tool").packageIdentifier());
    assertEquals(WingetPath.Kind.PACKAGES, paths.parse("api/packages/Contoso.Tool").kind());
    assertEquals(WingetPath.Kind.UNKNOWN, paths.parse("api/unknown").kind());
  }

  private com.fasterxml.jackson.databind.JsonNode body(String identifier, String version, String duplicateVersion) {
    String versions = versionEntry(identifier, version) + (duplicateVersion == null ? "" : ","
        + versionEntry(identifier, duplicateVersion));
    String json = """
        {
          "PackageIdentifier": "%s",
          "Versions": [%s]
        }
        """.formatted(identifier, versions);
    try {
      return mapper.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException(invalid);
    }
  }

  private String versionEntry(String identifier, String version) {
    return """
        {
          "PackageIdentifier": "%s",
          "PackageVersion": "%s",
          "DefaultLocale": {
            "PackageLocale": "en-US",
            "Publisher": "Contoso",
            "PackageName": "Contoso Tool",
            "Moniker": "contoso",
            "Tags": ["cli", "tool"],
            "Commands": ["contoso.exe"]
          },
          "Installers": [
            {
              "InstallerType": "msi",
              "PackageFamilyName": "Contoso.Tool_8wekyb3d8bbwe",
              "ProductCode": "{PRODUCT-CODE}",
              "AppsAndFeaturesEntries": [
                {"DisplayVersion": "%s", "ProductCode": "{PRODUCT-CODE}", "UpgradeCode": "{UPGRADE-CODE}"}
              ]
            }
          ]
        }
        """.formatted(identifier, version, version);
  }
}
