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
    String encoded = codec.encodeAssetId("windows-artifacts", 42L);

    assertEquals(
        new NexusAssetIdCodec.DecodedAssetId(
            "windows-artifacts", "0000000000000000000000000000002a"),
        new NexusAssetIdCodec().decodeAssetId(encoded));
  }

  @Test
  void fullWidthNexusOpaqueIdIsPreservedWithoutLongConversion() {
    String opaqueId = "ffffffffffffffffffffffffffffffff";

    assertEquals(
        new NexusAssetIdCodec.DecodedAssetId("repo", opaqueId),
        codec.decodeAssetId(codec.encodeAssetId("repo", opaqueId)));
  }

  @Test
  void malformedAndNonCanonicalAssetIdsAreRejected() {
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId("not-an-id"));
    assertThrows(InvalidAssetIdException.class, () -> codec.decodeAssetId(base64("repo:2a")));
    assertThrows(InvalidAssetIdException.class,
        () -> codec.decodeAssetId(base64("repo:FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")));
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
  void componentContinuationRoundTripsWithoutNodeLocalState() {
    String fingerprint = "0123456789abcdef0123456789abcdef";
    String encoded = codec.encodeComponentContinuation(fingerprint, 123L);

    assertEquals(
        new NexusAssetIdCodec.DecodedComponentContinuation(fingerprint, 123L),
        new NexusAssetIdCodec().decodeComponentContinuation(encoded));
    assertThrows(InvalidContinuationTokenException.class,
        () -> codec.decodeComponentContinuation(codec.encodeContinuation(7L, 123L)));
  }

  private static String base64(String value) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
