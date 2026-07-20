package com.github.klboke.kkrepo.server.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class JdbcCatalogCacheBroadcasterTest {

  @Test
  void configuresFixedDelayWithPerReplicaInitialJitter() {
    JdbcCatalogCacheBroadcaster broadcaster =
        new JdbcCatalogCacheBroadcaster(new InMemoryVersionWatermark(), 750, 500, 100);
    ScheduledTaskRegistrar taskRegistrar = new ScheduledTaskRegistrar();

    broadcaster.configureTasks(taskRegistrar);

    assertEquals(1, taskRegistrar.getFixedDelayTaskList().size());
    IntervalTask task = taskRegistrar.getFixedDelayTaskList().getFirst();
    assertEquals(750, task.getIntervalDuration().toMillis());
    assertTrue(task.getInitialDelayDuration().toMillis() >= 500);
    assertTrue(task.getInitialDelayDuration().toMillis() <= 600);
  }

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
