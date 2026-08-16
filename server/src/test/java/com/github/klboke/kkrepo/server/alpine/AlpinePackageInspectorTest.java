package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.protocol.alpine.AlpineChecksums;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

  @Test
  void acceptsSignatureAndHiddenControlMembersButRejectsMalformedSignatures() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    byte[] signature = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".SIGN.RSA256.fixture.rsa.pub", new byte[] {1, 2, 3})));
    try (AlpinePackageInspector.InspectedPackage inspected = inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            signature, fixture.controlMember(), fixture.dataMember())),
        "demo-1.0-r0.apk")) {
      assertEquals(1, inspected.signatures().size());
      assertEquals("fixture.rsa.pub", inspected.signatures().getFirst().keyFilename());
    }

    byte[] empty = AlpineTestPackage.gzipTar(List.of());
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            empty, fixture.controlMember(), fixture.dataMember())),
        "demo-1.0-r0.apk"));

    byte[] invalid = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item("not-a-signature", new byte[] {1})));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            invalid, fixture.controlMember(), fixture.dataMember())),
        "demo-1.0-r0.apk"));

    byte[] duplicate = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".SIGN.RSA.fixture.rsa.pub", new byte[] {1}),
        new AlpineTestPackage.Item(".SIGN.RSA.fixture.rsa.pub", new byte[] {2})));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            duplicate, fixture.controlMember(), fixture.dataMember())),
        "demo-1.0-r0.apk"));

    byte[] zeroLength = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".SIGN.RSA.fixture.rsa.pub", new byte[0])));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            zeroLength, fixture.controlMember(), fixture.dataMember())),
        "demo-1.0-r0.apk"));
  }

  @Test
  void rejectsDatahashMismatchEmptyPayloadAndUnsafeControlEntries() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    String wrongHash = fixture.pkgInfo().replace(
        AlpineTestPackage.sha256(fixture.dataMember()), "0".repeat(64));
    byte[] wrongControl = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".PKGINFO", wrongHash.getBytes(StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            wrongControl, fixture.dataMember())), "demo-1.0-r0.apk"));

    byte[] emptyData = AlpineTestPackage.gzipTar(List.of());
    String emptyHash = fixture.pkgInfo().replace(
        AlpineTestPackage.sha256(fixture.dataMember()), AlpineTestPackage.sha256(emptyData));
    byte[] emptyControl = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".PKGINFO", emptyHash.getBytes(StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(emptyControl, emptyData)),
        "demo-1.0-r0.apk"));

    byte[] dataInControl = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".PKGINFO", fixture.pkgInfo().getBytes(StandardCharsets.UTF_8)),
        new AlpineTestPackage.Item("usr/bin/demo", new byte[] {1})));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            dataInControl, fixture.dataMember())), "demo-1.0-r0.apk"));

    byte[] invalidUtf8 = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".PKGINFO", new byte[] {(byte) 0xc3, 0x28})));
    assertThrows(MavenExceptions.BadRequestException.class, () -> inspector.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(
            invalidUtf8, fixture.dataMember())), "demo-1.0-r0.apk"));
  }

  @Test
  void rejectsMissingBodiesIoFailuresAndInterruptedInspection() {
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(null, "demo-1.0-r0.apk"));
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(new ByteArrayInputStream(new byte[0]), "demo-1.0-r0.apk"));
    InputStream failing = new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("fixture read failure");
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws IOException {
        throw new IOException("fixture read failure");
      }
    };
    assertThrows(MavenExceptions.BadRequestException.class,
        () -> inspector.inspect(failing, "demo-1.0-r0.apk"));

    Thread.currentThread().interrupt();
    try {
      assertThrows(MavenExceptions.WritePolicyDenied.class,
          () -> inspector.inspect(new ByteArrayInputStream(new byte[] {1}), "demo-1.0-r0.apk"));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void enforcesControlDataAndEntryCountLimits() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1.0-r0", "x86_64");
    AlpinePackageInspector oneControlEntry = new AlpinePackageInspector(
        fixture.bytes().length + 1024L, 1024 * 1024, 1024 * 1024,
        1, 32, 1024 * 1024, 10, 1, 1000);
    byte[] control = AlpineTestPackage.gzipTar(List.of(
        new AlpineTestPackage.Item(".pre-install", new byte[] {1}),
        new AlpineTestPackage.Item(".PKGINFO", fixture.pkgInfo().getBytes(StandardCharsets.UTF_8))));
    assertThrows(MavenExceptions.BadRequestException.class, () -> oneControlEntry.inspect(
        new ByteArrayInputStream(AlpineTestPackage.concatenate(control, fixture.dataMember())),
        "demo-1.0-r0.apk"));

    AlpinePackageInspector oneDataEntry = new AlpinePackageInspector(
        fixture.bytes().length + 1024L, 1024 * 1024, 1024 * 1024,
        32, 1, 1024 * 1024, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> oneDataEntry.inspect(
        new ByteArrayInputStream(fixture.bytes()), "demo-1.0-r0.apk"));

    AlpinePackageInspector tinyEntry = new AlpinePackageInspector(
        fixture.bytes().length + 1024L, 1024 * 1024, 1024 * 1024,
        32, 32, 1, 10, 1, 1000);
    assertThrows(MavenExceptions.BadRequestException.class, () -> tinyEntry.inspect(
        new ByteArrayInputStream(fixture.bytes()), "demo-1.0-r0.apk"));
  }
}
