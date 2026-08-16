package com.github.klboke.kkrepo.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.security.scan.ScannerContract.ResourceLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlpinePackageCatalogerTest {
  @TempDir Path temporary;
  private final AlpinePackageCataloger cataloger = new AlpinePackageCataloger();

  @Test
  void extractsPayloadAndCreatesApkIdentitySidecars() throws Exception {
    byte[] packageBytes = apk(List.of(
        new Item("usr/bin/demo", "payload".getBytes(StandardCharsets.UTF_8), null),
        new Item("usr/bin/demo-link", new byte[0], "demo")));
    Path artifact = temporary.resolve("demo.apk");
    Files.write(artifact, packageBytes);

    AlpinePackageCataloger.Prepared prepared = cataloger.prepare(
        artifact, limits(1024 * 1024), temporary.resolve("work"), new ScanDeadline(10));

    assertEquals("demo", prepared.name());
    assertEquals("1.0-r0", prepared.version());
    assertEquals("x86_64", prepared.architecture());
    assertTrue(prepared.identity().startsWith("Q1"));
    assertEquals("payload", Files.readString(prepared.scanRoot().resolve("usr/bin/demo")));
    assertTrue(Files.readString(prepared.scanRoot().resolve("lib/apk/db/installed"))
        .contains("P:demo"));
    assertTrue(Files.readString(
        prepared.scanRoot().resolve(".kkrepo/alpine-apk-v2.identity"))
        .contains("schema=alpine-apk-v2"));
  }

  @Test
  void rejectsTraversalTamperingAndExpandedLimit() throws Exception {
    Path traversal = temporary.resolve("traversal.apk");
    Files.write(traversal, apk(List.of(new Item("../../outside", new byte[] {1}, null))));
    assertCode("ARCHIVE_PATH_ESCAPE", () -> cataloger.prepare(
        traversal, limits(1024 * 1024), temporary.resolve("bad-work"), new ScanDeadline(10)));

    byte[] valid = apk(List.of(new Item("usr/share/demo", new byte[512], null)));
    valid[valid.length - 9] ^= 1;
    Path tampered = temporary.resolve("tampered.apk");
    Files.write(tampered, valid);
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        tampered, limits(1024 * 1024), temporary.resolve("tamper-work"), new ScanDeadline(10)));

    Path large = temporary.resolve("large.apk");
    Files.write(large, apk(List.of(new Item("usr/share/demo", new byte[4096], null))));
    assertCode("ALPINE_ARCHIVE_LIMIT", () -> cataloger.prepare(
        large, limits(256), temporary.resolve("limit-work"), new ScanDeadline(10)));
  }

  @Test
  void acceptsSignedMembersOptionalGzipHeadersAndDirectoryEntries() throws Exception {
    byte[] packageBytes = apk(
        List.of(
            Item.directory("usr/share/demo/"),
            new Item("usr/share/demo/payload", "signed".getBytes(StandardCharsets.UTF_8), null)),
        List.of(new Item(".INSTALL", "post-install".getBytes(StandardCharsets.UTF_8), null)),
        List.of(new Item(
            ".SIGN.RSA256.fixture.rsa.pub", new byte[] {1, 2, 3}, null)),
        true);
    Path artifact = temporary.resolve("signed.apk");
    Files.write(artifact, packageBytes);

    AlpinePackageCataloger.Prepared prepared = cataloger.prepare(
        artifact, limits(1024 * 1024), temporary.resolve("signed-work"), new ScanDeadline(10));

    assertEquals(4, prepared.entries());
    assertEquals(
        "signed", Files.readString(prepared.scanRoot().resolve("usr/share/demo/payload")));
  }

  @Test
  void rejectsMalformedMemberShapesMetadataAndSignatures() throws Exception {
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        temporary.resolve("missing.apk"),
        limits(1024 * 1024),
        temporary.resolve("missing-work"),
        new ScanDeadline(10)));

    Path singleMember = temporary.resolve("single-member.apk");
    Files.write(singleMember, gzipTar(List.of(
        new Item("usr/share/demo", new byte[] {1}, null))));
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        singleMember,
        limits(1024 * 1024),
        temporary.resolve("single-work"),
        new ScanDeadline(10)));

    byte[] data = gzipTar(List.of(new Item("usr/share/demo", new byte[] {1}, null)));
    byte[] malformedControl = gzipTar(List.of(
        new Item(".PKGINFO", new byte[] {(byte) 0xc3, 0x28}, null)));
    Path malformedMetadata = temporary.resolve("malformed-metadata.apk");
    Files.write(malformedMetadata, concatenate(malformedControl, data));
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        malformedMetadata,
        limits(1024 * 1024),
        temporary.resolve("metadata-work"),
        new ScanDeadline(10)));

    Path invalidSignature = temporary.resolve("invalid-signature.apk");
    Files.write(invalidSignature, apk(
        List.of(new Item("usr/share/demo", new byte[] {1}, null)),
        List.of(),
        List.of(new Item(".SIGN.UNKNOWN.fixture.rsa.pub", new byte[] {1}, null)),
        false));
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        invalidSignature,
        limits(1024 * 1024),
        temporary.resolve("invalid-signature-work"),
        new ScanDeadline(10)));

    Path linkedSignature = temporary.resolve("linked-signature.apk");
    Files.write(linkedSignature, apk(
        List.of(new Item("usr/share/demo", new byte[] {1}, null)),
        List.of(),
        List.of(new Item(".SIGN.RSA.fixture.rsa.pub", new byte[0], "target")),
        false));
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        linkedSignature,
        limits(1024 * 1024),
        temporary.resolve("linked-signature-work"),
        new ScanDeadline(10)));
  }

  @Test
  void rejectsUnsafeEntriesAndCorruptGzipMembers() throws Exception {
    Path unsafe = temporary.resolve("unsafe.apk");
    Files.write(unsafe, apk(List.of(Item.characterDevice("dev/demo"))));
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        unsafe, limits(1024 * 1024), temporary.resolve("unsafe-work"), new ScanDeadline(10)));

    byte[] invalidHeader = apk(List.of(new Item("usr/share/demo", new byte[] {1}, null)));
    invalidHeader[0] = 0;
    Path badHeader = temporary.resolve("bad-header.apk");
    Files.write(badHeader, invalidHeader);
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        badHeader, limits(1024 * 1024), temporary.resolve("header-work"), new ScanDeadline(10)));

    byte[] invalidDeflate = apk(List.of(new Item("usr/share/demo", new byte[] {1}, null)));
    invalidDeflate[10] = 0x07;
    Path badDeflate = temporary.resolve("bad-deflate.apk");
    Files.write(badDeflate, invalidDeflate);
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        badDeflate,
        limits(1024 * 1024),
        temporary.resolve("deflate-work"),
        new ScanDeadline(10)));

    byte[] invalidCrc = apk(List.of(new Item("usr/share/demo", new byte[] {1}, null)));
    invalidCrc[invalidCrc.length - 8] ^= 1;
    Path badCrc = temporary.resolve("bad-crc.apk");
    Files.write(badCrc, invalidCrc);
    assertCode("ALPINE_PACKAGE_INVALID", () -> cataloger.prepare(
        badCrc, limits(1024 * 1024), temporary.resolve("crc-work"), new ScanDeadline(10)));
  }

  private static void assertCode(String code, Runnable invocation) {
    ScannerRequestException failure = assertThrows(ScannerRequestException.class, invocation::run);
    assertEquals(code, failure.code());
  }

  private static ResourceLimits limits(long expanded) {
    return new ResourceLimits(1024 * 1024, 100, expanded, expanded, 1, 10);
  }

  private static byte[] apk(List<Item> dataEntries) throws Exception {
    return apk(dataEntries, List.of(), List.of(), false);
  }

  private static byte[] apk(
      List<Item> dataEntries,
      List<Item> controlExtras,
      List<Item> signatureEntries,
      boolean optionalHeaders) throws Exception {
    byte[] data = gzipTar(dataEntries);
    if (optionalHeaders) data = withOptionalGzipHeaders(data);
    String pkgInfo = """
        pkgname = demo
        pkgver = 1.0-r0
        pkgdesc = fixture
        url = https://example.invalid
        size = 7
        arch = x86_64
        origin = demo
        license = MIT
        depend = musl
        datahash = %s
        """.formatted(sha256(data));
    ArrayList<Item> controlEntries = new ArrayList<>();
    controlEntries.add(new Item(
        ".PKGINFO", pkgInfo.getBytes(StandardCharsets.UTF_8), null));
    controlEntries.addAll(controlExtras);
    byte[] control = gzipTar(controlEntries);
    if (optionalHeaders) control = withOptionalGzipHeaders(control);
    if (signatureEntries.isEmpty()) return concatenate(control, data);
    byte[] signature = gzipTar(signatureEntries);
    if (optionalHeaders) signature = withOptionalGzipHeaders(signature);
    return concatenate(signature, control, data);
  }

  private static byte[] gzipTar(List<Item> entries) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(result);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      for (Item item : entries) {
        TarArchiveEntry entry;
        if (item.type() == TarConstants.LF_NORMAL) {
          entry = new TarArchiveEntry(item.name());
          entry.setSize(item.bytes().length);
        } else {
          entry = new TarArchiveEntry(item.name(), item.type());
          if (item.linkTarget() != null) entry.setLinkName(item.linkTarget());
          entry.setSize(0);
        }
        tar.putArchiveEntry(entry);
        if (item.type() == TarConstants.LF_NORMAL) tar.write(item.bytes());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return result.toByteArray();
  }

  private static byte[] withOptionalGzipHeaders(byte[] member) throws IOException {
    ByteArrayOutputStream header = new ByteArrayOutputStream();
    header.write(member, 0, 3);
    header.write(0x04 | 0x08 | 0x10 | 0x02);
    header.write(member, 4, 6);
    header.write(new byte[] {6, 0, 'K', 'K', 2, 0, 1, 2});
    header.write("fixture.apk\0".getBytes(StandardCharsets.US_ASCII));
    header.write("kkrepo\0".getBytes(StandardCharsets.US_ASCII));
    CRC32 crc = new CRC32();
    byte[] prefix = header.toByteArray();
    crc.update(prefix);
    header.write((int) crc.getValue() & 0xff);
    header.write((int) (crc.getValue() >>> 8) & 0xff);
    header.write(member, 10, member.length - 10);
    return header.toByteArray();
  }

  private static byte[] concatenate(byte[]... sections) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    for (byte[] section : sections) result.write(section);
    return result.toByteArray();
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record Item(String name, byte[] bytes, String linkTarget, byte type) {
    private Item(String name, byte[] bytes, String linkTarget) {
      this(
          name,
          bytes,
          linkTarget,
          linkTarget == null ? TarConstants.LF_NORMAL : TarConstants.LF_SYMLINK);
    }

    private static Item directory(String name) {
      return new Item(name, new byte[0], null, TarConstants.LF_DIR);
    }

    private static Item characterDevice(String name) {
      return new Item(name, new byte[0], null, TarConstants.LF_CHR);
    }
  }
}
