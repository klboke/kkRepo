package com.github.klboke.kkrepo.server.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidAssetIdException;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.InvalidContinuationTokenException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class NexusAssetIdCodecTest {
  private final NexusAssetIdCodec codec = new NexusAssetIdCodec();

  @Test
  void assetIdRoundTripsWithoutNodeLocalState() {
    String encoded = codec.encodeAssetId("raw-hosted", 42L);

    assertEquals(
        new NexusAssetIdCodec.DecodedAssetId("raw-hosted", 42L),
        new NexusAssetIdCodec().decodeAssetId(encoded));
  }

  @Test
  void malformedAndNonCanonicalAssetIdsAreRejected() {
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId("not-an-id"));
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId(base64("repo:2a")));
    assertThrows(InvalidAssetIdException.class,
        () -> codec.decodeAssetId(base64("repo:ffffffffffffffffffffffffffffffff")));
    assertThrows(InvalidAssetIdException.class,
        () -> codec.decodeAssetId(codec.encodeAssetId("repo", 1L) + "="));
  }

  @Test
  void continuationRoundTripsAndRejectsAssetIds() {
    String encoded = codec.encodeContinuation(7L, 99L);

    assertEquals(
        new NexusAssetIdCodec.DecodedContinuation(7L, 99L),
        new NexusAssetIdCodec().decodeContinuation(encoded));
    assertThrows(InvalidContinuationTokenException.class,
        () -> codec.decodeContinuation(codec.encodeAssetId("repo", 99L)));
  }

  @Test
  void encodersRejectInvalidRepositoryAndNumericInputs() {
    assertThrows(IllegalArgumentException.class, () -> codec.encodeAssetId(null, 1L));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeAssetId("", 1L));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeAssetId("invalid/name", 1L));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeAssetId("repo", 0L));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeContinuation(0L, 1L));
    assertThrows(IllegalArgumentException.class, () -> codec.encodeContinuation(1L, 0L));
  }

  @Test
  void decodersRejectEmptyOversizedZeroAndOverflowingIdentifiers() {
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId(null));
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId(" "));
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId("x".repeat(513)));
    assertThrows(InvalidAssetIdException.class,
        () -> codec.decodeAssetId(base64("repo:00000000000000000000000000000000")));
    assertThrows(InvalidContinuationTokenException.class,
        () -> codec.decodeContinuation(base64("v1:0000000000000000:0000000000000001")));
    assertThrows(InvalidContinuationTokenException.class,
        () -> codec.decodeContinuation(base64("v1:8000000000000000:0000000000000001")));
  }

  @Test
  void publicExceptionTypesPreserveMessages() {
    assertEquals("asset", new InvalidAssetIdException("asset").getMessage());
    assertEquals("continuation",
        new InvalidContinuationTokenException("continuation").getMessage());
  }

  private static String base64(String value) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
