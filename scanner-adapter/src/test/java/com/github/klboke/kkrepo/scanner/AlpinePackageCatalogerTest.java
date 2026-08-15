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
import java.util.HexFormat;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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

  private static void assertCode(String code, Runnable invocation) {
    ScannerRequestException failure = assertThrows(ScannerRequestException.class, invocation::run);
    assertEquals(code, failure.code());
  }

  private static ResourceLimits limits(long expanded) {
    return new ResourceLimits(1024 * 1024, 100, expanded, expanded, 1, 10);
  }

  private static byte[] apk(List<Item> dataEntries) throws Exception {
    byte[] data = gzipTar(dataEntries);
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
    byte[] control = gzipTar(List.of(
        new Item(".PKGINFO", pkgInfo.getBytes(StandardCharsets.UTF_8), null)));
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    result.write(control);
    result.write(data);
    return result.toByteArray();
  }

  private static byte[] gzipTar(List<Item> entries) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(result);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      for (Item item : entries) {
        TarArchiveEntry entry;
        if (item.linkTarget() == null) {
          entry = new TarArchiveEntry(item.name());
          entry.setSize(item.bytes().length);
        } else {
          entry = new TarArchiveEntry(
              item.name(), org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
          entry.setLinkName(item.linkTarget());
          entry.setSize(0);
        }
        tar.putArchiveEntry(entry);
        if (item.linkTarget() == null) tar.write(item.bytes());
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
    return result.toByteArray();
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private record Item(String name, byte[] bytes, String linkTarget) {
  }
}
