package com.github.klboke.kkrepo.server.gitlfs;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsDao;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitLfsUploadCleanupWorkerTest {
  @Test void failedCleanupRemainsClaimableAndDoesNotBlockOtherUploads() {
    var uploads = mock(GitLfsDao.class);
    var stores = mock(BlobStorageRegistry.class);
    var storage = mock(BlobStorage.class);
    var first = abandoned("first");
    var second = abandoned("second");
    when(uploads.claimExpired(50)).thenReturn(List.of(first, second));
    when(stores.forBlobStoreId(1)).thenReturn(storage);
    doThrow(new IllegalStateException("storage unavailable")).doNothing().when(storage)
        .discardVerifiedUpload(any(), eq("multipart"));
    new GitLfsUploadCleanupWorker(uploads, stores).cleanup();
    verify(uploads, never()).reaped(eq("first"), anyString());
    verify(uploads).reaped("second", "cleanup-second");
    verify(uploads).prunePublished(100);
  }
  private static GitLfsDao.Upload abandoned(String id) {
    return new GitLfsDao.Upload(id, 1, "a".repeat(64), 3, "owner", 1, 1, 2, Instant.now(),
        Instant.now(), "REAPING", "blob://b/attempt-" + id, "attempt-" + id, "multipart", "cleanup-" + id);
  }
}
