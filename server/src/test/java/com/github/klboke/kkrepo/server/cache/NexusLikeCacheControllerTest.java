package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NexusLikeCacheControllerTest {
  @Test
  void staleMatchesNexusInvalidatedTokenAndAgeRules() {
    NexusLikeCacheController controller = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    Instant now = Instant.parse("2026-06-02T00:00:00Z");
    NexusLikeCacheInfo fresh = controller.current(7L, NexusCacheType.CONTENT, now.minusSeconds(30));

    assertFalse(controller.isStale(7L, NexusCacheType.CONTENT, fresh, 1, now));
    assertTrue(controller.isStale(
        7L,
        NexusCacheType.CONTENT,
        new NexusLikeCacheInfo(now, NexusLikeCacheInfo.INVALIDATED, NexusCacheType.CONTENT),
        -1,
        now));
    assertTrue(controller.isStale(7L, NexusCacheType.CONTENT, fresh, 0, now));
    assertFalse(controller.isStale(
        7L,
        NexusCacheType.CONTENT,
        new NexusLikeCacheInfo(Instant.EPOCH, controller.currentToken(7L, NexusCacheType.CONTENT), NexusCacheType.CONTENT),
        -1,
        now));
  }

  @Test
  void tokenBumpMakesOldCacheInfoStale() {
    NexusLikeCacheController controller = new NexusLikeCacheController(new InMemoryVersionWatermark(), 60);
    NexusLikeCacheInfo before = controller.current(7L, NexusCacheType.CONTENT, Instant.now());

    controller.invalidate(7L, NexusCacheType.CONTENT);

    assertTrue(controller.isStale(7L, NexusCacheType.CONTENT, before, -1, Instant.now()));
  }

  @Test
  void strictInvalidationPropagatesWatermarkFailuresForDurableRetryCallers() {
    VersionWatermark watermark = mock(VersionWatermark.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(watermark).bump("repo:7:METADATA");
    NexusLikeCacheController controller = new NexusLikeCacheController(watermark, 60);

    assertThrows(
        IllegalStateException.class,
        () -> controller.invalidateOrThrow(7L, NexusCacheType.METADATA));
    assertDoesNotThrow(() -> controller.invalidate(7L, NexusCacheType.METADATA));
  }

  @Test
  void cacheInfoRoundTripsThroughAssetAttributes() {
    NexusLikeCacheInfo cacheInfo = new NexusLikeCacheInfo(
        Instant.parse("2026-06-02T00:00:00Z"),
        "42",
        NexusCacheType.METADATA);

    Map<String, Object> attributes = NexusLikeCacheInfo.applyToAttributes(Map.of("format", "maven"), cacheInfo);

    assertTrue(NexusLikeCacheInfo.fromAttributes(attributes).filter(cacheInfo::equals).isPresent());
  }
}
