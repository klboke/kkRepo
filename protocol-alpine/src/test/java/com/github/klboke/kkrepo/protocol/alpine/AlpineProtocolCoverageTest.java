package com.github.klboke.kkrepo.protocol.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlpineProtocolCoverageTest {
  @Test
  void exposesHostedProxyAndGroupCapabilities() {
    AlpineRepositoryProtocol protocol = new AlpineRepositoryProtocol();
    assertEquals(RepositoryFormat.ALPINE, protocol.format());
    assertTrue(protocol.capability().hostedRead());
    assertTrue(protocol.capability().hostedWrite());
    assertTrue(protocol.capability().proxyRead());
    assertTrue(protocol.capability().groupRead());
  }

  @Test
  void validatesSignatureEntries() {
    var parsed = AlpineSignature.parseEntryName(".SIGN.RSA256.kkrepo-alpine.rsa.pub");
    assertEquals(AlpineSignature.Type.RSA256, parsed.type());
    assertEquals("kkrepo-alpine.rsa.pub", parsed.keyFilename());
  }

  @Test
  void validatesPkgInfoSafetyNumbersAndProviderPriority() {
    AlpinePackageInfo info = AlpinePackageInfo.parse(pkgInfo(
        "demo", "x86_64", "7", "provider_priority = 42\ncommit = deadbeef\n"));
    assertEquals(42, info.providerPriority());
    assertEquals("deadbeef", info.commit());
    assertEquals("demo", info.fields().get("pkgname").getFirst());
    assertEquals("42", info.indexRecord("Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=", 10).get('k'));

    assertThrows(IllegalArgumentException.class,
        () -> AlpinePackageInfo.parse(pkgInfo("demo", "x86_64", "7",
            "provider_priority = 2147483648\n")).providerPriority());
    assertThrows(IllegalArgumentException.class,
        () -> AlpinePackageInfo.parse(pkgInfo("bad/name", "x86_64", "7", "")));
    assertThrows(IllegalArgumentException.class,
        () -> AlpinePackageInfo.parse(pkgInfo("demo", "bad/arch", "7", "")));
    assertThrows(IllegalArgumentException.class,
        () -> AlpinePackageInfo.parse(pkgInfo("demo", "x86_64", "-1", "")));
    assertThrows(IllegalArgumentException.class,
        () -> AlpinePackageInfo.parse(pkgInfo("demo", "x86_64", "7", "").replace('\n', '\r')));
    assertThrows(IllegalArgumentException.class,
        () -> AlpinePackageInfo.parse(pkgInfo("demo", "x86_64", "7", "")
            + "Bad Key = value\n"));
  }

  @Test
  void validatesIndexFieldsNumbersUtf8AndUnsafeCharacters() {
    assertThrows(IllegalArgumentException.class, () -> new AlpineIndexRecord(List.of()));
    assertThrows(IllegalArgumentException.class, () -> record("-1", "demo"));
    assertThrows(IllegalArgumentException.class, () -> record("1", "bad\tname"));
    assertThrows(IllegalArgumentException.class,
        () -> new AlpineIndexRecord.Field('1', "value"));
    assertThrows(IllegalArgumentException.class,
        () -> new AlpineIndexRecord.Field('x', "bad\nvalue"));

    assertThrows(IllegalArgumentException.class, () -> AlpineIndex.parse(null));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineIndex.parse(new byte[] {(byte) 0xc3, 0x28}));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineIndex.parse("P:demo\r\n".getBytes(StandardCharsets.UTF_8)));
    assertThrows(IllegalArgumentException.class,
        () -> AlpineIndex.parse("malformed\n".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void mapsMediaTypesPathKindsAndChecksumFailures() {
    assertEquals(AlpineMediaTypes.APK_PACKAGE, AlpineMediaTypes.forPath("DEMO.APK"));
    assertEquals(AlpineMediaTypes.APK_INDEX, AlpineMediaTypes.forPath("APKINDEX.tar.gz"));
    assertEquals(AlpineMediaTypes.PUBLIC_KEY, AlpineMediaTypes.forPath("fixture.RSA.PUB"));
    assertEquals(AlpineMediaTypes.OCTET_STREAM, AlpineMediaTypes.forPath(null));

    AlpinePath index = new AlpinePath(
        AlpinePath.Kind.INDEX, "raw", "normalized", "v3.20", "main", "x86_64", null);
    AlpinePath apk = new AlpinePath(
        AlpinePath.Kind.PACKAGE, "raw", "normalized", "v3.20", "main", "x86_64", "demo.apk");
    assertTrue(index.metadata());
    assertFalse(index.immutable());
    assertFalse(apk.metadata());
    assertTrue(apk.immutable());

    assertThrows(IllegalArgumentException.class, () -> AlpineChecksums.v2Identity(new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> AlpineChecksums.requireSha256("bad"));
    assertThrows(IllegalArgumentException.class, () -> AlpineChecksums.requireV2Identity(null));
  }

  private static AlpineIndexRecord record(String size, String name) {
    return AlpineIndexRecord.builder()
        .field('C', "Q1AAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .field('P', name)
        .field('V', "1-r0")
        .field('A', "x86_64")
        .field('S', size)
        .field('I', "7")
        .build();
  }

  private static String pkgInfo(String name, String architecture, String size, String extra) {
    return """
        pkgname = %s
        pkgver = 1-r0
        size = %s
        arch = %s
        datahash = %s
        %s""".formatted(name, size, architecture, "a".repeat(64), extra);
  }
}
