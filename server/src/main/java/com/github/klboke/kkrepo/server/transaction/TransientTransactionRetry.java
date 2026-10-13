package com.github.klboke.kkrepo.server.transaction;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TransientTransactionRetry {
  private static final Logger log = LoggerFactory.getLogger(TransientTransactionRetry.class);

  private final TransactionTemplate transactionTemplate;
  private final int maxAttempts;
  private final long initialBackoffMs;

  public TransientTransactionRetry(
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.persistence.transaction-retry.max-attempts:3}") int maxAttempts,
      @Value("${kkrepo.persistence.transaction-retry.initial-backoff-ms:25}") long initialBackoffMs) {
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.maxAttempts = Math.max(1, maxAttempts);
    this.initialBackoffMs = Math.max(0, initialBackoffMs);
  }

  public <T> T execute(String operation, Supplier<T> callback) {
    return retry(operation, () -> transactionTemplate.execute(status -> callback.get()));
  }

  /** Retry a complete transaction owned by the callback, preserving its isolation and rollback rules. */
  public <T> T retryTransaction(String operation, Supplier<T> transaction) {
    // An outer transaction must be retried by its owner, never continued after a deadlock.
    if (TransactionSynchronizationManager.isActualTransactionActive()) return transaction.get();
    return retry(operation, transaction);
  }

  private <T> T retry(String operation, Supplier<T> transaction) {
    int attempt = 1;
    while (true) {
      try {
        return transaction.get();
      } catch (RuntimeException e) {
        if (!isTransient(e) || attempt >= maxAttempts) {
          throw e;
        }
        long base = initialBackoffMs * attempt;
        long delayMs = base + (base > 0 ? ThreadLocalRandom.current().nextLong(base) : 0);
        log.warn("{} failed with transient transaction error on attempt {}/{}; retrying after {}ms",
            operation, attempt, maxAttempts, delayMs, e);
        sleep(delayMs);
        attempt++;
      }
    }
  }

  public <T> T executeIfNoTransaction(String operation, Supplier<T> callback) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return callback.get();
    }
    return execute(operation, callback);
  }

  private static boolean isTransient(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof TransientDataAccessException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void sleep(long delayMs) {
    if (delayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
