package com.github.klboke.kkrepo.persistence.jdbc.internal.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HashColumnsTest {
  @Test
  void producesStableSeparatedSha256Hashes() {
    byte[] hash = PersistenceHashes.sha256("a", "b");

    assertArrayEquals(
        HexFormat.of().parseHex("8fb20ef63ced4145fc2e983ffe597d1dcff39154c3bf21f0fa9dde6a0c50fdc9"),
        hash);
    assertFalse(Arrays.equals(hash, PersistenceHashes.sha256("ab")));
  }

  @Test
  void normalizesNullableCoordinatePartsAndDelegatesAllHashColumns() {
    assertArrayEquals(
        PersistenceHashes.componentCoordinateHash(null, "name", null),
        PersistenceHashes.componentCoordinateHash("", "name", ""));
    assertArrayEquals(
        PersistenceHashes.componentCoordinateHash("org.example", "demo", "1.0"),
        HashColumns.componentCoordinateHash("org.example", "demo", "1.0"));
    assertArrayEquals(PersistenceHashes.pathHash("a/b"), HashColumns.pathHash("a/b"));
    assertArrayEquals(PersistenceHashes.blobRefHash("bucket:key"), HashColumns.blobRefHash("bucket:key"));
    assertArrayEquals(PersistenceHashes.objectKeyHash("objects/key"), HashColumns.objectKeyHash("objects/key"));
    assertArrayEquals(PersistenceHashes.sha256("one", "two"), HashColumns.sha256("one", "two"));
  }
}
