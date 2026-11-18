package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmIntegrity;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmPackageId;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OhpmStreamParserTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final OhpmPackageId PACKAGE = OhpmPackageId.parse("@scope/fixture");
  private static final byte[] HAR = new byte[] {1, 2, 3, 4};
  private static final byte[] HSP = new byte[] {5, 6, 7, 8};
  @TempDir Path directory;

  @Test
  void stagesHarAndHspAndPreservesOnlyPublicationMetadata() throws Exception {
    try (var upload = parse(metadata(false), HAR)) {
      assertArrayEquals(HAR, Files.readAllBytes(upload.har().file()));
      assertNull(upload.hsp());
      assertFalse(upload.metadata().metadata().has("_attachments"));
    }
    try (var upload = parse(metadata(true), archive(
        new Entry("./", new byte[0], '5'),
        file("bundle/interface.har", HAR), file("bundle/binary.hsp", HSP)))) {
      assertArrayEquals(HAR, Files.readAllBytes(upload.har().file()));
      assertArrayEquals(HSP, Files.readAllBytes(upload.hsp().file()));
    }
    assertEmpty();
  }

  @Test
  void fieldsMayArriveInEitherOrderAndClientFilenamesAreNotFilesystemPaths() throws Exception {
    Part metadata = new MemoryPart("metadata", JSON.writeValueAsBytes(metadata(false)));
    Part file = new MemoryPart("pkg_stream", "../../outside.har", HAR);
    try (var upload = parser(1024).parse(PACKAGE, List.of(file, metadata))) {
      assertEquals(directory, upload.har().file().getParent());
      assertArrayEquals(HAR, Files.readAllBytes(upload.har().file()));
    }
    assertEmpty();
  }

  @Test
  void rejectsDuplicateMissingUnknownAndEncodedParts() throws Exception {
    Part metadata = new MemoryPart("metadata", JSON.writeValueAsBytes(metadata(false)));
    Part file = new MemoryPart("pkg_stream", "package.har", HAR);
    MemoryPart encoded = new MemoryPart("pkg_stream", "package.har", HAR);
    encoded.getHeaders().add("Content-Transfer-Encoding", "base64");
    for (List<Part> parts : List.of(List.of(metadata), List.of(metadata, metadata), List.of(file, file),
        List.of(metadata, file, file), List.of(metadata, new MemoryPart("other", HAR)), List.of(metadata, encoded))) {
      assertEquals(400, assertThrows(OhpmException.class, () -> parser(1024).parse(PACKAGE, parts)).status());
      assertEmpty();
    }
  }

  @Test
  void rejectsAttachmentJsonTrailingMetadataAndInvalidIdentity() throws Exception {
    ObjectNode metadata = metadata(false);
    metadata.putObject("_attachments");
    reject(metadata, HAR, 400);
    metadata.remove("_attachments");
    metadata.put("name", "another");
    reject(metadata, HAR, 400);
    assertEquals(400, assertThrows(OhpmException.class, () -> parser(1024).parse(PACKAGE,
        List.of(new MemoryPart("metadata", (JSON.writeValueAsString(metadata(false)) + "{}").getBytes()),
            new MemoryPart("pkg_stream", "package.har", HAR)))).status());
    assertEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"../escape.har", "/escape.har", "C:/escape.har", "dir\\escape.har", "dir/../escape.har"})
  void rejectsUnsafeArchivePaths(String name) throws Exception {
    reject(metadata(true), archive(file(name, HAR), file("binary.hsp", HSP)), 400);
  }

  @Test
  void rejectsLinksDuplicatesUnexpectedAndMissingFiles() throws Exception {
    for (Entry[] entries : new Entry[][] {
        {new Entry("interface.har", new byte[0], '2'), file("binary.hsp", HSP)},
        {new Entry("interface.har", new byte[0], '1'), file("binary.hsp", HSP)},
        {file("interface.har", HAR), file("another.har", HAR), file("binary.hsp", HSP)},
        {file("interface.har", HAR), file("extra.txt", HSP)},
        {file("interface.har", HAR)}}) {
      reject(metadata(true), archive(entries), 400);
    }
  }

  @Test
  void rejectsBadIntegrityTruncationAndGzipChecksumAndCleansStaging() throws Exception {
    reject(metadata(false), HSP, 400);
    reject(metadata(true), archive(file("interface.har", HAR), file("binary.hsp", HAR)), 400);
    byte[] archive = archive(file("interface.har", HAR), file("binary.hsp", HSP));
    reject(metadata(true), Arrays.copyOf(archive, archive.length - 9), 400);
    archive[archive.length - 8] ^= 1;
    reject(metadata(true), archive, 400);
    reject(metadata(true), HAR, 400);
  }

  @Test
  void boundsExpandedFilesAndHeaderCountBeforeWriting() throws Exception {
    reject(metadata(true), archive(file("interface.har", new byte[1025]), file("binary.hsp", HSP)), 413);
    Entry[] many = new Entry[33];
    for (int index = 0; index < many.length; index++) many[index] = new Entry("dir" + index + "/", new byte[0], '5');
    reject(metadata(true), archive(many), 400);
    var hugePax = new TarArchiveEntry("PaxHeader", (byte) 'x');
    hugePax.setSize(1_000_000_000);
    byte[] header = new byte[512];
    hugePax.writeEntryHeader(header);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(bytes)) { gzip.write(header); }
    reject(metadata(true), bytes.toByteArray(), 413);
  }

  @Test
  void rejectsPaxSparseMetadataBeforeTheArchiveLibraryBuildsSparseMaps() throws Exception {
    byte[] pax = "22 GNU.sparse.major=1\n22 GNU.sparse.minor=0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] archive = archive(new Entry("PaxHeader", pax, 'x'), file("interface.har", HAR), file("binary.hsp", HSP));
    var error = assertThrows(OhpmException.class, () -> parse(metadata(true), archive));
    assertEquals(400, error.status());
    assertTrue(error.getMessage().contains("sparse"));
    assertEmpty();
  }

  @Test
  void acceptsPaxLongPathsWithoutUsingThemAsStagingPaths() throws Exception {
    try (var upload = parse(metadata(true), archive(
        file("folder/" + "a".repeat(200) + ".har", HAR), file("binary.hsp", HSP)))) {
      assertArrayEquals(HAR, Files.readAllBytes(upload.har().file()));
      assertEquals(directory, upload.har().file().getParent());
      assertArrayEquals(HSP, Files.readAllBytes(upload.hsp().file()));
    }
    assertEmpty();
  }

  @Test
  void rejectsTrailingArchiveAndOversizedStreamAndMetadata() throws Exception {
    byte[] first = archive(file("interface.har", HAR), file("binary.hsp", HSP));
    byte[] second = archive(file("extra.har", HAR));
    ByteArrayOutputStream combined = new ByteArrayOutputStream();
    combined.write(first);
    combined.write(second);
    reject(metadata(true), combined.toByteArray(), 400);
    reject(metadata(false), new byte[1025], 413);
    var part = mock(Part.class);
    when(part.getName()).thenReturn("metadata");
    when(part.getSize()).thenReturn(8L * 1024 * 1024 + 1);
    assertEquals(413, assertThrows(OhpmException.class, () -> parser(1024).parse(PACKAGE,
        List.of(part, new MemoryPart("pkg_stream", "package.har", HAR)))).status());
    verify(part, never()).getInputStream();
    verify(part).delete();
  }

  @Test
  void streamsLargeHarUsingBoundedReadsAndReclaimsServletPart() throws Exception {
    long size = 64L * 1024 * 1024;
    ObjectNode metadata = metadata(false);
    metadata.withObject("/versions/1.0.0/dist").put("integrity", OhpmIntegrity.compute(generated(size)).value());
    Part content = mock(Part.class);
    when(content.getName()).thenReturn("pkg_stream");
    when(content.getSize()).thenReturn(size);
    when(content.getInputStream()).thenAnswer(call -> generated(size));
    try (var upload = parser(size).parse(PACKAGE,
        List.of(new MemoryPart("metadata", JSON.writeValueAsBytes(metadata)), content))) {
      assertEquals(size, Files.size(upload.har().file()));
    }
    verify(content).delete();
    assertEmpty();
  }

  @Test
  void inputFailureRemovesPartialFileAndDeletesServletParts() throws Exception {
    Part content = mock(Part.class);
    when(content.getName()).thenReturn("pkg_stream");
    when(content.getSize()).thenReturn(64L);
    when(content.getInputStream()).thenReturn(new InputStream() {
      private boolean read;
      @Override public int read() throws IOException { throw new IOException("Interrupted"); }
      @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (read) throw new IOException("Interrupted");
        read = true;
        buffer[offset] = 1;
        return 1;
      }
    });
    assertThrows(IOException.class, () -> parser(1024).parse(PACKAGE,
        List.of(new MemoryPart("metadata", JSON.writeValueAsBytes(metadata(false))), content)));
    verify(content).delete();
    assertEmpty();
  }

  @Test
  void stagingFailureReleasesServletParts() throws Exception {
    Path notDirectory = Files.writeString(directory.resolve("not-a-directory"), "file");
    Part content = mock(Part.class);
    when(content.getName()).thenReturn("pkg_stream");
    when(content.getSize()).thenReturn((long) HAR.length);
    when(content.getInputStream()).thenReturn(new ByteArrayInputStream(HAR));
    assertThrows(IOException.class, () -> new OhpmStreamParser(1024, notDirectory).parse(PACKAGE,
        List.of(new MemoryPart("metadata", JSON.writeValueAsBytes(metadata(false))), content)));
    verify(content).delete();
  }

  private OhpmPublishParser.Upload parse(ObjectNode metadata, byte[] bytes) throws Exception {
    return parser(1024).parse(PACKAGE, List.of(
        new MemoryPart("metadata", JSON.writeValueAsBytes(metadata)), new MemoryPart("pkg_stream", "package.tgz", bytes)));
  }

  private void reject(ObjectNode metadata, byte[] bytes, int status) throws Exception {
    assertEquals(status, assertThrows(OhpmException.class, () -> parse(metadata, bytes)).status());
    assertEmpty();
  }

  private OhpmStreamParser parser(long limit) { return new OhpmStreamParser(limit, directory); }

  private void assertEmpty() throws IOException {
    try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
  }

  private static InputStream generated(long size) {
    return new InputStream() {
      private long remaining = size;
      @Override public int read() {
        if (remaining == 0) return -1;
        remaining--;
        return 42;
      }
      @Override public int read(byte[] bytes, int offset, int length) {
        assertTrue(length <= 64 * 1024, "Upload must use bounded read buffers");
        if (remaining == 0) return -1;
        int count = (int) Math.min(length, remaining);
        Arrays.fill(bytes, offset, offset + count, (byte) 42);
        remaining -= count;
        return count;
      }
    };
  }

  private static ObjectNode metadata(boolean hsp) throws IOException {
    ObjectNode root = JSON.createObjectNode().put("_id", PACKAGE.id()).put("name", PACKAGE.id());
    root.putObject("dist-tags").put("latest", "1.0.0");
    var version = root.putObject("versions").putObject("1.0.0")
        .put("name", PACKAGE.id()).put("version", "1.0.0").put("main", "Index.ets");
    var dist = version.putObject("dist").put("integrity", OhpmIntegrity.compute(new ByteArrayInputStream(HAR)).value());
    if (hsp) {
      root.put("packageType", "InterfaceHar").put("hspType", "bundle_app");
      version.put("types", "Index.d.ets");
      dist.put("integrity_hsp", OhpmIntegrity.compute(new ByteArrayInputStream(HSP)).value());
    }
    return root;
  }

  private static Entry file(String name, byte[] bytes) { return new Entry(name, bytes, '0'); }
  private record Entry(String name, byte[] bytes, char type) {}

  private static final class MemoryPart extends org.springframework.mock.web.MockPart {
    MemoryPart(String name, byte[] content) { super(name, content); }
    MemoryPart(String name, String filename, byte[] content) { super(name, filename, content); }
    @Override public void delete() {}
  }

  private static byte[] archive(Entry... entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (var value : entries) {
        var entry = new TarArchiveEntry(value.name(), (byte) value.type(), true);
        entry.setSize(value.bytes().length);
        if (value.type() == '1' || value.type() == '2') entry.setLinkName("outside");
        archive.putArchiveEntry(entry);
        archive.write(value.bytes());
        archive.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }
}
