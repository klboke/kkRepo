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
