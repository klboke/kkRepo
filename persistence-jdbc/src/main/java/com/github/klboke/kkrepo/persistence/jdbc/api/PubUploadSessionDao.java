package com.github.klboke.kkrepo.persistence.jdbc.api;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PubUploadSessionDao {
  String STATUS_NEW = "NEW";
  String STATUS_UPLOADED = "UPLOADED";
  String STATUS_FINALIZED = "FINALIZED";
  String STATUS_FAILED = "FAILED";

  long insert(PubUploadSessionRecord record);

  Optional<PubUploadSessionRecord> find(long repositoryId, String sessionId);

  Optional<PubUploadSessionRecord> lock(long repositoryId, String sessionId);

  Optional<PubUploadSessionRecord> lockById(long id);

  List<PubUploadSessionRecord> listExpiredOpen(Instant now, int limit);

  void markUploaded(
      long id,
      long blobStoreId,
      String blobRef,
      String objectKey,
      String md5,
      String sha1,
      String sha256,
      String sha512,
      long size,
      String packageName,
      String version,
      Map<String, Object> pubspec);

  void markFinalized(long id, Instant finalizedAt);

  void markFailed(long id, String message);

  int expireOlderThan(Instant now);
}
