package com.github.klboke.kkrepo.server;

import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.DELETED;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.BLOBS_REMAIN;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.NOT_FOUND;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.REPOSITORY_IN_USE;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.UPLOADS_IN_PROGRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class BlobStoreDeletionServiceTest {
  @Test
  void broadcastsOnlyAfterCommittedDeletion() {
    BlobStoreDao dao = mock(BlobStoreDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(dao.deleteEmptyById(7)).thenReturn(DELETED);
    BlobStoreDeletionService service = new BlobStoreDeletionService(dao, registry, transactionManager);

    service.deleteEmpty(7);

    var order = inOrder(transactionManager, dao, registry);
    order.verify(dao).deleteEmptyById(7);
    order.verify(transactionManager).commit(any());
    order.verify(registry).invalidate(7);
    order.verify(registry).refreshAllAndBroadcast();
  }

  @Test
  void postCommitCacheFailuresDoNotTurnSuccessfulDeleteIntoAnError() {
    BlobStoreDao dao = mock(BlobStoreDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(dao.deleteEmptyById(7)).thenReturn(DELETED);
    doThrow(new IllegalStateException("local cache unavailable")).when(registry).invalidate(7);
    doThrow(new IllegalStateException("watermark unavailable"))
        .when(registry).refreshAllAndBroadcast();

    new BlobStoreDeletionService(dao, registry, transactionManager).deleteEmpty(7);

    var order = inOrder(transactionManager, registry);
    order.verify(transactionManager).commit(any());
    order.verify(registry).invalidate(7);
    order.verify(registry).refreshAllAndBroadcast();
  }

  @Test
  void rejectedDeletionLeavesCatalogUnchanged() {
    BlobStoreDao dao = mock(BlobStoreDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    BlobStoreDeletionService service = new BlobStoreDeletionService(dao, registry, transactionManager);

    for (var entry : Map.<DeleteResult, HttpStatus>of(
        NOT_FOUND, HttpStatus.NOT_FOUND,
        REPOSITORY_IN_USE, HttpStatus.CONFLICT,
        BLOBS_REMAIN, HttpStatus.CONFLICT,
        UPLOADS_IN_PROGRESS, HttpStatus.CONFLICT).entrySet()) {
      when(dao.deleteEmptyById(7)).thenReturn(entry.getKey());
      ResponseStatusException error = assertThrows(ResponseStatusException.class,
          () -> service.deleteEmpty(7));
      assertEquals(entry.getValue(), error.getStatusCode());
    }

    when(dao.deleteEmptyById(7)).thenThrow(new DataIntegrityViolationException("foreign key"));
    ResponseStatusException racedReference = assertThrows(ResponseStatusException.class,
        () -> service.deleteEmpty(7));
    assertEquals(HttpStatus.CONFLICT, racedReference.getStatusCode());

    verifyNoInteractions(registry);
  }
}
