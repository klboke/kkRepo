package com.github.klboke.kkrepo.protocol.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NpmPathParserTest {
  private final NpmPathParser parser = new NpmPathParser();

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
