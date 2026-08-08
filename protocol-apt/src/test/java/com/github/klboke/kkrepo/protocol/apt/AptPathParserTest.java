package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AptPathParserTest {
  private final AptPathParser parser = new AptPathParser();

  @Test
  void parsesStructuredMetadataAndPackages() {
    AptPath release = parser.parse("dists/bookworm/InRelease");
    assertEquals(AptPath.Kind.IN_RELEASE, release.kind());
    assertEquals("bookworm", release.distribution());

    AptPath packages = parser.parse("dists/bookworm/main/binary-amd64/Packages.xz");
    assertEquals(AptPath.Kind.PACKAGES, packages.kind());
    assertEquals("main", packages.component());
    assertEquals("amd64", packages.architecture());
    assertEquals(AptPath.Compression.XZ, packages.compression());

    AptPath byHash = parser.parse("dists/bookworm/main/binary-amd64/by-hash/SHA256/"
        + "a".repeat(64));
    assertEquals(AptPath.Kind.BY_HASH, byHash.kind());
    assertEquals("amd64", byHash.architecture());
  }

  @Test
  void parsesPoolPackagesAndPublicKey() {
    AptPath packagePath = parser.parse("pool/main/d/demo/demo_1%3a2.0-1_amd64.deb");
    assertEquals(AptPath.Kind.PACKAGE, packagePath.kind());
    assertEquals("amd64", packagePath.architecture());
    assertEquals(AptPath.Kind.PUBLIC_KEY, parser.parse("/gpg.key").kind());
  }

  @Test
  void supportsFlatMetadataWithoutWeakeningPathValidation() {
    assertEquals(AptPath.Kind.FLAT_METADATA, parser.parse("stable/Packages.gz", true).kind());
    assertEquals(AptPath.Kind.RELEASE_SIGNATURE, parser.parse("stable/Release.gpg", true).kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("stable/%2fetc/passwd", true).kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("stable/../Packages", true).kind());
  }

  @Test
  void rejectsEncodedSeparatorsSecondEncodingAndMalformedSegments() {
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("dists/bookworm/main%2fbinary-amd64/Packages").kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("dists/bookworm/%252e%252e/Packages").kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("dists//bookworm/Release").kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("dists/bookworm/Release?download=1").kind());
  }

  @Test
  void parsesEveryStructuredAndFlatMetadataFamily() {
    assertEquals(AptPath.Kind.ROOT, parser.parse(null).kind());
    assertEquals(AptPath.Kind.RELEASE, parser.parse("dists/stable/Release").kind());
    assertEquals(AptPath.Kind.RELEASE_SIGNATURE, parser.parse("dists/stable/Release.gpg").kind());
    assertEquals(AptPath.Kind.SOURCES,
        parser.parse("dists/stable/main/source/Sources.bz2").kind());
    assertEquals(AptPath.Compression.BZIP2,
        parser.parse("dists/stable/main/source/Sources.bz2").compression());
    assertEquals(AptPath.Kind.CONTENTS,
        parser.parse("dists/stable/main/Contents-amd64.gz").kind());
    assertEquals(AptPath.Kind.TRANSLATION,
        parser.parse("dists/stable/main/i18n/Translation-en.zst").kind());
    assertEquals(AptPath.Compression.ZSTD,
        parser.parse("dists/stable/main/i18n/Translation-en.zst").compression());
    assertEquals(AptPath.Kind.PDIFF,
        parser.parse("dists/stable/main/binary-arm64/Packages.diff/Index").kind());

    assertEquals(AptPath.Kind.PACKAGE, parser.parse("demo_1.0_all.deb", true).kind());
    assertEquals(AptPath.Kind.IN_RELEASE, parser.parse("flat/InRelease", true).kind());
    assertEquals(AptPath.Kind.RELEASE, parser.parse("flat/Release.xz", true).kind());
    assertEquals(AptPath.Kind.FLAT_METADATA, parser.parse("flat/Sources", true).kind());
    assertEquals(AptPath.Kind.FLAT_METADATA, parser.parse("flat/Contents-all.xz", true).kind());
    assertEquals(AptPath.Kind.BY_HASH, parser.parse(
        "flat/by-hash/SHA256/" + "A".repeat(64), true).kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("flat/random", true).kind());
  }

  @Test
  void validatesNamesArchitecturesPackagesAndHostilePaths() {
    assertTrue(AptPathParser.isSafeName("bookworm-updates"));
    assertFalse(AptPathParser.isSafeName(null));
    assertFalse(AptPathParser.isSafeName("bad/name"));
    assertTrue(AptPathParser.isArchitecture("amd64"));
    assertFalse(AptPathParser.isArchitecture(null));
    assertTrue(AptPathParser.isPackageFile("demo_1.0_amd64.udeb"));
    assertFalse(AptPathParser.isPackageFile(null));
    assertEquals(null, parser.parse("demo.deb").architecture());

    for (String path : new String[] {
        "dists/stable/Unknown", "dists/bad%20name/Release",
        "dists/stable/bad%20component/binary-amd64/Packages",
        "dists/stable/main/binary-bad%20arch/Packages",
        "dists/stable/main/binary-amd64/by-hash/SHA256/not-a-digest",
        "dists/stable/main/unknown/file", "a/./b", "a/../b",
        "bad\\path", "bad#fragment", "bad\u007fpath", "%", "%zz", "%2f", "%5c", "%25",
        "%c3%28"
    }) {
      assertEquals(AptPath.Kind.UNKNOWN, parser.parse(path).kind(), path);
    }
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("a".repeat(2049)).kind());
    assertEquals(AptPath.Kind.UNKNOWN, parser.parse("x/" + "a".repeat(256)).kind());
  }
}
