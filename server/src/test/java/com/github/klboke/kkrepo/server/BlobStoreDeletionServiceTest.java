package com.github.klboke.kkrepo.server;

import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.DELETED;
import static com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult.REPOSITORY_IN_USE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import org.junit.jupiter.api.Test;
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
    order.verify(registry).refreshAllAndBroadcast();
  }

  @Test
  void rejectedDeletionLeavesCatalogUnchanged() {
    BlobStoreDao dao = mock(BlobStoreDao.class);
    BlobStorageRegistry registry = mock(BlobStorageRegistry.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    when(dao.deleteEmptyById(7)).thenReturn(REPOSITORY_IN_USE);
    BlobStoreDeletionService service = new BlobStoreDeletionService(dao, registry, transactionManager);

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.deleteEmpty(7));

    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    verifyNoInteractions(registry);
  }
}
