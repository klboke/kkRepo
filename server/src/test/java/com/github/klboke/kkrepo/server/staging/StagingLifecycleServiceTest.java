package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class StagingLifecycleServiceTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final SecurityManagementService security = mock(SecurityManagementService.class);
  private final BlobReferenceDao blobReferences = mock(BlobReferenceDao.class);
  private final StagingService service = new StagingService(
      staging, mock(RepositoryDao.class), mock(RepositoryService.class), mock(AssetDao.class), blobReferences,
      mock(RepositoryRuntimeRegistry.class), mock(RawHostedService.class),
      mock(BlobStorageRegistry.class), mock(SecurityAuditDao.class), security, List.of(),
      transactionManager(), 100);
  private final PermissionSubject actor =
      new PermissionSubject("Local", "admin", Set.of(), null);

  @Test
  void deletesTerminalSessionHistory() {
    when(security.decide(actor, "nexus:staging:admin")).thenReturn(AccessDecision.allow());
    StagingSessionRecord session = session(StagingDao.SESSION_PROMOTED);
    when(staging.lockSession(1L)).thenReturn(Optional.of(session));
    StagingItemRecord item = item();
    when(staging.listItems(1L)).thenReturn(List.of(item));

    service.delete(1L, actor, "127.0.0.1");

    verify(blobReferences).releaseOwner(StagingService.BLOB_OWNER_TYPE, 3L);
    verify(staging).deleteItems(1L);
    verify(staging).deleteSession(1L);
  }

  @Test
  void refusesToDeleteAnActiveSession() {
    when(security.decide(actor, "nexus:staging:admin")).thenReturn(AccessDecision.allow());
    when(staging.lockSession(1L)).thenReturn(Optional.of(session(StagingDao.SESSION_OPEN)));

    assertThrows(ResponseStatusException.class,
        () -> service.delete(1L, actor, "127.0.0.1"));
  }

  private static StagingSessionRecord session(String state) {
    Instant now = Instant.now();
    return new StagingSessionRecord(
        1L, "release", state, 1, "Local", "alice", now, now, null, null,
        now.plus(1, ChronoUnit.DAYS), null, Map.of());
  }

  private static StagingItemRecord item() {
    return new StagingItemRecord(
        3L, 1L, 1L, com.github.klboke.kkrepo.core.RepositoryFormat.RAW, "docs/a.txt",
        new byte[32], "ADD", "PROMOTED", 1L, 5L, "text/plain", 1L, "2".repeat(64),
        "3".repeat(32), "1".repeat(40), null, null, null, Map.of(),
        Instant.now(), Instant.now());
  }

  private static PlatformTransactionManager transactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus(true);
      }

      @Override
      public void commit(TransactionStatus status) {
      }

      @Override
      public void rollback(TransactionStatus status) {
      }
    };
  }
}
