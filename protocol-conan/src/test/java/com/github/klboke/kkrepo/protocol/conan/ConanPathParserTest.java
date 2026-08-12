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
    assertEquals(ConanPath.Kind.RECIPE_REVISION, parser.parse(rrev).kind());
    assertEquals(ConanPath.Kind.RECIPE_FILES, parser.parse(rrev + "/files").kind());
    assertEquals(ConanPath.Kind.PACKAGES, parser.parse(rrev + "/packages").kind());
    assertEquals(ConanPath.Kind.PACKAGE_SEARCH, parser.parse(rrev + "/search").kind());
    ConanPath recipeFile = parser.parse(rrev + "/files/metadata/sign/signature");
    assertEquals(ConanPath.Kind.RECIPE_FILE, recipeFile.kind());
    assertEquals("metadata/sign/signature", recipeFile.filePath());
    assertNull(recipeFile.reference().user());
    assertNull(recipeFile.reference().channel());

    String pkg = rrev + "/packages/0123456789abcdef0123456789abcdef01234567";
    assertEquals(ConanPath.Kind.PACKAGE, parser.parse(pkg).kind());
    assertEquals(ConanPath.Kind.PACKAGE_REVISIONS,
        parser.parse(pkg + "/revisions").kind());
    assertEquals(ConanPath.Kind.PACKAGE_LATEST,
        parser.parse(pkg + "/latest").kind());
    ConanPath packageFile = parser.parse(
        pkg + "/revisions/fedcba9876543210fedcba9876543210/files/conan_package.tgz");
    assertEquals(ConanPath.Kind.PACKAGE_REVISION,
        parser.parse(pkg + "/revisions/fedcba9876543210fedcba9876543210").kind());
    assertEquals(ConanPath.Kind.PACKAGE_FILES,
        parser.parse(pkg + "/revisions/fedcba9876543210fedcba9876543210/files").kind());
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
    assertFalse(parser.parse(
        "v2/conans/zlib/1.3.1/_/_/search", "list_only=False").listOnly());
    assertTrue(parser.parse("v2/conans/search", null).ignoreCase());
    assertEquals("hello world", parser.parse(
        "v2/conans/search", "q=hello+world&ignorecase=").searchPattern());

    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v1/ping", "q=anything"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=a&q=b"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "ignorecase=maybe"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "unexpected=value"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "&q=value"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=%"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=%zz"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=%c3%28"));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=" + "a".repeat(513)));
    assertThrows(IllegalArgumentException.class,
        () -> parser.parse("v2/conans/search", "q=bad%00value"));
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
    for (String invalid : new String[] {
        null, "", "/", "v1/ping/", "v1//ping", "v2/users/unknown",
        "v3/conans/zlib/1.0/_/_", "v2/conans/zlib/1.0/_/_/wrong",
        "v2/conans/zlib/1.0/_/_/revisions/!",
        "v2/conans/zlib/1.0/_/_/revisions/r/wrong",
        "v2/conans/zlib/1.0/_/_/revisions/r/packages/!",
        "v2/conans/zlib/1.0/_/_/revisions/r/packages/pkg/wrong",
        "v2/conans/zlib/1.0/_/_/revisions/r/packages/pkg/revisions/!",
        "v2/conans/zlib/1.0/_/_/revisions/r/packages/pkg/revisions/p/wrong"
    }) {
      assertEquals(ConanPath.Kind.UNKNOWN, parser.parse(invalid).kind(), invalid);
    }

    assertTrue(ConanPathParser.validFilePath("metadata/sign"));
    for (String invalid : new String[] {
        null, "", "/absolute", "trailing/", "back\\slash", ".", "..", "a//b",
        "bad\u0001path", "a/../b", "a/./b", "a".repeat(1025)
    }) {
      assertFalse(ConanPathParser.validFilePath(invalid), invalid);
    }
  }

  @Test
  void classifiesResourceFamiliesWithoutRouteGuessing() {
    ConanReference recipe = new ConanReference("demo", "1.0", null, null, "r", null, null);
    ConanReference binary = recipe.packageCoordinate("pkg", "p");
    for (ConanPath.Kind kind : ConanPath.Kind.values()) {
      ConanReference reference = switch (kind) {
        case PACKAGE_SEARCH, PACKAGES, PACKAGE, PACKAGE_LATEST, PACKAGE_REVISIONS,
            PACKAGE_REVISION, PACKAGE_FILES, PACKAGE_FILE -> binary;
        default -> recipe;
      };
      ConanPath path = new ConanPath(kind, kind.name(), reference,
          kind == ConanPath.Kind.RECIPE_FILE || kind == ConanPath.Kind.PACKAGE_FILE ? "file" : null);
      assertEquals(kind == ConanPath.Kind.RECIPE_FILE || kind == ConanPath.Kind.PACKAGE_FILE,
          path.fileResource());
      assertEquals(switch (kind) {
        case RECIPE_SEARCH, PACKAGE_SEARCH, RECIPE_LATEST, RECIPE_REVISIONS,
            RECIPE_FILES, PACKAGE_LATEST, PACKAGE_REVISIONS, PACKAGE_FILES -> true;
        default -> false;
      }, path.discoveryResource());
      assertEquals(reference == binary, path.packageResource());
    }
  }
}
