package com.github.klboke.kkrepo.server.gitlfs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.protocol.gitlfs.*;
import com.github.klboke.kkrepo.server.maven.*;
import com.github.klboke.kkrepo.server.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GitLfsHostedServiceTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final GitLfsDao uploads = mock(GitLfsDao.class);
  private final BlobStorageRegistry stores = mock(BlobStorageRegistry.class);
  private final GitLfsAccess access = mock(GitLfsAccess.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final GitLfsHostedService service = new GitLfsHostedService(assets, mock(ComponentDao.class),
      mock(BrowseNodeDao.class), uploads, stores, access, transactions, 1);
  private static final String BASE = "https://public.example/repository/lfs/";
  static RepositoryRuntime repository() {
    return new RepositoryRuntime(1, "lfs", RepositoryFormat.GITLFS, RepositoryType.HOSTED,
        "gitlfs-hosted", true, 1L, "ALLOW", null, null, false, null, null, null, null, null, List.of());
  }
  private static GitLfsCaller caller() {
    return caller(false);
  }
  private static GitLfsCaller caller(boolean anonymous) {
    return new GitLfsCaller(GitLfsAccessTest.subject(), anonymous, "203.0.113.7",
        () -> Optional.of(GitLfsAccessTest.subject()));
  }
  private static java.io.InputStream body(byte[] bytes) {
    return new java.io.ByteArrayInputStream(bytes);
  }
  @org.junit.jupiter.api.AfterEach void stopHeartbeat() { service.close(); }
  @Test void repeatedBatchObjectsShareOneActionAndRejectConflictingSizes() {
    String oid = "a".repeat(64);
    when(access.allowedObjects(any(), eq("lfs"), any(), eq(PermissionAction.ADD))).thenReturn(Set.of(oid));
    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of());
    var ready = upload(UUID.randomUUID().toString(), oid, 3, "READY");
    when(uploads.create(eq(1L), eq(oid), eq(3L), anyString(), eq(1L))).thenReturn(ready);
    var objects = service.batch(repository(), new GitLfsProtocol.Batch("upload", List.of(
        new GitLfsProtocol.ObjectRequest(oid, 3, null), new GitLfsProtocol.ObjectRequest(oid, 3, null),
        new GitLfsProtocol.ObjectRequest(oid, 4, null))), caller(), BASE).objects();
    assertEquals(GitLfsBatchResult.ObjectResult.upload(oid, 3, ready.id(), 900), objects.get(0));
    assertEquals(objects.get(0), objects.get(1));
    assertEquals(422, objects.get(2).errorCode());
    assertEquals("Conflicting object sizes", objects.get(2).errorMessage());
    verify(uploads, times(1)).create(anyLong(), anyString(), anyLong(), anyString(), anyLong());

    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of(oid, asset(oid, 3)));
    // Grant READ independently, then verify the stored-size boundary without issuing a download action.
    when(access.allowedObjects(any(), eq("lfs"), any(), eq(PermissionAction.READ))).thenReturn(Set.of(oid));
    var mismatch = service.batch(repository(), new GitLfsProtocol.Batch("download",
        List.of(new GitLfsProtocol.ObjectRequest(oid, 4, null))), caller(), BASE).objects();
    assertEquals(GitLfsBatchResult.ObjectResult.error(oid, 4, 422, "Object size does not match stored content"),
        mismatch.getFirst());
    var present = service.batch(repository(), new GitLfsProtocol.Batch("upload",
        List.of(new GitLfsProtocol.ObjectRequest(oid, 3, null))), caller(), BASE).objects();
    assertEquals(GitLfsBatchResult.ObjectResult.present(oid, 3), present.getFirst());
    verify(uploads, times(1)).create(anyLong(), anyString(), anyLong(), anyString(), anyLong());
  }
  @Test void anonymousBatchWithNoReadableObjectsChallengesForCredentials() {
    when(access.allowedObjects(any(), anyString(), any(), any())).thenReturn(Set.of());
    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of());
    var error = assertThrows(GitLfsException.class, () -> service.batch(repository(),
        new GitLfsProtocol.Batch("download", List.of(new GitLfsProtocol.ObjectRequest("a".repeat(64), 1, null))),
        caller(true), BASE));
    assertEquals(401, error.status());
    verifyNoInteractions(uploads);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(GitLfsRepositoryStateException.Reason.class)
  void batchReportsDurableRepositoryChangesAsLfsErrors(GitLfsRepositoryStateException.Reason reason) {
    String oid = "a".repeat(64);
    when(access.allowedObjects(any(), eq("lfs"), any(), eq(PermissionAction.ADD))).thenReturn(Set.of(oid));
    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of());
    when(uploads.create(anyLong(), anyString(), anyLong(), anyString(), anyLong()))
        .thenThrow(new GitLfsRepositoryStateException(reason));
    var error = assertThrows(GitLfsException.class, () -> service.batch(repository(),
        new GitLfsProtocol.Batch("upload", List.of(new GitLfsProtocol.ObjectRequest(oid, 3, null))), caller(), BASE));
    assertEquals(switch (reason) {
      case MISSING -> 404;
      case OFFLINE -> 503;
      case READ_ONLY -> 403;
      case CONFIGURATION_CHANGED -> 409;
    }, error.status());
    verifyNoInteractions(stores);
  }
  @Test void lostSuccessResponseCanReplayOnlyTheOriginalBytesWithoutRewritingStorage() throws Exception {
    byte[] body = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String oid = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
    String id = UUID.randomUUID().toString();
    when(uploads.find(id)).thenReturn(Optional.of(upload(id, oid, body.length, "PUBLISHED")));
    when(assets.findAssetByPath(1, oid)).thenReturn(Optional.of(asset(oid, body.length)));
    BlobStorage storage = mock(BlobStorage.class);
    when(stores.forBlobStoreId(1)).thenReturn(storage);
    when(storage.prepareVerifiedUpload("lfs", oid, body.length)).thenReturn(new BlobReference("b", "unused", oid, body.length));
    service.put(repository(), oid, id, body.length, body(body), caller());
    assertEquals(422, assertThrows(GitLfsException.class,
        () -> service.put(repository(), oid, id, 3, body(new byte[] {1, 2, 3}), caller())).status());
    assertEquals(422, assertThrows(GitLfsException.class,
        () -> service.put(repository(), oid, id, 4, body(body), caller())).status());
    assertEquals(422, assertThrows(GitLfsException.class,
        () -> service.put(repository(), oid, null, 3, body(body), caller())).status());
    verify(storage, never()).uploadVerified(any(), any(), any());
    verify(uploads, never()).claim(anyString(), anyString(), anyString(), anyString());
    verify(assets, never()).insertAsset(any());
  }
  private static GitLfsDao.Upload upload(String id, String oid, long size, String state) {
    return new GitLfsDao.Upload(id, 1, oid, size, GitLfsAccess.subjectKey(GitLfsAccessTest.subject()),
        1, 1, 2, Instant.now(), Instant.now().plusSeconds(300), state, null, null, null, null);
  }
  private static com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord asset(String oid, long size) {
    return new com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord(
        12L, 1L, 3L, 4L, RepositoryFormat.GITLFS, oid, null, oid, "gitlfs", "application/octet-stream",
        size, null, Instant.now(), Map.of());
  }
  @Test void mixedBatchChecksEachOidBeforeRevealingExistenceAndDoesNotEchoCredentials() {
    String allowed = "a".repeat(64), denied = "b".repeat(64);
    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of());
    when(access.allowedObjects(any(), eq("lfs"), any(), eq(PermissionAction.READ))).thenReturn(Set.of(allowed));
    var batch = new GitLfsProtocol.Batch("download", List.of(
        new GitLfsProtocol.ObjectRequest(allowed, 1, null), new GitLfsProtocol.ObjectRequest(denied, 1, null)));
    var response = service.batch(repository(), batch, caller(), BASE);
    assertEquals(BASE, response.objectBaseUrl());
    var objects = response.objects();
    assertEquals(404, objects.get(0).errorCode());
    assertEquals(403, objects.get(1).errorCode());
    assertEquals(GitLfsBatchResult.Outcome.ERROR, objects.get(1).outcome());
    verify(access).allowedObjects(any(), eq("lfs"), eq(List.of(allowed, denied)), eq(PermissionAction.READ));
    service.close();
  }
  @Test void managementDeletionUnlinksAssetAndLeavesPhysicalCollectionToGc() {
    var stored = new com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord(
        12L, 1L, 3L, 4L, RepositoryFormat.GITLFS, "a".repeat(64), null,
        "a".repeat(64), "gitlfs", "application/octet-stream", 3L, null, Instant.now(), Map.of());
    when(assets.findAssetById(12)).thenReturn(Optional.of(stored));
    when(assets.deleteAssetById(12)).thenReturn(1);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    assertEquals(404, service.deleteById(2, 12));
    verify(assets, never()).deleteAssetById(anyLong());
    assertEquals(204, service.deleteById(1, 12));
    verify(assets).markBlobDeletedIfUnreferenced(4, "Git LFS asset unlinked");
    verifyNoInteractions(stores);
    service.close();
  }
  @Test void revokedPermissionAfterStreamingCannotPublishAndLeavesDurableGarbage() throws Exception {
    assertFailedPublication(true);
  }
  @Test void lostPublicationFenceCannotCreateAsset() throws Exception {
    assertFailedPublication(false);
  }
  private void assertFailedPublication(boolean revoked) throws Exception {
    String id = UUID.randomUUID().toString(), oid = "a".repeat(64);
    var subject = GitLfsAccessTest.subject();
    String owner = GitLfsAccess.subjectKey(subject);
    var upload = new GitLfsDao.Upload(id, 1, oid, 3, owner, 1, 1, 2, Instant.now(),
        Instant.now().plusSeconds(300), "UPLOADING", "blob://b/attempt", "attempt", null, null);
    when(uploads.find(id)).thenReturn(Optional.of(upload));
    when(uploads.claim(eq(id), eq(owner), anyString(), anyString())).thenReturn(Optional.of(upload));
    BlobStorage storage = mock(BlobStorage.class);
    when(stores.forBlobStoreId(1)).thenReturn(storage);
    when(storage.prepareVerifiedUpload("lfs", oid, 3)).thenReturn(new BlobReference("b", "attempt", oid, 3));
    when(storage.uploadVerified(any(), any(), any())).thenReturn(new VerifiedBlobDigests(oid, "s", "m", 3));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    if (revoked) doNothing().doThrow(new GitLfsException(403, "Revoked")).when(access)
        .requireUpload(any(), anyString(), anyString(), anyString());
    var error = assertThrows(GitLfsException.class,
        () -> service.put(repository(), oid, id, 3, body(new byte[] {1, 2, 3}), caller()));
    assertEquals(revoked ? 403 : 409, error.status());
    verify(uploads).abandon(id, 2);
    verify(assets, never()).insertAsset(any());
    verify(uploads, never()).published(anyString(), anyLong(), anyLong());
    // Physical deletion belongs to a database-claimed worker, never a racy request finally block.
    verify(storage, never()).delete(any());
    service.close();
  }
}
