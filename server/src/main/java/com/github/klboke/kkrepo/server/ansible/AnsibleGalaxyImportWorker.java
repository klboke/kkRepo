package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Atomically claims a bounded batch and processes it without per-task database claim round trips. */
@Component
final class AnsibleGalaxyImportWorker {
  private static final Duration TASK_LEASE_DURATION = Duration.ofMinutes(5);
  private static final int MAX_CONCURRENCY = 64;

  private final AnsibleGalaxyRegistryDao registry;
  private final AnsibleGalaxyService service;
  private final int concurrency;
  private final Executor executor;
  private final ExecutorService ownedExecutor;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final String workerOwner;

  @Autowired
  AnsibleGalaxyImportWorker(
      AnsibleGalaxyRegistryDao registry,
      AnsibleGalaxyService service,
      @Value("${kkrepo.ansible.import-worker.concurrency:4}") int concurrency) {
    this(
        registry,
        service,
        concurrency,
        Executors.newFixedThreadPool(
            boundedConcurrency(concurrency),
            Thread.ofPlatform().name("ansible-import-worker-", 0).factory()),
        "ansible-import-worker-" + UUID.randomUUID());
  }

  AnsibleGalaxyImportWorker(
      AnsibleGalaxyRegistryDao registry, AnsibleGalaxyService service) {
    this(registry, service, 16, Runnable::run, "ansible-import-worker-test");
  }

  AnsibleGalaxyImportWorker(
      AnsibleGalaxyRegistryDao registry,
      AnsibleGalaxyService service,
      int concurrency,
      Executor executor,
      String workerOwner) {
    this.registry = registry;
    this.service = service;
    this.concurrency = boundedConcurrency(concurrency);
    this.executor = executor;
    this.ownedExecutor = executor instanceof ExecutorService serviceExecutor
        ? serviceExecutor : null;
    this.workerOwner = workerOwner;
  }

  @Scheduled(fixedDelayString = "${kkrepo.ansible.import-recovery-delay-ms:5000}")
  synchronized void recover() {
    int capacity = concurrency - inFlight.get();
    if (capacity <= 0) return;
    Instant now = Instant.now();
    for (AnsibleGalaxyRegistryDao.ImportTask task : registry.claimTasks(
        workerOwner, now.plus(TASK_LEASE_DURATION), now, capacity)) {
      inFlight.incrementAndGet();
      try {
        executor.execute(() -> {
          try {
            service.processClaimedTask(task);
          } finally {
            inFlight.decrementAndGet();
          }
        });
      } catch (RuntimeException rejected) {
        inFlight.decrementAndGet();
        throw rejected;
      }
    }
  }

  int inFlightCount() {
    return inFlight.get();
  }

  @PreDestroy
  void close() {
    if (ownedExecutor != null) ownedExecutor.shutdown();
  }

  private static int boundedConcurrency(int concurrency) {
    return Math.max(1, Math.min(MAX_CONCURRENCY, concurrency));
  }
}
