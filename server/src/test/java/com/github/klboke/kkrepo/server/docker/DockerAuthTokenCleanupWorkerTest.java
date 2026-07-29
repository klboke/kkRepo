package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DockerAuthTokenCleanupWorkerTest {
  @Test
  void cleanupUsesIndependentBoundedTransactionsUntilTheBacklogIsDrained() {
    DockerAuthTokenDao tokens = mock(DockerAuthTokenDao.class);
    when(tokens.deleteExpired(any(), eq(32))).thenReturn(32, 32, 7);
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    DockerAuthTokenCleanupWorker worker =
        new DockerAuthTokenCleanupWorker(tokens, transactions, 32, 4096);

    worker.cleanup();

    verify(tokens, times(3)).deleteExpired(any(), eq(32));
    assertEquals(3, transactions.count());
  }

  @Test
  void cleanupStopsAtTheConfiguredPerRunWorkLimit() {
    DockerAuthTokenDao tokens = mock(DockerAuthTokenDao.class);
    when(tokens.deleteExpired(any(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
    DockerAuthTokenCleanupWorker worker =
        new DockerAuthTokenCleanupWorker(tokens, new RecordingTransactionManager(), 32, 70);

    worker.cleanup();

    verify(tokens, times(2)).deleteExpired(any(), eq(32));
    verify(tokens).deleteExpired(any(), eq(6));
  }

  @Test
  void cleanupContainsDatabaseFailuresForTheNextScheduledCycle() {
    DockerAuthTokenDao tokens = mock(DockerAuthTokenDao.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(tokens).deleteExpired(any(), eq(1));
    DockerAuthTokenCleanupWorker worker =
        new DockerAuthTokenCleanupWorker(tokens, new RecordingTransactionManager(), 0, 0);

    worker.cleanup();

    verify(tokens).deleteExpired(any(), eq(1));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final AtomicInteger transactionCount = new AtomicInteger();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      transactionCount.incrementAndGet();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }

    private int count() {
      return transactionCount.get();
    }
  }
}
