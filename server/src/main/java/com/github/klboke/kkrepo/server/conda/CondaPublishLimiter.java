package com.github.klboke.kkrepo.server.conda;

import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Holds capacity across inspection, blob staging, and transactional publication. */
@Component
final class CondaPublishLimiter {
  private final Semaphore permits;
  private final long waitMillis;

  @Autowired
  CondaPublishLimiter(
      @Value("${kkrepo.conda.publish.max-concurrent-operations:4}") int concurrency,
      @Value("${kkrepo.conda.publish.permit-wait-ms:5000}") long waitMillis) {
    this.permits = new Semaphore(Math.max(1, concurrency), true);
    this.waitMillis = Math.max(1, waitMillis);
  }

  CondaPublishLimiter() {
    this(4, 5_000);
  }

  <T> T execute(Supplier<T> action) {
    boolean acquired = false;
    try {
      acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new MavenExceptions.WritePolicyDenied(
            "Conda publication capacity is busy; retry the request");
      }
      return action.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MavenExceptions.WritePolicyDenied(
          "Interrupted while waiting for Conda publication capacity");
    } finally {
      if (acquired) permits.release();
    }
  }
}
