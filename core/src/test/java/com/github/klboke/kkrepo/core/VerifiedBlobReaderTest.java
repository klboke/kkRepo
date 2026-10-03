package com.github.klboke.kkrepo.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class VerifiedBlobReaderTest {
  @Test void streamsAcrossPartBoundaryAndComputesDigests() throws Exception {
    byte[] bytes = new byte[VerifiedBlobReader.PART_BYTES + 31];
    new java.util.Random(42).nextBytes(bytes);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    VerifiedBlobReader reader = reader(bytes, bytes.length, sha);
    assertEquals(VerifiedBlobReader.PART_BYTES, reader.nextPart().length);
    assertEquals(31, reader.nextPart().length);
    assertEquals(0, reader.nextPart().length);
    VerifiedBlobDigests result = reader.finish();
    assertEquals(sha, result.sha256());
    assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)), result.sha1());
    assertThrows(IllegalStateException.class, reader::finish);
  }
  @Test void rejectsShortLongAndIncorrectOidIncludingZeroBoundary() {
    var shortReader = reader(new byte[3], 4, "a".repeat(64));
    assertThrows(BlobIntegrityException.class, shortReader::nextPart);
    var longReader = reader(new byte[3], 2, "a".repeat(64));
    longReader.nextPart();
    assertThrows(BlobIntegrityException.class, longReader::finish);
    var wrong = reader(new byte[3], 3, "a".repeat(64));
    wrong.nextPart();
    assertThrows(BlobIntegrityException.class, wrong::finish);
    var empty = reader(new byte[0], 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertEquals(0, empty.nextPart().length);
    assertEquals(0, empty.finish().size());
    assertThrows(BlobIntegrityException.class, () -> reader(new byte[0], VerifiedBlobReader.MAX_BYTES + 1, "a".repeat(64)));
  }
  private VerifiedBlobReader reader(byte[] bytes, long size, String sha) {
    return new VerifiedBlobReader(new BlobReference("bucket", "attempt", sha, size), new ByteArrayInputStream(bytes));
  }
}
