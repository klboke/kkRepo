package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
