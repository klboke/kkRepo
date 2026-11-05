package com.github.klboke.kkrepo.protocol.ohpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmPackageIdTest {
  @Test
  void preservesCanonicalIdentityAndClientLengthBoundary() {
    assertEquals(new OhpmPackageId("my_scope", "my.pkg"), OhpmPackageId.parse("@my_scope%2fmy.pkg"));
    assertEquals("a".repeat(128), OhpmPackageId.parse("a".repeat(128)).id());
    assertEquals("@s/" + "a".repeat(125), OhpmPackageId.parse("@s/" + "a".repeat(125)).id());
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageId.parse("a".repeat(129)));
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageId.parse("@s/" + "a".repeat(126)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " Package ", "Package", "1package", "_package", ".package", "-package",
      "package.", "package-", "package_", "@scope", "@scope/one/two", "one/two", "@1scope/pkg",
      "@scope./pkg", "@s.cope/pkg", "@scope_/pkg", "pkg+other", "pkg%2fother", "%2561bc"})
  void rejectsNamesTheOhpmClientCannotPublish(String name) {
    assertThrows(IllegalArgumentException.class, () -> OhpmPackageId.parse(name));
  }
}
