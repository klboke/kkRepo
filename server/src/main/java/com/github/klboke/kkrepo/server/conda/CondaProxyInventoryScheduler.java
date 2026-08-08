package com.github.klboke.kkrepo.server.conda;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the optional proxy inventory projection after the client-facing bytes have been cached.
 *
 * <p>The projection is not protocol truth: raw upstream metadata and package blobs remain the
 * authoritative proxy cache. A lost node-local task is safe because a later package or group
 * request schedules it again, while the durable Conda lease collapses work across replicas.
 */
@Component
final class CondaProxyInventoryScheduler {
  private static final Logger log = LoggerFactory.getLogger(CondaProxyInventoryScheduler.class);
  private static final int MAX_CONCURRENCY = 4;

  private final ScheduledExecutorService executor;
  private final long delayMillis;
  private final Semaphore pendingSlots;
  private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

  @Autowired
  CondaProxyInventoryScheduler(
      @Value("${kkrepo.conda.proxy-inventory.concurrency:1}") int concurrency,
      @Value("${kkrepo.conda.proxy-inventory.delay-ms:2000}") long delayMillis,
      @Value("${kkrepo.conda.proxy-inventory.max-pending:128}") int maxPending) {
    this.executor = Executors.newScheduledThreadPool(
        Math.max(1, Math.min(MAX_CONCURRENCY, concurrency)),
        Thread.ofPlatform().name("conda-proxy-inventory-", 0).factory());
    this.delayMillis = Math.max(0, delayMillis);
    this.pendingSlots = new Semaphore(Math.max(1, maxPending));
  }

  void schedule(String key, Runnable work) {
    if (key == null || key.isBlank() || work == null || !pendingSlots.tryAcquire()) return;
    if (!scheduled.add(key)) {
      pendingSlots.release();
      return;
    }
    try {
      executor.schedule(() -> {
        try {
          work.run();
        } catch (RuntimeException error) {
          log.warn("Deferred Conda proxy inventory failed for {}; a later request will retry", key,
              error);
        } finally {
          scheduled.remove(key);
          pendingSlots.release();
        }
      }, delayMillis, TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      scheduled.remove(key);
      pendingSlots.release();
      throw rejected;
    }
  }

  int scheduledCount() {
    return scheduled.size();
  }

  @PreDestroy
  void close() {
    executor.shutdown();
  }
}
