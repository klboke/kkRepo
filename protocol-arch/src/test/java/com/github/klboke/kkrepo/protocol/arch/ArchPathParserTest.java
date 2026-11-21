package com.github.klboke.kkrepo.protocol.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArchPathParserTest {
  private final ArchPathParser parser = new ArchPathParser();

  @Test
  void parsesDatabaseAliases() {
    assertDatabase("core.db", ArchPath.Kind.DATABASE, "core");
    assertDatabase("core.db.tar.gz", ArchPath.Kind.DATABASE_TAR, "core");
    assertDatabase("core.db.tar.xz", ArchPath.Kind.DATABASE_TAR, "core");
    assertDatabase("core.files", ArchPath.Kind.FILES, "core");
    assertDatabase("core.files.tar.gz", ArchPath.Kind.FILES_TAR, "core");
  }

  @Test
  void parsesPackageIdentity() {
    ArchPackageFilename pkg = ArchPackageFilename.parse(
        "python-requests-2.32.3-1-any.pkg.tar.zst");
    assertNotNull(pkg);
    assertEquals("python-requests", pkg.name());
    assertEquals("2.32.3-1", pkg.version());
    assertEquals("any", pkg.architecture());
    assertEquals("tar.zst", pkg.compression());

    ArchPath path = parser.parse("python-requests-2.32.3-1-any.pkg.tar.zst");
    assertEquals(ArchPath.Kind.PACKAGE, path.kind());
    assertEquals("python-requests", path.packageName());
    assertEquals("2.32.3-1", path.packageVersion());
  }

  @Test
  void parsesSignatureAndRoot() {
    assertEquals(ArchPath.Kind.ROOT, parser.parse("").kind());
    assertEquals(ArchPath.Kind.SIGNATURE, parser.parse("core.db.sig").kind());
    assertTrue(parser.parse("core.db.sig").databaseSignature());
    assertTrue(!parser.parse("foo-1.0-1-x86_64.pkg.tar.zst.sig").databaseSignature());
    assertEquals(ArchPath.Kind.SIGNATURE,
        parser.parse("foo-1.0-1-x86_64.pkg.tar.zst.sig").kind());
    assertEquals(ArchPath.Kind.PUBLIC_KEY, parser.parse("public-key").kind());
    assertEquals(ArchPath.Kind.PUBLIC_KEY, parser.parse("gpg.key").kind());
    assertEquals(ArchPath.Kind.UNKNOWN, parser.parse("nested/path").kind());
  }

  private void assertDatabase(String fileName, ArchPath.Kind kind, String name) {
    ArchPath path = parser.parse(fileName);
    assertEquals(kind, path.kind(), fileName);
    assertEquals(name, path.databaseName(), fileName);
  }
}
