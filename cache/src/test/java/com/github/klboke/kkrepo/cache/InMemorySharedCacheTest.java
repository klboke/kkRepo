package com.github.klboke.kkrepo.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemorySharedCacheTest {
  private final MutableClock clock = new MutableClock();
  private final InMemorySharedCache cache = new InMemorySharedCache(new ObjectMapper(), 100, null, clock);

  @Test
  void stringEntriesExpireByPerEntryTtl() {
    cache.putString("ns", "short", "value", Duration.ofSeconds(5));
    cache.putString("ns", "forever", "kept", Duration.ZERO);

    assertEquals("value", cache.getString("ns", "short").orElseThrow());

    clock.advance(Duration.ofSeconds(5));

    assertFalse(cache.getString("ns", "short").isPresent());
    assertEquals("kept", cache.getString("ns", "forever").orElseThrow());
  }

  @Test
  void getAndDeleteConsumesValueOnce() {
    cache.putString("ticket", "token", "alice", Duration.ofMinutes(1));

    assertEquals("alice", cache.getAndDeleteString("ticket", "token").orElseThrow());
    assertFalse(cache.getAndDeleteString("ticket", "token").isPresent());
  }

  @Test
  void incrementKeepsInitialTtlUntilExpiry() {
    assertEquals(1, cache.increment("rate", "ip", Duration.ofSeconds(10)));

    clock.advance(Duration.ofSeconds(5));
    assertEquals(2, cache.increment("rate", "ip", Duration.ofSeconds(10)));

    clock.advance(Duration.ofSeconds(5));
    assertEquals(1, cache.increment("rate", "ip", Duration.ofSeconds(10)));
  }

  @Test
  void evictByPrefixOnlyClearsMatchingNamespaceKeys() {
    cache.putString("ns", "repo/one", "1", Duration.ZERO);
    cache.putString("ns", "repo/two", "2", Duration.ZERO);
    cache.putString("ns", "other", "3", Duration.ZERO);
    cache.putString("other", "repo/one", "4", Duration.ZERO);

    cache.evictByPrefix("ns", "repo/");

    assertFalse(cache.getString("ns", "repo/one").isPresent());
    assertFalse(cache.getString("ns", "repo/two").isPresent());
    assertEquals("3", cache.getString("ns", "other").orElseThrow());
    assertEquals("4", cache.getString("other", "repo/one").orElseThrow());
  }

  @Test
  void evictByPrefixRecordsTheNumberOfDeletedKeys() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    InMemorySharedCache meteredCache =
        new InMemorySharedCache(new ObjectMapper(), 100, meterRegistry, clock);
    meteredCache.putString("ns", "repo/one", "1", Duration.ZERO);
    meteredCache.putString("ns", "repo/two", "2", Duration.ZERO);
    meteredCache.putString("ns", "other", "3", Duration.ZERO);

    meteredCache.evictByPrefix("ns", "repo/");

    assertEquals(2.0, meterRegistry.get("kkrepo_cache_scan_deleted_keys_total")
        .tag("namespace", "ns")
        .counter()
        .count());
  }

  @Test
  void jsonValuesRoundTrip() {
    cache.putJson("json", "map", Map.of("name", "demo", "count", 2), Duration.ZERO);

    Map<String, Object> value = cache.getJson("json", "map", new TypeReference<Map<String, Object>>() {})
        .orElseThrow();

    assertEquals("demo", value.get("name"));
    assertEquals(2, value.get("count"));
  }

  @Test
  void incrementRejectsNonCounterValues() {
    cache.putString("rate", "ip", "not-a-number", Duration.ZERO);

    assertThrows(IllegalStateException.class, () -> cache.increment("rate", "ip", Duration.ofSeconds(1)));
  }

  private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-06-05T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }
  }
}
