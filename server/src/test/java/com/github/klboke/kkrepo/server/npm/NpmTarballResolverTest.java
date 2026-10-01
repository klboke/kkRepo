package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NpmTarballResolverTest {
  private static final NpmPackageId PACKAGE = NpmPackageId.parse("@scope/demo");
  private static final String BASE = "https://registry.example/npm";

  @Test
  void exactMetadataUrlPreservesEncodingQueryAndIndependentCacheIdentity() {
    String url = "https://cdn.example/download/@scope/demo/-/@scope%2Fdemo.tgz?token=signed";
    var root = metadata(url);
    var result = resolve("@scope/demo.tgz", "@scope%2Fdemo/-/@scope%2Fdemo.tgz", root);
    assertEquals(url, result.upstreamUrl());
    assertEquals("@scope/demo/-/@scope/demo.tgz", result.assetPath());
    assertEquals(NpmTarballResolver.Source.DECLARED_URL, result.source());
    assertEquals(metadata(url), root);
  }

  @Test
  void exactPathsWinBeforeLegacyAliasesAndNestedDelimitersRemainDistinct() {
    var root = Map.<String, Object>of("versions", Map.of(
        "1", version(BASE + "/demo/-/demo.tgz"),
        "2", version(BASE + "/demo/-/signed/-/demo.tgz")));
    var exact = resolve("demo.tgz", "@scope/demo/-/demo.tgz", root);
    assertEquals(BASE + "/demo/-/demo.tgz", exact.upstreamUrl());
    assertEquals(NpmTarballResolver.Source.DECLARED_URL, exact.source());
    assertEquals(BASE + "/demo/-/signed/-/demo.tgz",
        resolve("signed/-/demo.tgz", "ignored", root).upstreamUrl());
  }

  @Test
  void legacyBasenameIsExplicitAndCannotAuthorizeInventedDirectories() {
    var root = metadata(BASE + "/demo/-/signed/demo.tgz");
    var alias = resolve("demo.tgz", "@scope/demo/-/demo.tgz", root);
    assertEquals(NpmTarballResolver.Source.LEGACY_BASENAME, alias.source());
    assertEquals("@scope/demo/-/demo.tgz", alias.assetPath());
    assertEquals(BASE + "/demo/-/signed/demo.tgz", alias.upstreamUrl());
    assertThrows(NpmExceptions.NpmNotFoundException.class,
        () -> resolve("invented/demo.tgz", "ignored", root));
  }

  @Test
  void unavailableOrUnrelatedMetadataUsesRawRequestPathFallback() {
    String raw = "@scope%2Fdemo/-/@scope%2Fdemo.tgz";
    for (var root : java.util.Arrays.asList(null, Map.<String, Object>of(),
        Map.<String, Object>of("versions", "invalid"), metadata(BASE + "/other.tgz"))) {
      var result = resolve("@scope/demo.tgz", raw, root);
      assertEquals(BASE + "/" + raw, result.upstreamUrl());
      assertEquals("@scope/demo/-/@scope/demo.tgz", result.assetPath());
      assertEquals(NpmTarballResolver.Source.REQUEST_PATH_FALLBACK, result.source());
    }
  }

  @Test
  void incompleteEntriesAreIgnoredAndRelativeUrlsResolveAgainstTheRepositoryBase() {
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("null", null);
    versions.put("scalar", "invalid");
    versions.put("missing-dist", Map.of());
    versions.put("missing-url", Map.of("dist", Map.of("shasum", "abc")));
    versions.put("invalid-encoding", version("bad%XX.tgz"));
    versions.put("valid", version("downloads/demo.tgz?token=signed"));
    assertEquals(BASE + "/downloads/demo.tgz?token=signed",
        resolve("demo.tgz", "ignored", Map.of("versions", versions)).upstreamUrl());
  }

  @Test
  void ambiguousDeclaredUrlsNeverFallBackToAnUnrelatedRequestPath() {
    for (var root : java.util.List.of(
        Map.<String, Object>of("versions", Map.of("1", version(BASE + "/demo/-/a/demo.tgz"),
            "2", version(BASE + "/demo/-/b/demo.tgz"))),
        Map.<String, Object>of("versions", Map.of("1", version(BASE + "/demo.tgz?token=1"),
            "2", version(BASE + "/demo.tgz?token=2"))))) {
      assertThrows(NpmExceptions.BadUpstreamException.class, () -> resolve("demo.tgz", "ignored", root));
    }
    String url = BASE + "/demo.tgz";
    assertEquals(url, resolve("demo.tgz", "ignored", Map.of("versions", Map.of(
        "1", version(url), "2", version(url)))).upstreamUrl());
  }

  @Test
  void malformedDeclaredDestinationsAreRejectedBeforeDownload() {
    for (String url : java.util.List.of("file:///demo.tgz", "https://user:secret@registry.example/demo.tgz",
        "https://registry.example/demo.tgz#fragment", "https://[invalid/demo.tgz", "//invalid host/demo.tgz")) {
      String identity = com.github.klboke.kkrepo.protocol.npm.NpmMetadata.canonicalTarballName(url);
      assertThrows(NpmExceptions.BadUpstreamException.class, () -> resolve(identity, "ignored", metadata(url)), url);
    }
  }

  private static NpmTarballResolver.Download resolve(String identity, String raw, Map<String, Object> root) {
    return NpmTarballResolver.resolve(BASE, PACKAGE, identity, raw, root);
  }

  private static Map<String, Object> metadata(String url) {
    return Map.of("versions", Map.of("1.0.0", version(url)));
  }

  private static Map<String, Object> version(String url) {
    return Map.of("dist", Map.of("tarball", url));
  }
}
