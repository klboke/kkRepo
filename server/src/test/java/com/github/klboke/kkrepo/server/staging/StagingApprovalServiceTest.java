package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class StagingApprovalServiceTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final StagingService service = new StagingService(
      staging, repositories, mock(RepositoryService.class), mock(AssetDao.class),
      mock(BlobReferenceDao.class), mock(RepositoryRuntimeRegistry.class),
      mock(RawHostedService.class), mock(BlobStorageRegistry.class),
      mock(SecurityAuditDao.class), mock(SecurityManagementService.class), List.of(),
      transactionManager(), 100);
  private final PermissionSubject owner =
      new PermissionSubject("Local", "alice", Set.of(), null);
  private final PermissionSubject reviewer =
      new PermissionSubject("Local", "bob", Set.of(), null);

  @Test
  void approveStoresTheReviewerIdentity() {
    StagingSessionRecord session = session();
    AtomicReference<Map<String, Object>> updated = new AtomicReference<>();
    when(staging.lockSession(1L)).thenReturn(Optional.of(session));
    when(staging.updateSessionMetadata(anyLong(), anyLong(), any(), any()))
        .thenAnswer(invocation -> {
          updated.set(invocation.getArgument(3));
          return true;
        });
    when(staging.findSessionById(1L)).thenReturn(Optional.of(session));

    service.approve(1L, reviewer, "127.0.0.1");

    assertEquals("bob", updated.get().get("approvedByUserId"));
    assertEquals("Local", updated.get().get("approvedBySource"));
  }

  @Test
  void ownerCannotApproveThemselves() {
    when(staging.lockSession(1L)).thenReturn(Optional.of(session()));
    assertThrows(ResponseStatusException.class,
        () -> service.approve(1L, owner, "127.0.0.1"));
  }

  @Test
  void createMarksApprovalRequiredWithoutTrustingClientApprovalFields() {
    when(staging.findSessionByName("release")).thenReturn(Optional.empty());
    when(staging.insertSession(any())).thenReturn(1L);
    when(staging.findSessionById(1L)).thenReturn(Optional.of(session()));

    StagingService.StagingSessionView view = service.create(
        new StagingService.CreateSessionRequest(
            "release", List.of(), Instant.now().plus(1, ChronoUnit.DAYS), null,
            Map.of("approvedByUserId", "attacker", "release", "r1"), true),
        owner, "127.0.0.1");

    org.mockito.ArgumentCaptor<StagingSessionRecord> captor =
        org.mockito.ArgumentCaptor.forClass(StagingSessionRecord.class);
    verify(staging).insertSession(captor.capture());
    assertEquals(true, captor.getValue().attributes().get("approvalRequired"));
    assertEquals("r1", captor.getValue().attributes().get("release"));
    assertEquals(false, captor.getValue().attributes().containsKey("approvedByUserId"));
    assertEquals(true, view.session().attributes().get("approvalRequired"));
  }

  private static StagingSessionRecord session() {
    Instant now = Instant.now();
    Map<String, Object> attributes = new java.util.LinkedHashMap<>();
    attributes.put("approvalRequired", true);
    return new StagingSessionRecord(
        1L, "release", StagingDao.SESSION_FROZEN, 3, "Local", "alice", now, now,
        now, null, now.plus(1, ChronoUnit.DAYS), null, attributes);
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
