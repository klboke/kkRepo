package com.github.klboke.kkrepo.protocol.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AptPackageControlTest {
  @Test
  void validatesControlIdentityAndBuildsPackagesStanza() {
    AptPackageControl control = AptPackageControl.parse("""
        Package: demo
        Version: 1:2.0~rc1-3
        Architecture: amd64
        Maintainer: Demo <demo@example.com>
        Source: demo-source (1:2.0~rc1-3)
        Section: utils
        Priority: optional
        Description: demo package
         second line
        """);
    AptDeb822.Stanza stanza = control.packagesStanza(
        "pool/main/d/demo/demo_2.0~rc1-3_amd64.deb", 123,
        "a".repeat(32), "b".repeat(40), "c".repeat(64));
    assertEquals("demo", stanza.get("Package"));
    assertEquals("demo-source", control.sourcePackageName());
    assertEquals("123", stanza.get("Size"));
    assertEquals("c".repeat(64), stanza.get("SHA256"));
    assertEquals("pool/d/demo/demo_1..0_amd64.deb", control.packagesStanza(
        "pool/d/demo/demo_1..0_amd64.deb", 123,
        "a".repeat(32), "b".repeat(40), "c".repeat(64)).get("Filename"));
  }

  @Test
  void rejectsMissingIdentityAndUnsafeGeneratedPath() {
    assertThrows(IllegalArgumentException.class, () -> AptPackageControl.parse("Package: demo\n"));
    AptPackageControl control = AptPackageControl.parse("""
        Package: demo
        Version: 1.0
        Architecture: all
        Maintainer: Demo <demo@example.com>
        Description: demo
        """);
    assertThrows(IllegalArgumentException.class,
        () -> control.packagesStanza("../demo.deb", 1, null, null, "a".repeat(64)));
    assertThrows(IllegalArgumentException.class, () -> AptPackageControl.parse("""
        Package: demo
        Version: 1.0
        Architecture: amd64
        Maintainer: Demo <demo@example.com>
        Source: ../../escape
        Description: demo
        """));
  }
}
