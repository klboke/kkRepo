package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.persistence.jdbc.api.GitLfsDao;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each replica claims a bounded batch; cleanup has no dependency on the originating JVM. */
@Component
public class GitLfsUploadCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(GitLfsUploadCleanupWorker.class);
  private final GitLfsDao uploads;
  private final BlobStorageRegistry storages;

  public GitLfsUploadCleanupWorker(GitLfsDao uploads, BlobStorageRegistry storages) {
    this.uploads = uploads;
    this.storages = storages;
  }

  @Scheduled(fixedDelayString = "${kkrepo.gitlfs.cleanup-interval-ms:60000}", initialDelay = 60000)
  public void cleanup() {
    for (var upload : uploads.claimExpired(50)) {
      try {
        if (upload.blobRef() != null) {
          var storage = storages.forBlobStoreId(upload.blobStoreId());
          storage.discardVerifiedUpload(BlobReferenceCodec.reference(upload.blobRef(), upload.objectKey(),
              upload.oid(), upload.size()), upload.multipartId());
        }
        uploads.reaped(upload.id(), upload.cleanupToken());
      } catch (RuntimeException error) {
        log.warn("Git LFS upload cleanup will retry for {}", upload.id(), error);
      }
    }
    uploads.prunePublished(100);
  }
}
