package com.github.klboke.kkrepo.server.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobReferenceDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityAuditDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingItemRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingPromotionRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.repositories.RepositoryService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StagingServiceTest {
  private static final PermissionSubject ALICE =
      new PermissionSubject("Local", "alice", Set.of(), null);

  @Test
  void preflightMarksAnAddConflictWhenADifferentLiveAssetOwnsThePath() {
    Fixture fixture = new Fixture();
    String sha = "a".repeat(64);
    StagingItemRecord item = item(StagingDao.ITEM_ADD, sha, 5L, 11L);
    fixture.session.set(session(StagingDao.SESSION_OPEN, 0));
    when(fixture.staging.listItems(1L)).thenReturn(List.of(item));
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.repositories.findById(1L)).thenReturn(Optional.of(fixture.repository));
    when(fixture.assets.lockLiveBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findAssetByPath(1L, "docs/a.bin")).thenReturn(Optional.of(liveAsset(20L)));
    when(fixture.assets.findBlobById(20L)).thenReturn(Optional.of(fixture.blob(20L, "b".repeat(64), 5L)));
    when(fixture.staging.insertPromotion(any())).thenReturn(7L);

    StagingService.StagingReport report = fixture.service.preflight(1L, ALICE, "127.0.0.1");

    assertFalse(report.promotable());
    assertTrue(report.error().contains("different live asset"));
    verify(fixture.staging).updateItemStatus(item.id(), StagingDao.ITEM_CONFLICT);
    assertEquals(StagingDao.SESSION_FROZEN, fixture.session.get().state());
  }

  @Test
  void promotionAppliesTheStagedAddAndReleasesTheStagingBlobReference() {
    Fixture fixture = new Fixture();
    String sha = "a".repeat(64);
    StagingItemRecord item = item(StagingDao.ITEM_ADD, sha, 5L, 11L);
    fixture.session.set(session(StagingDao.SESSION_PREFLIGHTED, 2));
    StagingPromotionRecord promotion = new StagingPromotionRecord(
        5L, 1L, StagingDao.PROMOTION_PREFLIGHT, null, manifest(List.of(item)),
        Map.of("promotable", true), null, null, Instant.now());
    when(fixture.staging.listItems(1L)).thenReturn(List.of(item));
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.lockPromotion(5L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.findLatestPromotion(1L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.lockRepositories(any())).thenReturn(List.of(1L));
    when(fixture.repositories.findById(1L)).thenReturn(Optional.of(fixture.repository));
    when(fixture.runtimes.resolve(fixture.repository)).thenReturn(Optional.of(fixture.runtime));
    when(fixture.assets.lockLiveBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findAssetByPath(1L, "docs/a.bin")).thenReturn(Optional.empty());
    when(fixture.rawHosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        eq(fixture.runtime), eq("docs/a.bin"), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    StagingService.StagingReport report = fixture.service.promote(1L, ALICE, "127.0.0.1");

    assertTrue(report.promotable());
    assertEquals(StagingDao.PROMOTION_SUCCEEDED, report.state());
    assertEquals(StagingDao.SESSION_PROMOTED, fixture.session.get().state());
    verify(fixture.rawHosted).linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        eq(fixture.runtime), eq("docs/a.bin"), any(), any(), any(), any(), any(), any());
    verify(fixture.blobReferences).releaseOwner(StagingService.BLOB_OWNER_TYPE, item.id());
    verify(fixture.staging).updateItemStatus(item.id(), StagingDao.ITEM_PROMOTED);
  }

  @Test
  void promotionPreparesPayloadsBeforeCommittingThePromotingState() {
    Fixture fixture = new Fixture();
    String sha = "a".repeat(64);
    StagingItemRecord item = item(StagingDao.ITEM_ADD, sha, 5L, 11L);
    fixture.session.set(session(StagingDao.SESSION_PREFLIGHTED, 2));
    StagingPromotionRecord promotion = new StagingPromotionRecord(
        5L, 1L, StagingDao.PROMOTION_PREFLIGHT, null, manifest(List.of(item)),
        Map.of("promotable", true), null, null, Instant.now());
    when(fixture.staging.listItems(1L)).thenReturn(List.of(item));
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.lockPromotion(5L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.findLatestPromotion(1L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.lockRepositories(any())).thenReturn(List.of(1L));
    when(fixture.repositories.findById(1L)).thenReturn(Optional.of(fixture.repository));
    when(fixture.runtimes.resolve(fixture.repository)).thenReturn(Optional.of(fixture.runtime));
    when(fixture.assets.lockLiveBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findAssetByPath(1L, "docs/a.bin")).thenReturn(Optional.empty());
    when(fixture.rawHosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        eq(fixture.runtime), eq("docs/a.bin"), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    org.mockito.InOrder order =
        org.mockito.Mockito.inOrder(fixture.blobStorage, fixture.staging);
    fixture.service.promote(1L, ALICE, "127.0.0.1");

    // PROMOTING is only committed once the payload is on disk; a replica death during
    // preparation therefore leaves a retryable PREFLIGHTED session instead of a stranded one.
    order.verify(fixture.blobStorage).get(any());
    order.verify(fixture.staging).transitionSession(
        eq(1L), anyLong(), any(), eq(StagingDao.SESSION_PROMOTING), any(), any(), any());
  }

  @Test
  void commitPreparesPayloadsWithoutAnAmbientTransaction() {
    Fixture fixture = new Fixture();
    String sha = "a".repeat(64);
    StagingItemRecord item = item(StagingDao.ITEM_ADD, sha, 5L, 11L);
    fixture.session.set(session(StagingDao.SESSION_PREFLIGHTED, 2));
    StagingPromotionRecord promotion = new StagingPromotionRecord(
        7L, 1L, StagingDao.PROMOTION_PREFLIGHT, null, manifest(List.of(item)),
        Map.of("promotable", true), null, null, Instant.now());
    when(fixture.staging.listItems(1L)).thenReturn(List.of(item));
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.insertPromotion(any())).thenReturn(7L);
    when(fixture.staging.lockPromotion(7L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.findLatestPromotion(1L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.lockRepositories(any())).thenReturn(List.of(1L));
    when(fixture.repositories.findById(1L)).thenReturn(Optional.of(fixture.repository));
    when(fixture.runtimes.resolve(fixture.repository)).thenReturn(Optional.of(fixture.runtime));
    when(fixture.assets.lockLiveBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findAssetByPath(1L, "docs/a.bin")).thenReturn(Optional.empty());
    when(fixture.rawHosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        eq(fixture.runtime), eq("docs/a.bin"), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);
    java.util.concurrent.atomic.AtomicBoolean transactionWhilePreparing =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    when(fixture.blobStorage.get(any())).thenAnswer(invocation -> {
      transactionWhilePreparing.set(
          TransactionSynchronizationManager.isActualTransactionActive());
      return Optional.of(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
    });

    StagingService.StagingReport report = transactionalProxy(fixture.service, fixture.transactions)
        .commit(1L, ALICE, "127.0.0.1");

    assertTrue(report.promotable());
    // The outer commit must not wrap preflight and promote in one transaction: the object-store
    // read has to happen with no session or blob lock held.
    assertFalse(transactionWhilePreparing.get());
  }

  @Test
  void promotionMaterializesPayloadsBeforeLockingTargetRepositories() {
    Fixture fixture = new Fixture();
    String sha = "a".repeat(64);
    StagingItemRecord item = item(StagingDao.ITEM_ADD, sha, 5L, 11L);
    fixture.session.set(session(StagingDao.SESSION_PREFLIGHTED, 2));
    StagingPromotionRecord promotion = new StagingPromotionRecord(
        5L, 1L, StagingDao.PROMOTION_PREFLIGHT, null, manifest(List.of(item)),
        Map.of("promotable", true), null, null, Instant.now());
    when(fixture.staging.listItems(1L)).thenReturn(List.of(item));
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.lockPromotion(5L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.findLatestPromotion(1L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.lockRepositories(any())).thenReturn(List.of(1L));
    when(fixture.repositories.findById(1L)).thenReturn(Optional.of(fixture.repository));
    when(fixture.runtimes.resolve(fixture.repository)).thenReturn(Optional.of(fixture.runtime));
    when(fixture.assets.lockLiveBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findAssetByPath(1L, "docs/a.bin")).thenReturn(Optional.empty());
    when(fixture.rawHosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        eq(fixture.runtime), eq("docs/a.bin"), any(), any(), any(), any(), any(), any()))
        .thenReturn(true);

    fixture.service.promote(1L, ALICE, "127.0.0.1");

    // The object-store read happens while the session and repository rows are still unlocked; the
    // locked transaction only links the catalogue rows from the prepared payload.
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(fixture.blobStorage, fixture.staging);
    order.verify(fixture.blobStorage).get(any());
    order.verify(fixture.staging).lockRepositories(any());
  }

  @Test
  void promotionFailsAndRecordsTheAttemptWhenALiveAssetAppears() {
    Fixture fixture = new Fixture();
    String sha = "a".repeat(64);
    StagingItemRecord item = item(StagingDao.ITEM_ADD, sha, 5L, 11L);
    fixture.session.set(session(StagingDao.SESSION_PREFLIGHTED, 2));
    StagingPromotionRecord promotion = new StagingPromotionRecord(
        5L, 1L, StagingDao.PROMOTION_PREFLIGHT, null, manifest(List.of(item)),
        Map.of("promotable", true), null, null, Instant.now());
    when(fixture.staging.listItems(1L)).thenReturn(List.of(item));
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.lockPromotion(5L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.findLatestPromotion(1L)).thenReturn(Optional.of(promotion));
    when(fixture.staging.lockRepositories(any())).thenReturn(List.of(1L));
    when(fixture.repositories.findById(1L)).thenReturn(Optional.of(fixture.repository));
    when(fixture.runtimes.resolve(fixture.repository)).thenReturn(Optional.of(fixture.runtime));
    when(fixture.assets.lockLiveBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findBlobById(11L)).thenReturn(Optional.of(fixture.blob(11L, sha, 5L)));
    when(fixture.assets.findAssetByPath(1L, "docs/a.bin"))
        .thenReturn(Optional.empty(), Optional.of(liveAsset(20L)));
    when(fixture.assets.findBlobById(20L))
        .thenReturn(Optional.of(fixture.blob(20L, "b".repeat(64), 5L)));
    when(fixture.rawHosted.linkInternalBlobWithComponentAtBrowsePathIfAbsent(
        eq(fixture.runtime), eq("docs/a.bin"), any(), any(), any(), any(), any(), any()))
        .thenReturn(false);

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> fixture.service.promote(1L, ALICE, "127.0.0.1"));

    assertEquals(StagingDao.SESSION_FAILED, fixture.session.get().state());
    verify(fixture.blobReferences, never()).releaseOwner(any(), anyLong());
  }

  @Test
  void sessionViewCountsItemsAndLoadsRepositoriesInBatches() {
    Fixture fixture = new Fixture();
    when(fixture.staging.listRepositories(1L)).thenReturn(List.of(
        new com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord(
            1L, 1L, RepositoryFormat.RAW, Instant.now()),
        new com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord(
            1L, 2L, RepositoryFormat.RAW, Instant.now()),
        new com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord(
            1L, 3L, RepositoryFormat.RAW, Instant.now())));
    when(fixture.repositories.findByIds(any())).thenReturn(List.of(
        repository(1L, "repo-a"), repository(2L, "repo-b"), repository(3L, "repo-c")));
    when(fixture.staging.countItems(1L)).thenReturn(42L);

    StagingService.StagingSessionView view = fixture.service.get(1L, ALICE);

    assertEquals(42L, view.itemCount());
    assertEquals(List.of("repo-a", "repo-b", "repo-c"),
        view.repositories().stream().map(StagingService.StagingRepositoryView::name).toList());
    // One batch lookup and one aggregate count instead of N repository reads and a full item list.
    verify(fixture.repositories, never()).findById(anyLong());
    verify(fixture.staging, never()).listItems(anyLong());
  }

  @Test
  void uploadIsRejectedAtAdmissionWhenTheSessionItemBudgetIsExhausted() {
    Fixture fixture = new Fixture();
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.countItems(1L)).thenReturn(100L);
    when(fixture.staging.findItem(eq(1L), eq(1L), any())).thenReturn(Optional.empty());

    org.springframework.web.server.ResponseStatusException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> fixture.service.putRaw(
                fixture.runtime, "1", "extra.bin",
                new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}),
                "text/plain", ALICE, "127.0.0.1"));

    assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
    assertTrue(String.valueOf(error.getReason()).contains("limit of 100 items"));
    // The budget is enforced before the body is streamed and stored, not after materializing it.
    verify(fixture.rawHosted, never()).storeStagedBlob(
        any(), any(), any(), any(), any(), any(), any());
    verify(fixture.staging, never()).tryInsertItem(any());
  }

  @Test
  void uploadReplacingAnExistingItemIsAllowedWhenTheSessionItemBudgetIsExhausted() {
    Fixture fixture = new Fixture();
    StagingItemRecord existing = item(StagingDao.ITEM_ADD, "a".repeat(64), 3L, 9L);
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.countItems(1L)).thenReturn(100L);
    when(fixture.staging.findItem(eq(1L), eq(1L), any())).thenReturn(Optional.of(existing));
    when(fixture.assets.findAssetByPath(eq(1L), any())).thenReturn(Optional.empty());
    when(fixture.rawHosted.storeStagedBlob(
        eq(fixture.runtime), any(), any(), any(), any(), any(), any()))
        .thenReturn(fixture.blob(11L, "b".repeat(64), 3L));
    when(fixture.staging.updateItem(any())).thenReturn(1);
    when(fixture.blobReferences.retain(any(), anyLong(), anyLong())).thenReturn(true);

    StagingService.StagingWriteResult result = fixture.service.putRaw(
        fixture.runtime, "1", "docs/a.bin",
        new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}),
        "text/plain", ALICE, "127.0.0.1");

    // A replacement does not grow the session, so a full session must still accept it.
    assertEquals(existing.id(), result.itemId());
    verify(fixture.rawHosted).storeStagedBlob(
        eq(fixture.runtime), any(), any(), any(), any(), any(), any());
  }

  @Test
  void stagedItemChangesAdvanceTheSharedMetadataRevision() {
    Fixture fixture = new Fixture();
    com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark watermark =
        new com.github.klboke.kkrepo.server.support.InMemoryVersionWatermark();
    fixture.service.setVersionWatermark(watermark);
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.staging.findItem(eq(1L), eq(1L), any())).thenReturn(Optional.empty());
    when(fixture.staging.tryInsertItem(any()))
        .thenReturn(java.util.OptionalLong.of(11L));
    when(fixture.blobReferences.retain(any(), anyLong(), anyLong())).thenReturn(true);
    when(fixture.rawHosted.storeStagedBlob(
        eq(fixture.runtime), any(), any(), any(), any(), any(), any()))
        .thenReturn(fixture.blob(11L, "a".repeat(64), 3L));

    fixture.service.putRaw(
        fixture.runtime, "1", "docs/a.bin",
        new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}),
        "text/plain", ALICE, "127.0.0.1");

    // Sibling replicas discard their rendered Yum/APT snapshots after the watermark poll TTL.
    assertEquals(1L,
        watermark.current(StagingMetadataSnapshotCache.versionName(1L)));
  }

  @Test
  void sessionListViewUsesGroupedQueriesInsteadOfPerSessionLookups() {
    Fixture fixture = new Fixture();
    java.util.List<StagingSessionRecord> sessions = new java.util.ArrayList<>();
    java.util.List<com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingRepositoryRecord>
        memberships = new java.util.ArrayList<>();
    for (long id = 1; id <= 100; id++) {
      sessions.add(new StagingSessionRecord(
          id, "release-" + id, StagingDao.SESSION_OPEN, 0, "Local", "alice",
          Instant.now(), Instant.now(), null, null, Instant.now().plus(1, ChronoUnit.DAYS),
          null, Map.of()));
      for (long repo = 1; repo <= 3; repo++) {
        memberships.add(new com.github.klboke.kkrepo.persistence.jdbc.api.model
            .StagingRepositoryRecord(id, repo, RepositoryFormat.RAW, Instant.now()));
      }
    }
    when(fixture.staging.listSessions(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(sessions);
    when(fixture.staging.listRepositoriesBySessionIds(any())).thenReturn(memberships);
    when(fixture.repositories.findByIds(any())).thenReturn(List.of(
        repository(1L, "repo-a"), repository(2L, "repo-b"), repository(3L, "repo-c")));
    java.util.Map<Long, Long> counts = new java.util.HashMap<>();
    sessions.forEach(session -> counts.put(session.id(), session.id()));
    when(fixture.staging.countItemsBySessionIds(any())).thenReturn(counts);

    java.util.List<StagingService.StagingSessionView> views =
        fixture.service.list(null, 100, ALICE);

    assertEquals(100, views.size());
    assertEquals(100L, views.get(99).itemCount());
    assertEquals(List.of("repo-a", "repo-b", "repo-c"),
        views.get(0).repositories().stream()
            .map(StagingService.StagingRepositoryView::name).toList());
    // Four queries for the whole page: sessions, memberships, repository names, item counts.
    verify(fixture.staging, org.mockito.Mockito.times(1)).listRepositoriesBySessionIds(any());
    verify(fixture.staging, org.mockito.Mockito.times(1)).countItemsBySessionIds(any());
    verify(fixture.repositories, org.mockito.Mockito.times(1)).findByIds(any());
    verify(fixture.staging, never()).listRepositories(anyLong());
    verify(fixture.staging, never()).countItems(anyLong());
    verify(fixture.staging, never()).listItems(anyLong());
    verify(fixture.repositories, never()).findById(anyLong());
  }

  @Test
  void createHonoursAnApprovalRequirementSuppliedInAttributes() {
    Fixture fixture = new Fixture();
    when(fixture.staging.insertSession(any())).thenReturn(1L);

    fixture.service.create(
        new StagingService.CreateSessionRequest(
            "release", List.of(), null, null,
            Map.of("approvalRequired", true, "team", "core"), null),
        ALICE, "127.0.0.1");

    var inserted = org.mockito.ArgumentCaptor.forClass(StagingSessionRecord.class);
    verify(fixture.staging).insertSession(inserted.capture());
    assertEquals(true, inserted.getValue().attributes().get("approvalRequired"));
    assertEquals("core", inserted.getValue().attributes().get("team"));
  }

  @Test
  void updateRejectsAnExplicitApprovalRequirementChange() {
    Fixture fixture = new Fixture();
    fixture.session.set(
        session(StagingDao.SESSION_OPEN, 0, Map.of("approvalRequired", true)));
    when(fixture.staging.updateSessionMetadata(anyLong(), anyLong(), any(), any()))
        .thenReturn(true);

    org.springframework.web.server.ResponseStatusException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> fixture.service.update(
                1L,
                new StagingService.UpdateSessionRequest(
                    null, Map.of("approvalRequired", false)),
                ALICE, "127.0.0.1"));

    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.getStatusCode());
    verify(fixture.staging, never()).updateSessionMetadata(anyLong(), anyLong(), any(), any());
  }

  @Test
  void updateRejectsCredentialIdentityInjection() {
    Fixture fixture = new Fixture();
    fixture.session.set(session(StagingDao.SESSION_OPEN, 0));
    when(fixture.staging.updateSessionMetadata(anyLong(), anyLong(), any(), any()))
        .thenReturn(true);

    org.springframework.web.server.ResponseStatusException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> fixture.service.update(
                1L,
                new StagingService.UpdateSessionRequest(null, Map.of("stagingTokenId", 999)),
                ALICE, "127.0.0.1"));

    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.getStatusCode());
    verify(fixture.staging, never()).updateSessionMetadata(anyLong(), anyLong(), any(), any());
  }

  @Test
  void updatePreservesApprovalAndCredentialStateAcrossAMetadataReplacement() {
    Fixture fixture = new Fixture();
    fixture.session.set(session(StagingDao.SESSION_OPEN, 0, Map.of(
        "approvalRequired", true,
        "stagingTokenId", 9,
        "stagingTokenIssuedAt", "2026-01-01T00:00:00Z",
        "team", "old")));
    when(fixture.staging.updateSessionMetadata(anyLong(), anyLong(), any(), any()))
        .thenReturn(true);

    fixture.service.update(
        1L,
        new StagingService.UpdateSessionRequest(null, Map.of("team", "new")),
        ALICE, "127.0.0.1");

    var attributes = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(fixture.staging).updateSessionMetadata(eq(1L), anyLong(), any(), attributes.capture());
    Map<?, ?> stored = (Map<?, ?>) attributes.getValue();
    assertEquals(true, stored.get("approvalRequired"));
    assertEquals(9, stored.get("stagingTokenId"));
    assertEquals("2026-01-01T00:00:00Z", stored.get("stagingTokenIssuedAt"));
    assertEquals("new", stored.get("team"));
  }

  @Test
  void emptyReplacementKeepsApprovalButDropsUserMetadata() {
    Fixture fixture = new Fixture();
    fixture.session.set(session(StagingDao.SESSION_OPEN, 0, Map.of(
        "approvalRequired", true, "stagingTokenId", 9, "team", "old")));
    when(fixture.staging.updateSessionMetadata(anyLong(), anyLong(), any(), any()))
        .thenReturn(true);

    fixture.service.update(
        1L, new StagingService.UpdateSessionRequest(null, Map.of()), ALICE, "127.0.0.1");

    var attributes = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(fixture.staging).updateSessionMetadata(eq(1L), anyLong(), any(), attributes.capture());
    Map<?, ?> stored = (Map<?, ?>) attributes.getValue();
    assertEquals(true, stored.get("approvalRequired"));
    assertEquals(9, stored.get("stagingTokenId"));
    assertFalse(stored.containsKey("team"));
  }

  private static String manifest(List<StagingItemRecord> items) {
    StringBuilder value = new StringBuilder();
    items.forEach(item -> value.append("item").append('\u0000')
        .append(item.repositoryId()).append('\u0000')
        .append(item.logicalPath()).append('\u0000')
        .append(item.action()).append('\u0000')
        .append(item.sha256() == null ? "" : item.sha256()).append('\u0000')
        .append(item.size() == null ? "" : item.size()).append('\n'));
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static StagingItemRecord item(String action, String sha, long size, long blobId) {
    return new StagingItemRecord(
        3L, 1L, 1L, RepositoryFormat.RAW, "docs/a.bin",
        PersistenceHashes.pathHash("docs/a.bin"), action, StagingDao.ITEM_STAGED,
        1L, blobId, "text/plain", size, sha, "c".repeat(32), "d".repeat(40),
        "docs/a.bin", null, null, Map.of(), Instant.now(), Instant.now());
  }

  private static StagingSessionRecord session(String state, long revision) {
    return session(state, revision, Map.of());
  }

  private static StagingSessionRecord session(
      String state, long revision, Map<String, Object> attributes) {
    Instant now = Instant.now();
    return new StagingSessionRecord(
        1L, "release", state, revision, "Local", "alice", now, now,
        state.equals(StagingDao.SESSION_OPEN) ? null : now,
        null, now.plus(1, ChronoUnit.DAYS), null, attributes);
  }

  private static AssetRecord liveAsset(long blobId) {
    return new AssetRecord(
        9L, 1L, null, blobId, RepositoryFormat.RAW, "docs/a.bin",
        PersistenceHashes.pathHash("docs/a.bin"), "a.bin", "raw", "text/plain",
        5L, null, Instant.now(), Map.of());
  }

  @Test
  void anUploadBlockedOnItsBodyDoesNotHoldTheSessionLock() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.staging.hasRepository(1L, 1L)).thenReturn(true);
    when(fixture.assets.findAssetByPath(eq(1L), any())).thenReturn(Optional.empty());
    when(fixture.staging.findItem(eq(1L), eq(1L), any())).thenReturn(Optional.empty());
    when(fixture.staging.tryInsertItem(any()))
        .thenReturn(java.util.OptionalLong.of(11L), java.util.OptionalLong.of(12L));
    when(fixture.blobReferences.retain(any(), anyLong(), anyLong())).thenReturn(true);
    java.util.concurrent.CountDownLatch storeStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseStore = new java.util.concurrent.CountDownLatch(1);
    when(fixture.rawHosted.storeStagedBlob(
        eq(fixture.runtime), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          String path = invocation.getArgument(1);
          if ("slow.bin".equals(path)) {
            storeStarted.countDown();
            assertTrue(releaseStore.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return fixture.blob(11L, "a".repeat(64), 3L);
          }
          return fixture.blob(12L, "b".repeat(64), 3L);
        });
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.Future<StagingService.StagingWriteResult> slow = pool.submit(
          () -> fixture.service.putRaw(
              fixture.runtime, "1", "slow.bin",
              new java.io.ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
              "text/plain", ALICE, "127.0.0.1"));
      assertTrue(storeStarted.await(10, java.util.concurrent.TimeUnit.SECONDS));

      // The session row is not locked while the body is being received and stored, so another
      // upload of the same session is not serialized behind a slow client or object-store I/O.
      verify(fixture.staging, never()).lockSession(1L);
      StagingService.StagingWriteResult other = fixture.service.putRaw(
          fixture.runtime, "1", "fast.bin",
          new java.io.ByteArrayInputStream("xyz".getBytes(StandardCharsets.UTF_8)),
          "text/plain", ALICE, "127.0.0.1");
      assertEquals(11L, other.itemId());

      releaseStore.countDown();
      assertEquals(12L, slow.get(10, java.util.concurrent.TimeUnit.SECONDS).itemId());
      // The lock is only acquired for the short transaction that records the staged item.
      verify(fixture.staging, org.mockito.Mockito.atLeastOnce()).lockSession(1L);
    } finally {
      releaseStore.countDown();
      pool.shutdownNow();
    }
  }

  private static final class Fixture {
    final StagingDao staging = mock(StagingDao.class);
    final RepositoryDao repositories = mock(RepositoryDao.class);
    final AssetDao assets = mock(AssetDao.class);
    final BlobReferenceDao blobReferences = mock(BlobReferenceDao.class);
    final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    final RawHostedService rawHosted = mock(RawHostedService.class);
    final BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
    final com.github.klboke.kkrepo.core.BlobStorage blobStorage =
        mock(com.github.klboke.kkrepo.core.BlobStorage.class);
    final SecurityAuditDao audit = mock(SecurityAuditDao.class);
    final SecurityManagementService security = mock(SecurityManagementService.class);
    final RepositoryService repositoryService = mock(RepositoryService.class);
    final AtomicReference<StagingSessionRecord> session =
        new AtomicReference<>(session(StagingDao.SESSION_OPEN, 0));
    final RepositoryRecord repository = new RepositoryRecord(
        1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
    final RepositoryRuntime runtime = mock(RepositoryRuntime.class);
    final CountingTransactionManager transactions = new CountingTransactionManager();
    final StagingService service;

    Fixture() {
      when(staging.lockSession(anyLong())).thenAnswer(invocation -> Optional.of(session.get()));
      when(staging.findSessionById(anyLong()))
          .thenAnswer(invocation -> Optional.of(session.get()));
      when(staging.transitionSession(
          anyLong(), anyLong(), any(), any(), any(), any(), any()))
          .thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            long revision = invocation.getArgument(1);
            java.util.Collection<String> allowed = invocation.getArgument(2);            String next = invocation.getArgument(3);
            StagingSessionRecord current = session.get();
            if (current.id() != id || current.revision() != revision
                || !allowed.contains(current.state())) {
              return false;
            }
            session.set(new StagingSessionRecord(
                current.id(), current.name(), next, current.revision() + 1,
                current.ownerSource(), current.ownerUserId(), current.createdAt(),
                current.openedAt(), invocation.getArgument(4), invocation.getArgument(5),
                current.expiresAt(), invocation.getArgument(6), current.attributes()));
            return true;
          });
      when(runtime.id()).thenReturn(1L);
      when(runtime.name()).thenReturn("raw");
      when(runtime.format()).thenReturn(RepositoryFormat.RAW);
      when(runtime.type()).thenReturn(RepositoryType.HOSTED);
      when(runtime.isHosted()).thenReturn(true);
      when(runtime.writePolicy()).thenReturn("ALLOW");
      when(security.decide(any(), any(String.class)))
          .thenReturn(com.github.klboke.kkrepo.auth.AccessDecision.deny("not admin"));
      when(blobStorageRegistry.forBlobStoreId(1L)).thenReturn(blobStorage);
      when(blobStorage.get(any())).thenAnswer(invocation ->
          Optional.of(new java.io.ByteArrayInputStream(new byte[] {1, 2, 3})));
      service = new StagingService(
          staging, repositories, repositoryService, assets, blobReferences, runtimes, rawHosted,
          blobStorageRegistry, audit, security, List.of(), transactions, 100);
    }

    AssetBlobRecord blob(long id, String sha, long size) {
      Instant now = Instant.now();
      return new AssetBlobRecord(
          id, 1L, "blob-" + id, new byte[32], "objects/" + id, new byte[32],
          "d".repeat(40), sha, "c".repeat(32), size, "text/plain", "alice",
          "127.0.0.1", now, now, Map.of());
    }
  }

  private static RepositoryRecord repository(long id, String name) {
    return new RepositoryRecord(
        id, name, RepositoryFormat.RAW, RepositoryType.HOSTED, name, true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
  }

  private static PlatformTransactionManager transactionManager() {
    return new CountingTransactionManager();
  }

  /**
   * Minimal platform transaction manager: real propagation and synchronization semantics without a
   * database, so a test can observe whether a transaction is active at a given moment.
   */
  private static final class CountingTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
    }
  }

  /** Wraps the service in the Spring transaction interceptor, as the container would. */
  private static StagingService transactionalProxy(
      StagingService target, PlatformTransactionManager transactions) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(
        transactions, new AnnotationTransactionAttributeSource()));
    return (StagingService) factory.getProxy();
  }
}
