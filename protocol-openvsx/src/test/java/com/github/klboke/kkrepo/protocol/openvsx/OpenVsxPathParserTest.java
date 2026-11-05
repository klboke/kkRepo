package com.github.klboke.kkrepo.protocol.openvsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OpenVsxPathParserTest {
  private final OpenVsxPathParser parser = new OpenVsxPathParser();

  @Test
  void parsesRegistryMetadataRoutes() {
    assertEquals(OpenVsxPath.Kind.ROOT, parser.parse("").kind());
    assertEquals(OpenVsxPath.Kind.REGISTRY_VERSION, parser.parse("api/version").kind());
    assertEquals(OpenVsxPath.Kind.SEARCH, parser.parse("api/-/search").kind());
    assertEquals(OpenVsxPath.Kind.QUERY, parser.parse("api/-/query").kind());
    assertEquals(OpenVsxPath.Kind.QUERY, parser.parse("api/v2/-/query").kind());
    assertEquals(OpenVsxPath.Kind.PUBLISH, parser.parse("api/-/publish").kind());

    OpenVsxPath latest = parser.parse("api/redhat/java");
    assertEquals(OpenVsxPath.Kind.EXTENSION, latest.kind());
    assertEquals("redhat", latest.namespace());
    assertEquals("java", latest.extension());

    assertEquals(OpenVsxPath.Kind.EXTENSION_VERSIONS,
        parser.parse("api/redhat/java/versions").kind());
    assertEquals(OpenVsxPath.Kind.VERSION_REFERENCES,
        parser.parse("api/redhat/java/version-references").kind());
    assertEquals(OpenVsxPath.Kind.EXTENSION_VERSION,
        parser.parse("api/redhat/java/1.2.3").kind());
    assertEquals(OpenVsxPath.Kind.REVIEWS, parser.parse("api/redhat/java/reviews").kind());

    OpenVsxPath file = parser.parse("api/redhat/java/1.2.3/file/extension/package.json");
    assertEquals(OpenVsxPath.Kind.FILE, file.kind());
    assertEquals("extension/package.json", file.filePath());

    assertEquals(OpenVsxPath.Kind.PLATFORM_EXTENSION,
        parser.parse("api/redhat/java/linux-x64").kind());
    assertEquals(OpenVsxPath.Kind.PLATFORM_VERSIONS,
        parser.parse("api/redhat/java/linux-x64/versions").kind());
    assertEquals(OpenVsxPath.Kind.PLATFORM_VERSION,
        parser.parse("api/redhat/java/linux-x64/1.2.3").kind());
    assertEquals(OpenVsxPath.Kind.PLATFORM_FILE,
        parser.parse("api/redhat/java/linux-x64/1.2.3/file/x").kind());
  }

  @Test
  void parsesVscodeGalleryCompatibilityRoutes() {
    assertEquals(OpenVsxPath.Kind.VSCODE_QUERY,
        parser.parse("vscode/gallery/extensionquery").kind());
    OpenVsxPath latest = parser.parse("vscode/gallery/redhat/java/latest");
    assertEquals(OpenVsxPath.Kind.VSCODE_LATEST, latest.kind());
    assertEquals("redhat", latest.namespace());
    assertEquals("java", latest.extension());
    OpenVsxPath vsix = parser.parse(
        "vscode/gallery/publishers/redhat/vsextensions/java/1.0.0/vspackage");
    assertEquals(OpenVsxPath.Kind.VSCODE_VSPACKAGE, vsix.kind());
    assertEquals(OpenVsxPath.Kind.VSCODE_ASSET,
        parser.parse("vscode/asset/redhat/java/1.0.0/Microsoft.VisualStudio.Services.Icons.Default")
            .kind());
    assertEquals(OpenVsxPath.Kind.VSCODE_UNPKG,
        parser.parse("vscode/unpkg/redhat/java/1.0.0/extension/package.json").kind());
    assertEquals(OpenVsxPath.Kind.VSCODE_ITEM, parser.parse("vscode/item").kind());
  }

  @Test
  void rejectsEncodedSeparatorsAndTraversal() {
    assertThrows(IllegalArgumentException.class, () -> parser.parse("api/redhat%2Fjava"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("api/../java"));
    assertThrows(IllegalArgumentException.class, () -> parser.parse("api/redhat//java"));
    assertEquals(OpenVsxPath.Kind.NAMESPACE, parser.parse("api/redhat").kind());
    assertEquals(OpenVsxPath.Kind.UNKNOWN, parser.parse("api/-/unknown").kind());
  }
}
