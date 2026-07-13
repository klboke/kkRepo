package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerAuthTokenDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.DockerUploadDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadChunkRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.docker.DockerUploadSessionRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DockerUploadCleanupWorkerTest {
  @Test
  void cleanupDeletesTerminalUploadChunksBeforeDeletingSession() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerAuthTokenDao authTokenDao = mock(DockerAuthTokenDao.class);
    RepositoryRuntimeRegistry runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
    BlobStorage storage = mock(BlobStorage.class);
    RepositoryRuntime runtime = hostedRuntime();
    DockerUploadSessionRecord session = session(runtime, "COMPLETED");
    DockerUploadChunkRecord chunk = chunk();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    when(uploadDao.claimTerminalSessions(any(), anyString(), any(), eq(64)))
        .thenReturn(List.of(session));
    when(uploadDao.listChunks("upload-1")).thenReturn(List.of(chunk));
    when(runtimeRegistry.resolveById(runtime.id())).thenReturn(Optional.of(runtime));
    when(blobStorageRegistry.forBlobStoreId(runtime.blobStoreId())).thenReturn(storage);
    DockerUploadCleanupWorker worker = new DockerUploadCleanupWorker(
        uploadDao, authTokenDao, runtimeRegistry, blobStorageRegistry, transactions, true, 64, 300);

    worker.cleanup();

    verify(storage).delete(argThat(reference ->
        "test-bucket".equals(reference.bucket())
            && "docker/uploads/upload-1/chunk-0".equals(reference.objectKey())
            && chunk.sha256().equals(reference.sha256())
            && chunk.size() == reference.size()));
    verify(uploadDao).deleteSession("upload-1");
    assertEquals(3, transactions.committed);
  }

  @Test
  void cleanupKeepsSessionWhenRepositoryRuntimeIsTemporarilyUnavailable() {
    DockerUploadDao uploadDao = mock(DockerUploadDao.class);
    DockerAuthTokenDao authTokenDao = mock(DockerAuthTokenDao.class);
    RepositoryRuntimeRegistry runtimeRegistry = mock(RepositoryRuntimeRegistry.class);
    BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
    RepositoryRuntime runtime = hostedRuntime();
    DockerUploadSessionRecord session = session(runtime, "EXPIRED");
    when(uploadDao.claimTerminalSessions(any(), anyString(), any(), eq(64)))
        .thenReturn(List.of(session));
    when(uploadDao.listChunks("upload-1")).thenReturn(List.of(chunk()));
    when(runtimeRegistry.resolveById(runtime.id())).thenReturn(Optional.empty());
    DockerUploadCleanupWorker worker = new DockerUploadCleanupWorker(
        uploadDao, authTokenDao, runtimeRegistry, blobStorageRegistry,
        new RecordingTransactionManager(), true, 64, 300);

    worker.cleanup();

    verify(blobStorageRegistry, never()).forBlobStoreId(any(long.class));
    verify(uploadDao, never()).deleteSession("upload-1");
  }

  private static DockerUploadSessionRecord session(RepositoryRuntime runtime, String status) {
    Instant now = Instant.now();
    return new DockerUploadSessionRecord(
        "upload-1",
        runtime.id(),
        "library/alpine",
        PersistenceHashes.sha256("library/alpine"),
        status,
        3,
        null,
        null,
        "user",
        "127.0.0.1",
        now.minusSeconds(60),
        null,
        null,
        Map.of(),
        now.minusSeconds(120),
        now.minusSeconds(60));
  }

  private static DockerUploadChunkRecord chunk() {
    return new DockerUploadChunkRecord(
        1L,
        "upload-1",
        0,
        0,
        2,
        "blob://test-bucket/docker/uploads/upload-1/chunk-0",
        "docker/uploads/upload-1/chunk-0",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        3,
        Instant.now());
  }

  private static RepositoryRuntime hostedRuntime() {
    return new RepositoryRuntime(
        10L,
        "docker-hosted",
        RepositoryFormat.DOCKER,
        RepositoryType.HOSTED,
        "docker-hosted",
        true,
        1L,
        "ALLOW",
        null,
        null,
        true,
        null,
        null,
        null,
        true,
        null,
        false,
        null,
        null,
        List.of());
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    int committed;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
      committed++;
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
    }
  }
}
