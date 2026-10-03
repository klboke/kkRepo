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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GitLfsHostedServiceTest {
  private final AssetDao assets = mock(AssetDao.class);
  private final GitLfsDao uploads = mock(GitLfsDao.class);
  private final BlobStorageRegistry stores = mock(BlobStorageRegistry.class);
  private final GitLfsAccess access = mock(GitLfsAccess.class);
  private final ForwardedHeaderPolicy forwarded = mock(ForwardedHeaderPolicy.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final GitLfsHostedService service = new GitLfsHostedService(assets, mock(ComponentDao.class),
      mock(BrowseNodeDao.class), uploads, stores, access, forwarded, transactions, 1);
  static RepositoryRuntime repository() {
    return new RepositoryRuntime(1, "lfs", RepositoryFormat.GITLFS, RepositoryType.HOSTED,
        "gitlfs-hosted", true, 1L, "ALLOW", null, null, false, null, null, null, null, null, List.of());
  }
  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest();
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, GitLfsAccessTest.subject());
    return request;
  }
  @org.junit.jupiter.api.AfterEach void stopHeartbeat() { service.close(); }
  @Test void repeatedBatchObjectsShareOneActionAndRejectConflictingSizes() {
    String oid = "a".repeat(64);
    when(access.allowedObjects(any(), eq("lfs"), any(), eq(PermissionAction.ADD))).thenReturn(Set.of(oid));
    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of());
    var ready = upload(UUID.randomUUID().toString(), oid, 3, "READY");
    when(uploads.create(eq(1L), eq(oid), eq(3L), anyString(), eq(1L))).thenReturn(ready);
    var objects = (List<Map<String, Object>>) service.batch(repository(), new GitLfsProtocol.Batch("upload", List.of(
        new GitLfsProtocol.ObjectRequest(oid, 3, null), new GitLfsProtocol.ObjectRequest(oid, 3, null),
        new GitLfsProtocol.ObjectRequest(oid, 4, null))), request()).get("objects");
    assertEquals(objects.get(0), objects.get(1));
    assertEquals(422, ((Map<?, ?>) objects.get(2).get("error")).get("code"));
    verify(uploads, times(1)).create(anyLong(), anyString(), anyLong(), anyString(), anyLong());

    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of(oid, asset(oid, 3)));
    // Grant READ independently, then verify the stored-size boundary without issuing a download action.
    when(access.allowedObjects(any(), eq("lfs"), any(), eq(PermissionAction.READ))).thenReturn(Set.of(oid));
    var mismatch = (List<Map<String, Object>>) service.batch(repository(), new GitLfsProtocol.Batch("download",
        List.of(new GitLfsProtocol.ObjectRequest(oid, 4, null))), request()).get("objects");
    assertEquals(422, ((Map<?, ?>) mismatch.getFirst().get("error")).get("code"));
    assertFalse(mismatch.getFirst().containsKey("actions"));
  }
  @Test void anonymousBatchWithNoReadableObjectsChallengesForCredentials() {
    var request = request(); request.setAttribute(GitLfsAccess.ANONYMOUS_ATTRIBUTE, true);
    when(access.allowedObjects(any(), anyString(), any(), any())).thenReturn(Set.of());
    when(assets.findAssetsByPaths(anyLong(), any())).thenReturn(Map.of());
    var error = assertThrows(GitLfsException.class, () -> service.batch(repository(),
        new GitLfsProtocol.Batch("download", List.of(new GitLfsProtocol.ObjectRequest("a".repeat(64), 1, null))), request));
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
        new GitLfsProtocol.Batch("upload", List.of(new GitLfsProtocol.ObjectRequest(oid, 3, null))), request()));
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
    var request = request(); request.addHeader("X-KkRepo-Lfs-Upload", id); request.setContent(body);
    service.put(repository(), oid, request);
    request.setContent(new byte[] {1, 2, 3});
    assertEquals(422, assertThrows(GitLfsException.class, () -> service.put(repository(), oid, request)).status());
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
    when(forwarded.serverBaseUrl(any())).thenReturn("https://public.example");
    var batch = new GitLfsProtocol.Batch("download", List.of(
        new GitLfsProtocol.ObjectRequest(allowed, 1, null), new GitLfsProtocol.ObjectRequest(denied, 1, null)));
    var request = request(); request.addHeader("Authorization", "SECRET");
    Map<String, Object> response = service.batch(repository(), batch, request);
    assertFalse(response.toString().contains("SECRET"));
    var objects = (List<Map<String, Object>>) response.get("objects");
    assertEquals(404, ((Map<?, ?>) objects.get(0).get("error")).get("code"));
    assertEquals(403, ((Map<?, ?>) objects.get(1).get("error")).get("code"));
    assertFalse(objects.get(1).containsKey("actions"));
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
    var request = request(); request.setContent(new byte[] {1, 2, 3}); request.addHeader("X-KkRepo-Lfs-Upload", id);
    var error = assertThrows(GitLfsException.class, () -> service.put(repository(), oid, request));
    assertEquals(revoked ? 403 : 409, error.status());
    verify(uploads).abandon(id, 2);
    verify(assets, never()).insertAsset(any());
    verify(uploads, never()).published(anyString(), anyLong(), anyLong());
    // Physical deletion belongs to a database-claimed worker, never a racy request finally block.
    verify(storage, never()).delete(any());
    service.close();
  }
}
