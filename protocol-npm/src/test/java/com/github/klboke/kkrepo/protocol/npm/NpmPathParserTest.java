package com.github.klboke.kkrepo.protocol.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NpmPathParserTest {
  private final NpmPathParser parser = new NpmPathParser();

  @Test
  void acceptsScopedTarballSuffixAndRevisionWithoutChangingPackageIdentity() {
    for (String path : new String[] {"@abc/abc-ui/-/@abc/abc-ui-0.1.1-beta.1.tgz",
        "@abc%2fabc-ui/-/@abc%2fabc-ui-0.1.1-beta.1.tgz"}) {
      NpmPath parsed = parser.parse(path);
      assertEquals(NpmPath.Kind.TARBALL, parsed.kind());
      assertEquals("@abc/abc-ui", parsed.packageId().id());
      assertEquals("@abc/abc-ui-0.1.1-beta.1.tgz", parsed.tarballName());
      assertEquals("123", parser.parse(path + "/-rev/123").revision());
    }
    for (String path : new String[] {"@abc/abc-ui/-/@abc/../secret", "demo/-/signed/../secret"}) {
      assertEquals(NpmPath.Kind.UNKNOWN, parser.parse(path).kind(), path);
    }
  }

  @Test
  void rewrittenSubdirectoryTarballsRemainRoutableWithTheirFullIdentity() {
    for (String suffix : new String[] {"signed/demo.tgz", "signed%2Fdemo.tgz", "@abc/a/b.tgz", "@cdn/demo.tgz"}) {
      String url = "https://upstream/download/-/" + suffix;
      var pkg = NpmPackageId.parse("@abc/demo");
      var parsed = parser.parse(pkg.tarballPath(NpmMetadata.tarballFilename(url)));
      assertEquals(NpmPath.Kind.TARBALL, parsed.kind());
      assertEquals(NpmMetadata.canonicalTarballName(url), parsed.tarballName());
      assertEquals("@abc/demo", parsed.packageId().id());
      assertEquals("rev", parser.parse(pkg.tarballPath(suffix) + "/-rev/rev").revision());
    }
    assertEquals(NpmPath.Kind.TARBALL, parser.parse("demo/-/signed/demo.tgz").kind());
    var unscoped = parser.parse("demo/-/@cdn/demo.tgz");
    assertEquals(NpmPath.Kind.TARBALL, unscoped.kind());
    assertEquals("demo", unscoped.packageId().id());
    assertEquals("@cdn/demo.tgz", unscoped.tarballName());
  }

  @Test
  void doesNotCollapseEmptyTarballSegmentsIntoOtherAssets() {
    for (String path : new String[] {"demo/-/signed//demo.tgz", "demo/-/signed%2F%2Fdemo.tgz",
        "@abc/demo/-/@abc//demo.tgz", "demo//-/demo.tgz", "demo/-/demo.tgz/",
        "demo/-/demo.tgz/-rev//123", "demo/-/demo.tgz/-rev/", "demo/-/demo.tgz/-rev/.."}) {
      assertEquals(NpmPath.Kind.UNKNOWN, parser.parse(path).kind(), path);
    }
  }

  @Test
  void scopedPackagesKeepTheirSeparatorAndAreDecodedOnlyOnce() {
    assertEquals("@scope/name", parser.parse("@scope%2fname").packageId().id());
    assertEquals("%61bc", parser.parse("%2561bc").packageId().id());
    assertEquals("@scope/%61bc",
        parser.parse("-/package/@scope%2f%2561bc/dist-tags/latest").packageId().id());
    for (String malformed : new String[] {"demo%", "demo%GG", "demo%C3%28"}) {
      assertEquals(NpmPath.Kind.UNKNOWN, parser.parse(malformed).kind());
    }
  }

  @Test
  void parsesPercentEncodedPlusWithoutChangingLiteralPlus() {
    NpmPath encoded = parser.parse("demo/1.2.3%2Bbuild");
    assertEquals(NpmPath.Kind.PACKAGE_VERSION, encoded.kind());
    assertEquals("1.2.3+build", encoded.packageVersion());

    NpmPath literal = parser.parse("demo/1.2.3+build");
    assertEquals(NpmPath.Kind.PACKAGE_VERSION, literal.kind());
    assertEquals("1.2.3+build", literal.packageVersion());
  }
}
