package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;

import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmArchiveUploadTest {
  private static final String MANIFEST = """
      {
        // Generated test manifest
        name: '@scope/demo',
        version: '1.0.0',
        main: 'Index.ets',
        description: 'line\\
      continuation',
        customHex: 0xff,
        dependencies: {},
      }
      """;
  @TempDir Path temporary;

  @Test
  void readsJson5AndComputesIntegrityWithoutChangingTheArchive() throws Exception {
    byte[] archive = har(MANIFEST);
    Path staged;
    try (var upload = parser().parse(input(archive), null, "beta")) {
      assertEquals("@scope/demo", upload.metadata().packageId().id());
      assertEquals("1.0.0", upload.metadata().version());
      var metadata = upload.metadata().metadata();
      assertEquals("1.0.0", metadata.path("dist-tags").path("beta").textValue());
      var manifest = metadata.path("versions").path("1.0.0");
      assertEquals(255, manifest.path("customHex").intValue());
      assertEquals("linecontinuation", manifest.path("description").textValue());
      assertEquals("6.0.1", manifest.path("_ohpmVersion").textValue());
      assertTrue(upload.metadata().harIntegrity().matches(input(archive)));
      assertArrayEquals(archive, Files.readAllBytes(upload.har().file()));
      staged = upload.har().file();
    }
    assertFalse(Files.exists(staged));
    assertEmpty();
  }

  @Test
  void requiresMatchingHspTypeAndBindsBothFiles() throws Exception {
    String manifest = MANIFEST.replace("main: 'Index.ets',",
        "types: 'Index.d.ets', packageType: 'InterfaceHar',");
    byte[] har = har(manifest);
    byte[] hsp = "test HSP content".getBytes(StandardCharsets.UTF_8);
    try (var upload = parser().parse(input(har), input(hsp), null)) {
      assertTrue(upload.metadata().isHsp());
      assertTrue(upload.metadata().hspIntegrity().matches(input(hsp)));
      assertEquals("bundle_app", upload.metadata().metadata().path("versions").path("1.0.0")
          .path("hspType").textValue());
    }
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser().parse(input(har), null, null)).status());
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser().parse(input(har(MANIFEST)), input(hsp), null)).status());
    assertEquals(400, assertThrows(OhpmException.class, () -> parser().parse(
        input(har(manifest.replace("types:", "hspType: 'unsupported', types:"))), input(hsp), null)).status());
    assertEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}", "[]", "{name:'a',name:'b'}", "{name:'a'} garbage", "{name:'a',} garbage",
      "{x:NaN}", "{x:Infinity}", "{x:0b11}", "{x:undefined}"
  })
  void rejectsInvalidManifestOrMissingCoordinates(String text) throws Exception {
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser().parse(input(har(text)), null, null)).status());
    assertEmpty();
  }

  @Test
  void rejectsDuplicateManifestUnsafePathsAndMissingManifest() throws Exception {
    for (String name : new String[] {"./package/oh-package.json5", "../escape", "/absolute", "package/../bad"}) {
      Map<String, byte[]> entries = new LinkedHashMap<>();
      entries.put("package/oh-package.json5", MANIFEST.getBytes(StandardCharsets.UTF_8));
      entries.put(name, MANIFEST.getBytes(StandardCharsets.UTF_8));
      assertEquals(400, assertThrows(OhpmException.class,
          () -> parser().parse(input(tar(entries)), null, null)).status(), name);
    }
    assertEquals(400, assertThrows(OhpmException.class, () -> parser().parse(
        input(tar(Map.of("package/readme.txt", new byte[] {1}))), null, null)).status());
    assertEmpty();
  }

  @Test
  void rejectsDeepManifestBeforeTheJson5ParserCanExhaustTheStack() {
    String text = "{value:" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}";
    assertEquals(400, assertThrows(OhpmException.class,
        () -> OhpmArchiveUpload.parseManifest(text.getBytes(StandardCharsets.UTF_8))).status());
  }

  @Test
  void limitsCompressedExpandedAndManifestSizesAndCleansStaging() throws Exception {
    var small = new OhpmArchiveUpload(1024, temporary);
    assertEquals(413, assertThrows(OhpmException.class,
        () -> small.parse(input(new byte[1025]), null, null)).status());
    byte[] expanded = tar(Map.of("package/large", new byte[2 * 1024 * 1024]));
    assertTrue(expanded.length < 16 * 1024);
    assertEquals(413, assertThrows(OhpmException.class,
        () -> new OhpmArchiveUpload(16 * 1024, temporary).parse(input(expanded), null, null)).status());
    assertEquals(413, assertThrows(OhpmException.class,
        () -> OhpmArchiveUpload.parseManifest(new byte[2 * 1024 * 1024 + 1])).status());
    assertEmpty();
  }

  @Test
  void rejectsCorruptAndTrailingArchives() throws Exception {
    byte[] archive = har(MANIFEST);
    archive[archive.length - 6] ^= 1;
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser().parse(input(archive), null, null)).status());
    var trailing = new ByteArrayOutputStream();
    trailing.write(har(MANIFEST));
    try (var gzip = new GzipCompressorOutputStream(trailing)) {
      gzip.write("trailing".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser().parse(input(trailing.toByteArray()), null, null)).status());
    assertEmpty();
  }

  @Test
  void readsOfficialHspBundlesThroughTheSharedStreamExtractor() throws Exception {
    String manifest = MANIFEST.replace("main: 'Index.ets',",
        "types: 'Index.d.ets', packageType: 'InterfaceHar',");
    byte[] har = har(manifest);
    byte[] hsp = new byte[] {1, 2, 3};
    byte[] bundle = tar(Map.of("interface.har", har, "example.hsp", hsp));
    try (var upload = parser().parseBundle(input(bundle), "beta")) {
      assertTrue(upload.metadata().isHsp());
      assertTrue(upload.metadata().harIntegrity().matches(input(har)));
      assertTrue(upload.metadata().hspIntegrity().matches(input(hsp)));
    }
    assertEquals(400, assertThrows(OhpmException.class,
        () -> parser().parseBundle(input(tar(Map.of("interface.har", har))), null)).status());
    assertEquals(400, assertThrows(OhpmException.class, () -> parser().parseBundle(input(tar(Map.of(
        "interface.har", har, "example.hsp", hsp, "extra.txt", new byte[] {1}))), null)).status());
    assertEmpty();
  }

  @Test
  void rejectsLinksAndSparseMetadataBeforeParsingSparseMaps() throws Exception {
    var symbolic = new TarArchiveEntry("package/link", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
    symbolic.setLinkName("../outside");
    var sparse = new TarArchiveEntry("package/sparse");
    sparse.addPaxHeader("GNU.sparse.map", "invalid sparse map");
    for (var entry : new TarArchiveEntry[] {symbolic, sparse}) {
      var bytes = new ByteArrayOutputStream();
      try (var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
      }
      assertEquals(400, assertThrows(OhpmException.class,
          () -> parser().parse(input(bytes.toByteArray()), null, null)).status());
      assertEmpty();
    }
  }

  @Test
  void cleansTheHarWhenTheSecondInputIsInterrupted() throws Exception {
    InputStream broken = new InputStream() {
      @Override public int read() throws IOException { throw new IOException("interrupted"); }
    };
    assertThrows(IOException.class, () -> parser().parse(input(har(MANIFEST)), broken, null));
    assertEmpty();
  }

  private OhpmArchiveUpload parser() {
    return new OhpmArchiveUpload(8 * 1024 * 1024, temporary);
  }

  private void assertEmpty() throws IOException {
    try (var files = Files.list(temporary)) {
      assertEquals(0, files.count());
    }
  }

  private static ByteArrayInputStream input(byte[] content) {
    return new ByteArrayInputStream(content);
  }

  static byte[] har(String manifest) throws IOException {
    return tar(Map.of("package/oh-package.json5", manifest.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] tar(Map<String, byte[]> entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      for (var entry : entries.entrySet()) {
        var header = new TarArchiveEntry(entry.getKey(), true);
        header.setSize(entry.getValue().length);
        tar.putArchiveEntry(header);
        tar.write(entry.getValue());
        tar.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }
}
