package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingPromotionRecord;
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
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class StagingCompensationServiceTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final RepositoryDao repositories = mock(RepositoryDao.class);
  private final RepositoryService repositoryService = mock(RepositoryService.class);
  private final BlobReferenceDao blobReferences = mock(BlobReferenceDao.class);
  private final StagingService service = new StagingService(
      staging, repositories, repositoryService, mock(AssetDao.class), blobReferences,
      mock(RepositoryRuntimeRegistry.class), mock(RawHostedService.class),
      mock(BlobStorageRegistry.class), mock(SecurityAuditDao.class),
      mock(SecurityManagementService.class), List.of(), transactionManager(), 100);
  private final PermissionSubject actor =
      new PermissionSubject("Local", "alice", Set.of(), null);

  @Test
  void createsInverseDeleteItemsForAddedContent() {
    Instant now = Instant.now();
    StagingSessionRecord original = new StagingSessionRecord(
        1L, "release", StagingDao.SESSION_PROMOTED, 4, "Local", "alice", now, now,
        now, now, now.plus(1, ChronoUnit.DAYS), 5L, Map.of());
    StagingPromotionRecord promotion = new StagingPromotionRecord(
        5L, 1L, StagingDao.PROMOTION_SUCCEEDED, null, "a".repeat(64),
        Map.of("items", List.of(Map.of(
            "repositoryId", 1L,
            "path", "docs/a.txt",
            "action", "ADD",
            "result", "published",
            "stagedAttributes", Map.of()))),
        now, now, now);
    RepositoryRecord repository = new RepositoryRecord(
        1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
    when(staging.findSessionById(1L)).thenReturn(Optional.of(original));
    when(staging.findPromotion(5L)).thenReturn(Optional.of(promotion));
    when(staging.insertSession(any())).thenReturn(2L);
    when(staging.findSessionById(2L)).thenReturn(Optional.of(new StagingSessionRecord(
        2L, "release-compensate", StagingDao.SESSION_OPEN, 0, "Local", "alice", now,
        now, null, null, now.plus(7, ChronoUnit.DAYS), null, Map.of())));
    when(staging.listRepositories(anyLong())).thenReturn(List.of());
    when(staging.listItems(anyLong())).thenReturn(List.of());
    when(repositories.findById(1L)).thenReturn(Optional.of(repository));
    when(staging.tryInsertItem(any())).thenReturn(OptionalLong.of(9L));

    service.compensate(1L, actor, "127.0.0.1");

    ArgumentCaptor<StagingItemRecord> captor = ArgumentCaptor.forClass(StagingItemRecord.class);
    verify(staging).tryInsertItem(captor.capture());
    assertEquals(StagingDao.ITEM_DELETE, captor.getValue().action());
    assertEquals("docs/a.txt", captor.getValue().logicalPath());
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
