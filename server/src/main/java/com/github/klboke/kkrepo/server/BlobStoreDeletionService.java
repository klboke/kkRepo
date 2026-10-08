package com.github.klboke.kkrepo.server;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao.DeleteResult;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BlobStoreDeletionService {
  private final BlobStoreDao blobStoreDao;
  private final BlobStorageRegistry registry;
  private final TransactionTemplate transactions;

  public BlobStoreDeletionService(
      BlobStoreDao blobStoreDao,
      BlobStorageRegistry registry,
      PlatformTransactionManager transactionManager) {
    this.blobStoreDao = blobStoreDao;
    this.registry = registry;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  public void deleteEmpty(long id) {
    DeleteResult result;
    try {
      result = transactions.execute(status -> blobStoreDao.deleteEmptyById(id));
    } catch (DataIntegrityViolationException conflict) {
      // Covers references added concurrently or by a newer schema version.
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Blob store is still referenced and cannot be deleted", conflict);
    }
    switch (result) {
      case DELETED -> registry.refreshAllAndBroadcast();
      case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "Blob store not found");
      case REPOSITORY_IN_USE -> throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Blob store is still used by a repository");
      case BLOBS_REMAIN -> throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Blob store still has registered blobs, including pending deletions");
      case UPLOADS_IN_PROGRESS -> throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Blob store still has upload sessions");
    }
  }
}
