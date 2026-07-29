package com.github.klboke.kkrepo.server.docker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DockerAuthTokenCleanupWorkerTest {
  @Test
  void cleanupUsesAnIndependentBoundedTransaction() {
    DockerAuthTokenDao tokens = mock(DockerAuthTokenDao.class);
    DockerAuthTokenCleanupWorker worker =
        new DockerAuthTokenCleanupWorker(tokens, new RecordingTransactionManager(), 32);

    worker.cleanup();

    verify(tokens).deleteExpired(any(), eq(32));
  }

  @Test
  void cleanupContainsDatabaseFailuresForTheNextScheduledCycle() {
    DockerAuthTokenDao tokens = mock(DockerAuthTokenDao.class);
    doThrow(new IllegalStateException("database unavailable"))
        .when(tokens).deleteExpired(any(), eq(1));
    DockerAuthTokenCleanupWorker worker =
        new DockerAuthTokenCleanupWorker(tokens, new RecordingTransactionManager(), 0);

    worker.cleanup();

    verify(tokens).deleteExpired(any(), eq(1));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
        throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}
