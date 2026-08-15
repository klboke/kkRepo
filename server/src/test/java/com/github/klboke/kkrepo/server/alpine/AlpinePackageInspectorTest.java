package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.protocol.alpine.AlpineChecksums;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlpinePackageInspectorTest {
  private final AlpinePackageInspector inspector = new AlpinePackageInspector();

  @Test
  void parsesConcatenatedV2MembersAndComputesProtocolIdentity() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.2.3-r4", "x86_64");

    try (AlpinePackageInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(fixture.bytes()), "demo-1.2.3-r4.apk")) {
      assertEquals("demo", inspected.info().name());
      assertEquals("1.2.3-r4", inspected.info().version());
      assertEquals("x86_64", inspected.info().architecture());
      assertEquals(AlpineChecksums.v2Identity(fixture.controlMember()), inspected.identity());
      assertEquals(AlpineTestPackage.sha256(fixture.dataMember()), inspected.dataSha256());
      assertEquals(fixture.bytes().length, inspected.size());
      assertEquals(64, inspected.sha256().length());
      assertTrue(Files.exists(inspected.file()));
      assertFalse(inspected.signatures().iterator().hasNext());
    }
  }

  @Test
  void rejectsBadFilenameTamperedDataAndExtraGzipMembers() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "aarch64");
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(fixture.bytes()), "other-1.0-r0.apk"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(fixture.bytes()), "../demo-1.0-r0.apk"));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(fixture.bytes()), "demo-1.0-r0.zip"));

    byte[] tampered = fixture.bytes();
    tampered[tampered.length - 9] ^= 1;
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(tampered), "demo-1.0-r0.apk"));

    byte[] tooMany = AlpineTestPackage.concatenate(
        fixture.controlMember(), fixture.dataMember(), fixture.dataMember(), fixture.dataMember());
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(tooMany), "demo-1.0-r0.apk"));
  }

  @Test
  void rejectsUnsafePayloadLinksAndResourceLimitOverruns() throws Exception {
    AlpineTestPackage.Fixture unsafe = AlpineTestPackage.apk(
        "demo", "2.0-r0", "x86_64",
        List.of(new AlpineTestPackage.Item("usr/bin/demo", new byte[0], "../../../outside")));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(unsafe.bytes()), "demo-2.0-r0.apk"));

    AlpineTestPackage.Fixture normal = AlpineTestPackage.apk("demo", "2.0-r0", "x86_64");
    AlpinePackageInspector compressedLimit = new AlpinePackageInspector(
        normal.bytes().length - 1L, 1024 * 1024, 1024 * 1024,
        32, 32, 1024 * 1024, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> compressedLimit.inspect(
        new ByteArrayInputStream(normal.bytes()), "demo-2.0-r0.apk"));

    AlpinePackageInspector expandedLimit = new AlpinePackageInspector(
        normal.bytes().length + 1L, 16, 32,
        32, 32, 1024 * 1024, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> expandedLimit.inspect(
        new ByteArrayInputStream(normal.bytes()), "demo-2.0-r0.apk"));
  }

  @Test
  void rejectsInvalidPkgInfoAndTruncatedInput() throws Exception {
    byte[] data = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item("usr/bin/demo", new byte[] {1})));
    byte[] badControl = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".PKGINFO", "pkgname = demo\n".getBytes(
            StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(badControl, data)),
        "demo-1.0-r0.apk"));
    byte[] valid = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64").bytes();
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(java.util.Arrays.copyOf(valid, valid.length - 1)),
        "demo-1.0-r0.apk"));
  }
}
