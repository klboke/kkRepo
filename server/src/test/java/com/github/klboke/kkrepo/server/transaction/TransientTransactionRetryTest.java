package com.github.klboke.kkrepo.server.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TransientTransactionRetryTest {
  @Test
  void retriesTransientDataAccessFailuresInFreshTransactions() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    TransientTransactionRetry retry = new TransientTransactionRetry(transactionManager, 3, 0);
    AtomicInteger attempts = new AtomicInteger();

    String value = retry.execute("test operation", () -> {
      if (attempts.incrementAndGet() < 3) {
        throw new CannotAcquireLockException("deadlock");
      }
      return "ok";
    });

    assertEquals("ok", value);
    assertEquals(3, attempts.get());
    assertEquals(3, transactionManager.begun);
    assertEquals(2, transactionManager.rolledBack);
    assertEquals(1, transactionManager.committed);
  }

  @Test
  void stopsAfterConfiguredAttempts() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    TransientTransactionRetry retry = new TransientTransactionRetry(transactionManager, 2, 0);

    assertThrows(CannotAcquireLockException.class,
        () -> retry.execute("test operation", () -> {
          throw new CannotAcquireLockException("deadlock");
        }));

    assertEquals(2, transactionManager.begun);
    assertEquals(2, transactionManager.rolledBack);
    assertEquals(0, transactionManager.committed);
  }

  @Test
  void doesNotRetryNonTransientFailures() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    TransientTransactionRetry retry = new TransientTransactionRetry(transactionManager, 3, 0);

    assertThrows(IllegalStateException.class,
        () -> retry.execute("test operation", () -> {
          throw new IllegalStateException("bad input");
        }));

    assertEquals(1, transactionManager.begun);
    assertEquals(1, transactionManager.rolledBack);
    assertEquals(0, transactionManager.committed);
  }

  @Test
  void retriesServiceOwnedTransactionsWithoutChangingIsolation() {
    RecordingTransactionManager manager = new RecordingTransactionManager();
    TransientTransactionRetry retry = new TransientTransactionRetry(manager, 3, 0);
    var serviceTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
    serviceTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    AtomicInteger attempts = new AtomicInteger();
    assertEquals("ok", retry.retryTransaction("directory-delete", () -> serviceTransaction.execute(status -> {
      if (attempts.incrementAndGet() == 1) throw new CannotAcquireLockException("deadlock");
      return "ok";
    })));
    assertEquals(2, manager.begun);
    assertEquals(1, manager.rolledBack);
    assertEquals(1, manager.committed);
    assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, manager.isolation);
  }

  @Test
  void letsTheOuterOwnerRetryAnAbortedTransaction() {
    RecordingTransactionManager manager = new RecordingTransactionManager();
    TransientTransactionRetry retry = new TransientTransactionRetry(manager, 3, 0);
    AtomicInteger attempts = new AtomicInteger();
    var failure = new CannotAcquireLockException("deadlock");
    org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertEquals(failure, assertThrows(CannotAcquireLockException.class,
          () -> retry.retryTransaction("joined-delete", () -> {
            attempts.incrementAndGet();
            throw failure;
          })));
      assertEquals(1, attempts.get());
      assertEquals(0, manager.begun);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }

  private static class RecordingTransactionManager implements PlatformTransactionManager {
    int begun;
    int isolation;
    int committed;
    int rolledBack;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
      begun++;
      isolation = definition.getIsolationLevel();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      committed++;
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
      rolledBack++;
    }
  }
}
