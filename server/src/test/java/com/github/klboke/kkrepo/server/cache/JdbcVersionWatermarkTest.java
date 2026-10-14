package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcVersionWatermarkTest {
  @Test
  void durableReadsBypassTheNodeLocalVersionCache() {
    CacheVersionDao dao = mock(CacheVersionDao.class);
    when(dao.current("repo:7:CONTENT")).thenReturn(1L, 2L);
    JdbcVersionWatermark watermark = new JdbcVersionWatermark(dao, 60);

    assertEquals(1L, watermark.current("repo:7:CONTENT"));
    assertEquals(1L, watermark.current("repo:7:CONTENT"));
    assertEquals(2L, watermark.currentDurable("repo:7:CONTENT"));
    assertEquals(1L, watermark.current("repo:7:CONTENT"));

    verify(dao, times(2)).current("repo:7:CONTENT");
  }

  @Test
  void transactionReadsAndBumpsDoNotPublishUncommittedGenerationsToOtherThreads() throws Exception {
    CacheVersionDao dao = mock(CacheVersionDao.class);
    when(dao.bump("repo")).thenReturn(2L);
    when(dao.current("repo")).thenAnswer(call ->
        TransactionSynchronizationManager.isActualTransactionActive() ? 2L : 1L);
    when(dao.selectAll()).thenReturn(Map.of("repo", 2L));
    JdbcVersionWatermark watermark = new JdbcVersionWatermark(dao, 60);
    assertEquals(1L, watermark.current("repo"));

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertEquals(2L, watermark.bump("repo"));
      assertEquals(2L, watermark.current("repo"));
      assertEquals(Map.of("repo", 2L), watermark.currentAll());
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        assertEquals(1L, executor.submit(() -> watermark.current("repo")).get());
      }
      TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
          sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertEquals(1L, watermark.current("repo"), "rollback must not leave a phantom generation");
  }
}
