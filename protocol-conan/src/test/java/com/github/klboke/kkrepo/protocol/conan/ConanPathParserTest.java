package com.github.klboke.kkrepo.protocol.conan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConanPathParserTest {
  private final ConanPathParser parser = new ConanPathParser();

  @Test
  void parsesOfficialConan2Routes() {
    assertEquals(ConanPath.Kind.PING, parser.parse("v1/ping").kind());
    assertEquals(ConanPath.Kind.AUTHENTICATE,
        parser.parse("v2/users/authenticate").kind());
    assertEquals(ConanPath.Kind.CHECK_CREDENTIALS,
        parser.parse("v2/users/check_credentials").kind());
    assertEquals(ConanPath.Kind.RECIPE_SEARCH,
        parser.parse("v2/conans/search").kind());

    String root = "v2/conans/zlib/1.3.1/_/_";
    assertEquals(ConanPath.Kind.RECIPE, parser.parse(root).kind());
    assertEquals(ConanPath.Kind.RECIPE_LATEST, parser.parse(root + "/latest").kind());
    assertEquals(ConanPath.Kind.RECIPE_REVISIONS,
        parser.parse(root + "/revisions").kind());
    assertEquals(ConanPath.Kind.PACKAGE_SEARCH, parser.parse(root + "/search").kind());

    String rrev = root + "/revisions/0123456789abcdef0123456789abcdef";
    ConanPath recipeFile = parser.parse(rrev + "/files/metadata/sign/signature");
    assertEquals(ConanPath.Kind.RECIPE_FILE, recipeFile.kind());
    assertEquals("metadata/sign/signature", recipeFile.filePath());
    assertNull(recipeFile.reference().user());
    assertNull(recipeFile.reference().channel());

    String pkg = rrev + "/packages/0123456789abcdef0123456789abcdef01234567";
    assertEquals(ConanPath.Kind.PACKAGE_REVISIONS,
        parser.parse(pkg + "/revisions").kind());
    assertEquals(ConanPath.Kind.PACKAGE_LATEST,
        parser.parse(pkg + "/latest").kind());
    ConanPath packageFile = parser.parse(
        pkg + "/revisions/fedcba9876543210fedcba9876543210/files/conan_package.tgz");
    assertEquals(ConanPath.Kind.PACKAGE_FILE, packageFile.kind());
    assertEquals("conan_package.tgz", packageFile.filePath());
  }

  @Test
  void parsesOnlyEndpointSpecificQueries() {
    ConanRequestTarget recipe = parser.parse(
        "v2/conans/search", "q=zlib%2F*&ignorecase=False");
    assertEquals("zlib/*", recipe.searchPattern());
    assertFalse(recipe.ignoreCase());

    ConanRequestTarget packages = parser.parse(
        "v2/conans/zlib/1.3.1/_/_/search", "list_only=True");
    assertTrue(packages.listOnly());

    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v1/ping", "q=anything"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=a&q=b"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "ignorecase=maybe"));
  }

  @Test
  void rejectsEncodedSeparatorsTraversalControlsAndInvalidReferences() {
    String prefix = "v2/conans/zlib/1.3.1/_/_/revisions/rrev/files/";
    assertEquals(ConanPath.Kind.UNKNOWN, parser.parse(prefix + "metadata%2Fsign").kind());
    assertEquals(ConanPath.Kind.UNKNOWN, parser.parse(prefix + "%252e%252e").kind());
    assertEquals(ConanPath.Kind.UNKNOWN, parser.parse(prefix + "../secret").kind());
    assertEquals(ConanPath.Kind.UNKNOWN, parser.parse(prefix + "bad\\path").kind());
    assertEquals(ConanPath.Kind.UNKNOWN,
        parser.parse("v2/conans/UPPER/1.0/_/_/revisions/r/files/x").kind());
    assertEquals(ConanPath.Kind.UNKNOWN,
        parser.parse("v2/conans/a/1.0/_/_/revisions/r/files/x").kind());
  }
}
