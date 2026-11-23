package com.github.klboke.kkrepo.server.pub;

import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceHashes;
import com.github.klboke.kkrepo.persistence.jdbc.api.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.staging.StagingService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns a completed Pub upload session into a durable staging item. */
@Service
public class PubStagingService {
  private final PubUploadSessionDao uploadSessions;
  private final AssetDao assets;
  private final StagingService staging;
  private final PubHostedService pubHosted;

  public PubStagingService(
      PubUploadSessionDao uploadSessions,
      AssetDao assets,
      StagingService staging,
      PubHostedService pubHosted) {
    this.uploadSessions = uploadSessions;
    this.assets = assets;
    this.staging = staging;
    this.pubHosted = pubHosted;
  }

  @Transactional
  public MavenResponse finalizeStaged(
      RepositoryRuntime runtime,
      String sessionId,
      String stagingSession,
      String principalUserId,
      Long principalApiKeyId,
      String ip,
      PermissionSubject subject,
      boolean headOnly) {
    PubUploadSessionRecord session = uploadSessions.lock(runtime.id(), sessionId)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(
            "Pub upload session not found: " + sessionId));
    if (!PubUploadSessionDao.STATUS_UPLOADED.equals(session.status())) {
      throw new PubExceptions.BadRequestException(
          "Pub upload session is not ready for finalization");
    }
    if (session.principalUserId() != null
        && !session.principalUserId().equals(principalUserId)) {
      throw new PubExceptions.BadRequestException("Pub upload session belongs to another user");
    }
    if (session.principalApiKeyId() != null
        && !session.principalApiKeyId().equals(principalApiKeyId)) {
      throw new PubExceptions.BadRequestException("Pub upload session belongs to another API key");
    }
    AssetBlobRecord blob = assets.findBlobByObjectKeyHash(
            session.blobStoreId(), PersistenceHashes.objectKeyHash(session.objectKey()))
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(
            "Pub upload blob is no longer available"));
    String path = PubPaths.archivePath(session.packageName(), session.version());
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("pubSession", session.sessionId());
    attributes.put("pubPackage", session.packageName());
    attributes.put("pubVersion", session.version());
    if (session.pubspec() != null) {
      attributes.put("pubspec", session.pubspec());
    }
    if (session.sha256() != null) {
      attributes.put("archiveSha256", session.sha256());
    }
    attributes.put("publishedAt", Instant.now().toString());
    staging.stageExternalBlob(
        runtime, stagingSession, path, blob.id(), "application/octet-stream", attributes,
        subject, ip);
    uploadSessions.markFinalized(session.id(), Instant.now());
    return pubHosted.publishSuccess(headOnly);
  }
}
