package com.github.klboke.kkrepo.server.blob;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferedUploadTest {
  @TempDir Path staging;

  @Test
  void stagesInTheStorageDirectoryWithAllDigestsAndLeavesInputOwnershipToCaller() throws Exception {
    AtomicBoolean closed = new AtomicBoolean();
    InputStream input = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
      @Override public void close() { closed.set(true); }
    };
    BufferedUpload upload = BufferedUpload.create(
        input, storage(), "upload-", BufferedUpload.Checksums.WITH_SHA512);
    assertEquals(staging, upload.file().getParent());
    assertEquals("abc", Files.readString(upload.file()));
    assertEquals(3, upload.digests().size());
    assertEquals("900150983cd24fb0d6963f7d28e17f72", upload.digests().md5());
    assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", upload.digests().sha1());
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", upload.digests().sha256());
    assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f", upload.digests().sha512());
    assertFalse(closed.get());
    try (InputStream response = TempBlobFiles.openDeleteOnClose(upload.file())) {
      assertEquals("abc", new String(response.readAllBytes(), StandardCharsets.UTF_8));
    }
    assertFalse(Files.exists(upload.file()));
  }

  @Test
  void cleansPartialFilesForCheckedAndUncheckedReadFailures() throws Exception {
    for (boolean checked : new boolean[] {true, false}) {
      InputStream input = new InputStream() {
        private int reads;
        @Override public int read() throws IOException {
          if (reads++ == 0) return 'a';
          if (checked) throw new IOException("upstream interrupted");
          throw new IllegalStateException("upstream interrupted");
        }
      };
      Class<? extends Exception> expected = checked ? IOException.class : IllegalStateException.class;
      assertThrows(expected,
          () -> BufferedUpload.create(input, storage(), "broken-", BufferedUpload.Checksums.STANDARD));
      try (var files = Files.list(staging)) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void inspectingExistingFilesDoesNotCopyDeleteOrCalculateOptionalSha512() throws Exception {
    Path file = staging.resolve("existing");
    Files.writeString(file, "abc");
    var digests = BufferedUpload.inspect(file, BufferedUpload.Checksums.STANDARD);
    assertNull(digests.sha512());
    assertEquals(3, digests.size());
    assertEquals("900150983cd24fb0d6963f7d28e17f72", digests.md5());
    assertEquals("abc", Files.readString(file));
    try (var files = Files.list(staging)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void continuesAfterEmptyReadsAndUsesABoundedBuffer() throws Exception {
    byte[] content = new byte[TempBlobFiles.responseBufferSize() + 13];
    InputStream input = new ByteArrayInputStream(content) {
      private boolean first = true;
      @Override public synchronized int read(byte[] buffer, int offset, int length) {
        assertTrue(length <= TempBlobFiles.responseBufferSize());
        if (first) { first = false; return 0; }
        return super.read(buffer, offset, length);
      }
    };
    BufferedUpload upload = BufferedUpload.create(
        input, storage(), "bounded-", BufferedUpload.Checksums.STANDARD);
    assertEquals(content.length, upload.digests().size());
    assertArrayEquals(content, Files.readAllBytes(upload.file()));
  }

  private BlobStorage storage() {
    BlobStorage storage = mock(BlobStorage.class);
    when(storage.stagingDirectory()).thenReturn(Optional.of(staging));
    return storage;
  }
}
