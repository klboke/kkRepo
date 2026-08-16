package com.github.klboke.kkrepo.server.alpine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlpineGzipMembersTest {
  @Test
  void scansConcatenatedMembersAndBoundsExpandedStreams() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1-r0", "x86_64");
    var file = Files.createTempFile("alpine-members-", ".apk");
    try {
      Files.write(file, fixture.bytes());
      List<AlpineGzipMembers.Member> members = AlpineGzipMembers.scan(
          file, 3, 1024 * 1024, 2 * 1024 * 1024, Duration.ofSeconds(5));
      assertEquals(2, members.size());
      assertEquals(0L, members.getFirst().start());
      assertEquals(fixture.bytes().length, members.getLast().end());
      assertTrue(members.getFirst().compressedSize() > 0);
      assertTrue(members.getFirst().expandedSize() > 0);
      try (var expanded = AlpineGzipMembers.openExpanded(file, members.getFirst())) {
        assertTrue(expanded.read() >= 0);
        byte[] rest = expanded.readAllBytes();
        assertTrue(rest.length > 0);
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void rejectsMemberCountExpansionTimeoutHeadersTrailersAndDeflateCorruption() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1-r0", "x86_64");
    var file = Files.createTempFile("alpine-members-invalid-", ".apk");
    try {
      Files.write(file, fixture.bytes());
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 1, Long.MAX_VALUE, Long.MAX_VALUE, Duration.ofSeconds(5)));
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 1, Long.MAX_VALUE, Duration.ofSeconds(5)));
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, Long.MAX_VALUE, Long.MAX_VALUE, Duration.ofNanos(-1)));

      Files.write(file, new byte[0]);
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 100, 100, Duration.ofSeconds(1)));
      Files.write(file, new byte[] {1, 2, 3});
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 100, 100, Duration.ofSeconds(1)));

      byte[] badHeader = fixture.controlMember();
      badHeader[0] = 0;
      Files.write(file, badHeader);
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));

      byte[] reserved = fixture.controlMember();
      reserved[3] = (byte) 0xe0;
      Files.write(file, reserved);
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));

      byte[] truncated = Arrays.copyOf(fixture.controlMember(), fixture.controlMember().length - 4);
      Files.write(file, truncated);
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));

      byte[] badTrailer = fixture.controlMember();
      badTrailer[badTrailer.length - 8] ^= 1;
      Files.write(file, badTrailer);
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));

      byte[] badDeflate = fixture.controlMember();
      badDeflate[12] ^= 0x7f;
      Files.write(file, badDeflate);
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 3, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  void scansOptionalGzipHeadersAndBoundsTheirDeclaredLengths() throws Exception {
    AlpineTestPackage.Fixture fixture = AlpineTestPackage.apk("demo", "1-r0", "x86_64");
    byte[] original = fixture.controlMember();
    ByteArrayOutputStream optional = new ByteArrayOutputStream();
    optional.write(new byte[] {7, 0, 'K', 'K', 3, 0, 1, 2, 3});
    optional.write("fixture-name".getBytes(StandardCharsets.US_ASCII));
    optional.write(0);
    optional.write("fixture-comment".getBytes(StandardCharsets.US_ASCII));
    optional.write(0);
    optional.write(new byte[] {0, 0});
    byte[] decorated = addGzipHeader(original, 0x1e, optional.toByteArray());

    var file = Files.createTempFile("alpine-members-optional-", ".gz");
    try {
      Files.write(file, decorated);
      List<AlpineGzipMembers.Member> members = AlpineGzipMembers.scan(
          file, 1, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1));
      assertEquals(1, members.size());
      try (var expanded = AlpineGzipMembers.openExpanded(file, members.getFirst())) {
        assertTrue(expanded.readAllBytes().length > 0);
        assertEquals(-1, expanded.read());
      }

      Files.write(file, addGzipHeader(original, 0x04, new byte[] {(byte) 0xff, (byte) 0xff}));
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 1, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));

      byte[] unterminatedName = new byte[65_537];
      Arrays.fill(unterminatedName, (byte) 'a');
      Files.write(file, addGzipHeader(original, 0x08, unterminatedName));
      assertThrows(MavenExceptions.BadRequestException.class, () -> AlpineGzipMembers.scan(
          file, 1, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(1)));
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static byte[] addGzipHeader(byte[] original, int flags, byte[] optional) {
    byte[] result = new byte[original.length + optional.length];
    System.arraycopy(original, 0, result, 0, 10);
    result[3] = (byte) flags;
    System.arraycopy(optional, 0, result, 10, optional.length);
    System.arraycopy(original, 10, result, 10 + optional.length, original.length - 10);
    return result;
  }
}
