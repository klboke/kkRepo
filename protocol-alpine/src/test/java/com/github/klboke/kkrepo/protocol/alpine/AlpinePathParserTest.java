package com.github.klboke.kkrepo.protocol.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AlpinePathParserTest {
  private final AlpinePathParser parser = new AlpinePathParser();

  @Test
  void parsesV2IndexPackageAndExplicitV3Boundary() {
    AlpinePath index = parser.parse("/v3.23/main/x86_64/APKINDEX.tar.gz");
    assertEquals(AlpinePath.Kind.INDEX, index.kind());
    assertEquals("v3.23/main/x86_64", index.namespace());

    AlpinePath apk = parser.parse("v3.23/community/aarch64/curl-8.14.1-r2.apk");
    assertEquals(AlpinePath.Kind.PACKAGE, apk.kind());
    assertEquals("curl-8.14.1-r2.apk", apk.filename());

    AlpinePath mixedCase = parser.parse(
        "v3.23/main/x86_64/freeswitch-sounds-ru-RU-elena-32000-1.0-r0.apk");
    assertEquals(AlpinePath.Kind.PACKAGE, mixedCase.kind());
    assertEquals("freeswitch-sounds-ru-RU-elena-32000-1.0-r0.apk",
        AlpinePathParser.packageFilename(
            "freeswitch-sounds-ru-RU-elena-32000", "1.0-r0"));

    assertEquals(AlpinePath.Kind.V3_INDEX,
        parser.parse("edge/main/x86_64/Packages.adb").kind());
  }

  @Test
  void rejectsAmbiguousOrUnsafePaths() {
    for (String path : new String[]{
        "v3.23/main/x86_64/%2e%2e.apk",
        "v3.23/main/x86_64/a%252fb.apk",
        "v3.23//x86_64/a-1.apk",
        "V3.23/main/x86_64/a-1.apk",
        "v3.23/main/x86_64/APKINDEX.tar.gz.apk",
        ".alpine/snapshots/x/1/APKINDEX.tar.gz"}) {
      AlpinePath parsed = parser.parse(path);
      assertEquals(AlpinePath.Kind.UNKNOWN, parsed.kind(), path);
      assertNull(parsed.normalized(), path);
    }
  }
}
