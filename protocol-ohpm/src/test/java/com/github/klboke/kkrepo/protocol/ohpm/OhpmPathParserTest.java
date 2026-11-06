package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmPathParserTest {
  private final OhpmPathParser parser = new OhpmPathParser();

  @ParameterizedTest
  @ValueSource(strings = {"@scope/pkg", "@scope%2fpkg", "%40scope%2Fpkg", "/@scope/pkg"})
  void metadataAndPublishingShareOnePackageIdentity(String path) {
    for (String method : new String[] {"GET", "HEAD", "PUT"}) {
      OhpmPath parsed = parser.parse(method, path);
      assertEquals(method.equals("PUT") ? OhpmPath.Kind.PUBLISH : OhpmPath.Kind.METADATA, parsed.kind());
      assertEquals("@scope/pkg", parsed.assetPath());
    }
  }

  @Test
  void packageNamesCanOverlapMethodSpecificEndpoints() {
    assertEquals(OhpmPath.Kind.LOGIN, parser.parse("POST", "login").kind());
    assertEquals(OhpmPath.Kind.METADATA, parser.parse("GET", "login").kind());
    assertEquals(OhpmPath.Kind.PUBLISH, parser.parse("PUT", "login").kind());
    assertEquals(OhpmPath.Kind.METADATA, parser.parse("GET", "stream").kind());
    assertEquals(OhpmPath.Kind.PING, parser.parse("GET", "-/ping").kind());
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("PUT", "-/ping").kind());
    assertEquals(OhpmPath.Kind.STREAM_PUBLISH, parser.parse("POST", "stream/@scope%2fpkg").kind());
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("GET", "stream/pkg").kind());
  }

  @Test
  void deleteUsesVersionSuffixWithoutConfusingScopeOrBuildMetadata() {
    for (String path : new String[] {"@scope/pkg@1.2.3-beta.1+build.4", "@scope%2fpkg@1.2.3-beta.1%2Bbuild.4"}) {
      OhpmPath parsed = parser.parse("DELETE", path);
      assertEquals(OhpmPath.Kind.UNPUBLISH, parsed.kind());
      assertEquals("@scope/pkg", parsed.packageId().id());
      assertEquals("1.2.3-beta.1+build.4", parsed.version());
      assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("GET", path).kind());
    }
    assertNull(parser.parse("DELETE", "@scope/pkg").version());
    assertEquals("1.0.0", parser.parse("DELETE", "pkg@1.0.0").version());
  }

  @ParameterizedTest
  @ValueSource(strings = {"@scope/pkg/-/@scope/pkg-1.0.0.har", "@scope%2fpkg/-/@scope%2fpkg-1.0.0.har"})
  void downloadsKeepTheCompleteScopedFilePath(String path) {
    OhpmPath parsed = parser.parse("GET", path);
    assertEquals(OhpmPath.Kind.DOWNLOAD, parsed.kind());
    assertEquals("@scope/pkg-1.0.0.har", parsed.fileName());
    assertEquals("@scope/pkg/-/@scope/pkg-1.0.0.har", parsed.assetPath());
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("DELETE", path).kind());
    assertEquals(OhpmPath.Kind.DOWNLOAD, parser.parse("HEAD", path.replace(".har", ".hsp")).kind());
  }

  @Test
  void distTagsUseMetadataForReadsAndDedicatedRoutesForWrites() {
    for (String method : new String[] {"POST", "PUT", "DELETE"}) {
      OhpmPath parsed = parser.parse(method, "-/package/@scope%2fpkg/dist-tags/beta");
      assertEquals(OhpmPath.Kind.DIST_TAG, parsed.kind());
      assertEquals("@scope/pkg", parsed.packageId().id());
      assertEquals("beta", parsed.tag());
    }
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("GET", "-/package/pkg/dist-tags").kind());
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("GET", "-/package/pkg/dist-tags/latest").kind());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", "/", "//pkg", "pkg/", "pkg//-/pkg.har", "pkg/-/../secret.har", "pkg/-/%2e%2e/secret.har",
      "pkg/-/dir//pkg.har", "pkg/-/pkg.har/", "pkg/-/pkg%00.har", "pkg/-/pkg\\a.har",
      "pkg/-/%252e%252e/secret.har", "@scope%252fpkg", "pkg%C3%28", "pkg%", "pkg%ZZ",
      "pkg/-/pkg.tgz", "pkg/-/pkg.har?secret=value", "pkg/-/pkg.har#fragment", "pkg/-/ space.har"
  })
  void rejectsAmbiguousAndUnsafePaths(String path) {
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("GET", path).kind(), path);
  }

  @Test
  void rejectsIncompleteOrInvalidMutations() {
    for (String path : new String[] {"pkg@", "@scope/pkg@", "pkg@1", "pkg@01.0.0", "pkg@1.0.0/extra"}) {
      assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("DELETE", path).kind(), path);
    }
    for (String tag : new String[] {"", "../latest", "%2Flatest", "_invalid", "a".repeat(61)}) {
      assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("PUT", "-/package/pkg/dist-tags/" + tag).kind(), tag);
    }
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse(null, "pkg").kind());
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("GET", null).kind());
    assertEquals(OhpmPath.Kind.UNKNOWN, parser.parse("PATCH", "pkg").kind());
  }
}
