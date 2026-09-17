package com.github.klboke.kkrepo.protocol.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NpmPathParserTest {
  private final NpmPathParser parser = new NpmPathParser();

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
