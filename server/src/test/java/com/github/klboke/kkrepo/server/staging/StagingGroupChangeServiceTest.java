package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingGroupChangeRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
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

class StagingGroupChangeServiceTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final RepositoryService repositoryService = mock(RepositoryService.class);
  private final AssetDao assets = mock(AssetDao.class);
  private final BlobReferenceDao blobReferences = mock(BlobReferenceDao.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final RawHostedService rawHosted = mock(RawHostedService.class);
  private final BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
  private final SecurityAuditDao audit = mock(SecurityAuditDao.class);
  private final SecurityManagementService security = mock(SecurityManagementService.class);
  private final StagingService service = new StagingService(
      staging, repositories, repositoryService, assets, blobReferences, runtimes, rawHosted,
      blobStorageRegistry, audit, security, List.of(), transactionManager(), 100);
  private final PermissionSubject actor =
      new PermissionSubject("Local", "alice", Set.of(), null);

  @Test
  void addingAGroupChangeRecordsBothRepositoriesAndTheChange() {
    Instant now = Instant.now();
    StagingSessionRecord session = new StagingSessionRecord(
        1L, "release", StagingDao.SESSION_OPEN, 0, "Local", "alice", now, now, null,
        null, now.plus(1, ChronoUnit.DAYS), null, Map.of());
    RepositoryRecord group = repository(10L, "group", RepositoryFormat.RAW, RepositoryType.GROUP);
    RepositoryRecord member = repository(11L, "member", RepositoryFormat.RAW, RepositoryType.HOSTED);
    when(staging.lockSession(1L)).thenReturn(Optional.of(session));
    when(repositories.findById(10L)).thenReturn(Optional.of(group));
    when(repositories.findById(11L)).thenReturn(Optional.of(member));
    when(repositories.listMembers(10L)).thenReturn(List.of());
    when(staging.findGroupChange(1L, 10L, 11L)).thenReturn(Optional.of(
        new StagingGroupChangeRecord(
            3L, 1L, 10L, 11L, StagingDao.GROUP_ADD, null, StagingDao.GROUP_STAGED,
            now, now)));

    StagingService.GroupChangeView view = service.addGroupChange(
        1L, 10L, 11L, "ADD", null, actor, "127.0.0.1");

    assertEquals("ADD", view.action());
    verify(staging).addRepository(1L, 10L, "raw");
    verify(staging).addRepository(1L, 11L, "raw");
    verify(staging).upsertGroupChange(any());
    verify(audit).insert(any());
  }

  @Test
  void metricsRequireStagingAdmin() {
    when(security.decide(actor, "nexus:staging:admin"))
        .thenReturn(AccessDecision.deny("not admin"));
    assertThrows(ResponseStatusException.class, () -> service.metrics(actor));

    when(security.decide(actor, "nexus:staging:admin"))
        .thenReturn(AccessDecision.allow());
    when(staging.countSessionsByState()).thenReturn(Map.of(StagingDao.SESSION_OPEN, 2L));
    when(staging.countItemsByStatus()).thenReturn(Map.of(StagingDao.ITEM_STAGED, 3L));
    when(staging.countGroupChangesByStatus()).thenReturn(Map.of(StagingDao.GROUP_STAGED, 1L));

    StagingService.StagingMetrics metrics = service.metrics(actor);
    assertEquals(2L, metrics.sessionsByState().get(StagingDao.SESSION_OPEN));
    assertEquals(3L, metrics.itemsByStatus().get(StagingDao.ITEM_STAGED));
    assertEquals(1L, metrics.groupChangesByStatus().get(StagingDao.GROUP_STAGED));
  }

  @Test
  void rejectsAMemberWithADifferentFormat() {
    Instant now = Instant.now();
    StagingSessionRecord session = new StagingSessionRecord(
        1L, "release", StagingDao.SESSION_OPEN, 0, "Local", "alice", now, now, null,
        null, now.plus(1, ChronoUnit.DAYS), null, Map.of());
    when(staging.lockSession(1L)).thenReturn(Optional.of(session));
    when(repositories.findById(10L)).thenReturn(Optional.of(
        repository(10L, "group", RepositoryFormat.RAW, RepositoryType.GROUP)));
    when(repositories.findById(11L)).thenReturn(Optional.of(
        repository(11L, "member", RepositoryFormat.NPM, RepositoryType.HOSTED)));

    assertThrows(ResponseStatusException.class, () -> service.addGroupChange(
        1L, 10L, 11L, "ADD", null, actor, "127.0.0.1"));
  }

  private static RepositoryRecord repository(
      long id, String name, RepositoryFormat format, RepositoryType type) {
    return new RepositoryRecord(
        id, name, format, type, format.id() + "-hosted", true, 1L, null, null, null,
        null, "ALLOW", true, Map.of());
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
