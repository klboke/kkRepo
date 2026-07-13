package com.github.klboke.kkrepo.server.blob;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreConfig;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.file.admin.FileBlobStoreAdmin;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class FileBlobStoreCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(FileBlobStoreCleanupWorker.class);

  private final BlobStoreDao blobStoreDao;
  private final FileBlobStorageFactory fileFactory;
  private final FileBlobStoreAdmin fileAdmin;
  private final boolean enabled;
  private final long tempGraceSeconds;

  FileBlobStoreCleanupWorker(
      BlobStoreDao blobStoreDao,
      FileBlobStorageFactory fileFactory,
      FileBlobStoreAdmin fileAdmin,
      @Value("${kkrepo.storage.file.temp-cleanup-enabled:true}") boolean enabled,
      @Value("${kkrepo.storage.file.temp-cleanup-grace-seconds:86400}") long tempGraceSeconds) {
    this.blobStoreDao = blobStoreDao;
    this.fileFactory = fileFactory;
    this.fileAdmin = fileAdmin;
    this.enabled = enabled;
    this.tempGraceSeconds = tempGraceSeconds;
  }

  @Scheduled(fixedDelayString = "${kkrepo.storage.file.temp-cleanup-interval-ms:300000}")
  void cleanupTemporaryFiles() {
    if (!enabled) {
      return;
    }
    Instant olderThan = Instant.now().minusSeconds(Math.max(60, tempGraceSeconds));
    for (BlobStoreRecord record : blobStoreDao.list()) {
      if (!FileBlobStoreConfig.isFileStore(record.type(), record.attributes())) {
        continue;
      }
      try {
        long deleted = fileAdmin.cleanupTemporaryFiles(toFileConfig(record), olderThan);
        if (deleted > 0) {
          log.info("deleted {} stale temporary files from file blob store {}", deleted, record.name());
        }
      } catch (RuntimeException e) {
        log.warn("file blob store temporary cleanup failed for {}", record.name(), e);
      }
    }
  }

  private FileBlobStoreConfig toFileConfig(BlobStoreRecord record) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    Object path = attrs.get(FileBlobStoreConfig.ATTR_PATH);
    return fileFactory.configFor(
        record.id() == null ? 0 : record.id(),
        record.name(),
        path == null ? "" : path.toString());
  }
}
