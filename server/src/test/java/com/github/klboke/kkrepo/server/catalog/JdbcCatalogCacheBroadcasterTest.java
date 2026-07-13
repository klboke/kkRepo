package com.github.klboke.kkrepo.server.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JdbcCatalogCacheBroadcasterTest {
  @Test
  void subscribeStartsAtCurrentVersionAndPublishSuppressesLocalPollReload() {
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    watermark.bump("catalog:security");
    JdbcCatalogCacheBroadcaster broadcaster = new JdbcCatalogCacheBroadcaster(watermark);
    AtomicInteger refreshes = new AtomicInteger();

    broadcaster.subscribe("security", refreshes::incrementAndGet);
    broadcaster.poll();
    broadcaster.publishRefresh("security");
    broadcaster.poll();

    assertEquals(0, refreshes.get());
  }

  @Test
  void externalVersionBumpTriggersListenerOnce() {
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    JdbcCatalogCacheBroadcaster broadcaster = new JdbcCatalogCacheBroadcaster(watermark);
    AtomicInteger refreshes = new AtomicInteger();
    broadcaster.subscribe("blob-store", refreshes::incrementAndGet);

    watermark.bump("catalog:blob-store");
    broadcaster.poll();
    broadcaster.poll();

    assertEquals(1, refreshes.get());
  }

  @Test
  void failedRefreshDoesNotAdvanceLastSeen() {
    InMemoryVersionWatermark watermark = new InMemoryVersionWatermark();
    JdbcCatalogCacheBroadcaster broadcaster = new JdbcCatalogCacheBroadcaster(watermark);
    AtomicInteger attempts = new AtomicInteger();
    broadcaster.subscribe("security", () -> {
      if (attempts.incrementAndGet() == 1) {
        throw new IllegalStateException("temporary");
      }
    });

    watermark.bump("catalog:security");
    broadcaster.poll();
    broadcaster.poll();

    assertEquals(2, attempts.get());
  }

  @Test
  void blankCatalogNameIsRejected() {
    JdbcCatalogCacheBroadcaster broadcaster = new JdbcCatalogCacheBroadcaster(new InMemoryVersionWatermark());

    assertThrows(IllegalArgumentException.class, () -> broadcaster.publishRefresh(" "));
  }
}
