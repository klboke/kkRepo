package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class CacheInvalidationTest {
  @Test
  void cascadesDrainBeforeSortedDeduplicatedWatermarksAndLocalEvictions() throws Exception {
    List<String> events = new ArrayList<>();
    var tx = new TransactionTemplate(manager(events));
    tx.executeWithoutResult(status -> {
      CacheInvalidation.watermarkBeforeCommit("z", () -> events.add("z"));
      CacheInvalidation.beforeCommit("group", () -> {
        events.add("group");
        CacheInvalidation.beforeCommit("ancestor", () -> events.add("ancestor"));
        CacheInvalidation.watermarkBeforeCommit("a", () -> events.add("a"));
        CacheInvalidation.watermarkBeforeCommit("z", () -> events.add("duplicate"));
        CacheInvalidation.afterCommit("local", () -> events.add("evict"));
      });
      CacheInvalidation.beforeCommit("group", () -> events.add("duplicate"));
      assertTrue(events.isEmpty());
    });
    assertEquals(List.of("group", "ancestor", "a", "z", "commit", "evict"), events);
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  @Test
  void failedWatermarkRollsBackAndRetriesBusinessWriteBeforeEvicting() throws Exception {
    List<String> events = new ArrayList<>();
    var retry = new TransientTransactionRetry(manager(events), 3, 0);
    var attempts = new AtomicInteger();
    retry.execute("cache invalidation", () -> {
      int attempt = attempts.incrementAndGet();
      events.add("write-" + attempt);
      CacheInvalidation.watermarkBeforeCommit("repo", () -> {
        if (attempt == 1) throw new CannotAcquireLockException("busy version row");
        events.add("bump");
      });
      CacheInvalidation.afterCommit("local", () -> events.add("evict"));
      return null;
    });
    assertEquals(List.of("write-1", "rollback", "write-2", "bump", "commit", "evict"), events);
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  @Test
  void independentTransactionDoesNotConsumeSuspendedOuterInvalidations() throws Exception {
    List<String> events = new ArrayList<>();
    var manager = manager(events);
    var outer = new TransactionTemplate(manager);
    var inner = new TransactionTemplate(manager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    outer.executeWithoutResult(status -> {
      CacheInvalidation.watermarkBeforeCommit("same-repo", () -> events.add("outer"));
      CacheInvalidation.afterCommit("local", () -> events.add("outer-evict"));
      inner.executeWithoutResult(innerStatus -> {
        CacheInvalidation.watermarkBeforeCommit("same-repo", () -> events.add("inner"));
        CacheInvalidation.afterCommit("local", () -> events.add("inner-evict"));
      });
      status.setRollbackOnly();
    });
    assertEquals(List.of("inner", "commit", "inner-evict", "rollback"), events);
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  @Test
  void localEvictionFailureCannotRetryAnAlreadyCommittedWrite() throws Exception {
    List<String> events = new ArrayList<>();
    var retry = new TransientTransactionRetry(manager(events), 3, 0);
    var attempts = new AtomicInteger();
    retry.execute("local cache", () -> {
      attempts.incrementAndGet();
      CacheInvalidation.afterCommit("broken", () -> {
        throw new CannotAcquireLockException("local cache failure after database commit");
      });
      CacheInvalidation.afterCommit("other", () -> events.add("other-evict"));
      return null;
    });
    assertEquals(1, attempts.get());
    assertEquals(List.of("commit", "other-evict"), events);
  }

  @Test
  void mutationsOutsideTransactionRunImmediatelyAndPropagateFailure() {
    List<String> events = new ArrayList<>();
    CacheInvalidation.beforeCommit("group", () -> events.add("group"));
    CacheInvalidation.watermarkBeforeCommit("repo", () -> events.add("bump"));
    CacheInvalidation.afterCommit("local", () -> events.add("evict"));
    assertEquals(List.of("group", "bump", "evict"), events);
    assertThrows(CannotAcquireLockException.class, () ->
        CacheInvalidation.watermarkBeforeCommit("repo", () -> {
          throw new CannotAcquireLockException("busy");
        }));
  }

  @Test
  void durableWorkCannotBeAppendedAfterTheCommitPhase() throws Exception {
    var tx = new TransactionTemplate(manager(new ArrayList<>()));
    tx.executeWithoutResult(status -> CacheInvalidation.afterCommit("local", () -> {
      assertThrows(IllegalStateException.class,
          () -> CacheInvalidation.beforeCommit("late", () -> {}));
      assertThrows(IllegalStateException.class,
          () -> CacheInvalidation.watermarkBeforeCommit("late", () -> {}));
    }));
  }

  private static DataSourceTransactionManager manager(List<String> events) throws Exception {
    DataSource source = mock(DataSource.class);
    when(source.getConnection()).thenAnswer(call -> {
      Connection connection = mock(Connection.class);
      when(connection.getAutoCommit()).thenReturn(true);
      doAnswer(ignored -> { events.add("commit"); return null; }).when(connection).commit();
      doAnswer(ignored -> { events.add("rollback"); return null; }).when(connection).rollback();
      return connection;
    });
    return new DataSourceTransactionManager(source);
  }
}
