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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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

  private static final class Fixture {
    final StagingDao staging = mock(StagingDao.class);
    final RepositoryDao repositories = mock(RepositoryDao.class);
    final AssetDao assets = mock(AssetDao.class);
    final BlobReferenceDao blobReferences = mock(BlobReferenceDao.class);
    final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
    final RawHostedService rawHosted = mock(RawHostedService.class);
    final BlobStorageRegistry blobStorageRegistry = mock(BlobStorageRegistry.class);
    final SecurityAuditDao audit = mock(SecurityAuditDao.class);
    final SecurityManagementService security = mock(SecurityManagementService.class);
    final RepositoryService repositoryService = mock(RepositoryService.class);
    final AtomicReference<StagingSessionRecord> session =
        new AtomicReference<>(session(StagingDao.SESSION_OPEN, 0));
    final RepositoryRecord repository = new RepositoryRecord(
        1L, "raw", RepositoryFormat.RAW, RepositoryType.HOSTED, "raw-hosted", true,
        1L, null, null, null, null, "ALLOW", true, Map.of());
    final RepositoryRuntime runtime = mock(RepositoryRuntime.class);
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
      service = new StagingService(
          staging, repositories, repositoryService, assets, blobReferences, runtimes, rawHosted,
          blobStorageRegistry, audit, security, List.of(), transactionManager(), 100);
    }

    AssetBlobRecord blob(long id, String sha, long size) {
      Instant now = Instant.now();
      return new AssetBlobRecord(
          id, 1L, "blob-" + id, new byte[32], "objects/" + id, new byte[32],
          "d".repeat(40), sha, "c".repeat(32), size, "text/plain", "alice",
          "127.0.0.1", now, now, Map.of());
    }
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
