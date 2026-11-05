package com.github.klboke.kkrepo.protocol.r;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RPathParserTest {
  private final RPathParser parser = new RPathParser();

  @Test
  void recognizesSourceMetadataAndPackages() {
    assertEquals(RPath.Kind.PACKAGES_GZIP, parser.parse("/src/contrib/PACKAGES.gz").kind());
    RPath source = parser.parse("src/contrib/example.pkg_1.2-3.tar.gz");
    assertEquals(RPath.Kind.SOURCE_PACKAGE, source.kind());
    assertEquals("example.pkg", source.packageName());
    assertEquals("1.2-3", source.version());
    assertEquals("src/contrib", source.namespace());
  }

  @Test
  void recognizesArchiveAndBinaryProxyPaths() {
    assertEquals(
        RPath.Kind.ARCHIVE_PACKAGE,
        parser.parse("src/contrib/Archive/example/example_1.0.0.tar.gz").kind());
    assertEquals(
        RPath.Kind.BINARY_PACKAGE,
        parser.parse("bin/windows/contrib/4.6/example_1.0.0.zip").kind());
  }

  @Test
  void isolatesCanonicalBinaryNamespacesAndRejectsMismatchedExtensions() {
    for (String namespace : java.util.List.of("bin/windows/contrib/4.5",
        "bin/macosx/big-sur-arm64/contrib/4.5", "bin/macosx/contrib/4.5",
        "bin/linux/noble-x86_64/4.5/src/contrib")) {
      String filename = RPathParser.packageFilename(namespace, "demo", "1.0.0");
      RPath path = parser.parse(namespace + "/" + filename);
      assertEquals(RPath.Kind.BINARY_PACKAGE, path.kind());
      assertEquals("demo", path.packageName());
      assertEquals(namespace, path.namespace());
      assertEquals(RPath.Kind.PACKAGES_GZIP, parser.parse(namespace + "/PACKAGES.gz").kind());
    }
    org.junit.jupiter.api.Assertions.assertFalse(
        parser.parse("bin/windows/contrib/4.5/demo_1.0.0.tar.gz").hostedPackage());
    org.junit.jupiter.api.Assertions.assertFalse(
        parser.parse("bin/macosx/contrib/latest/demo_1.0.0.tgz").hostedPackage());
    assertEquals("application/zip", RMediaTypes.forPath(
        parser.parse("bin/windows/contrib/4.5/demo_1.0.0.zip")));
  }

  @Test
  void rejectsAmbiguousAndEncodedPaths() {
    assertEquals(RPath.Kind.UNKNOWN, parser.parse("src/contrib/%2e%2e/secret").kind());
    assertEquals(RPath.Kind.UNKNOWN, parser.parse("src/contrib/a%2fb.tar.gz").kind());
    assertEquals(RPath.Kind.OTHER_GZIP, parser.parse("src/contrib/not_a_version.tar.gz").kind());
    assertNull(parser.parse("src//contrib/PACKAGES.gz").normalized());
  }
}
