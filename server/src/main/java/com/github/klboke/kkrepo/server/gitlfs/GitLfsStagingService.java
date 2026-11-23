package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.staging.StagingService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stages a Git LFS object upload instead of publishing the live asset immediately. */
@Service
public class GitLfsStagingService {
  private final RawHostedService hosted;
  private final StagingService staging;

  public GitLfsStagingService(RawHostedService hosted, StagingService staging) {
    this.hosted = hosted;
    this.staging = staging;
  }

  @Transactional
  public void put(
      RepositoryRuntime runtime,
      String oid,
      String stagingSession,
      HttpServletRequest request,
      AuthenticatedSubject subject,
      String remoteAddr) throws IOException {
    if (!GitLfsProtocol.validOid(oid)) {
      throw new GitLfsException(422, "Invalid Git LFS object id");
    }
    long contentLength = request.getContentLengthLong();
    if (contentLength < 0) {
      throw new GitLfsException(411, "Content-Length is required for Git LFS uploads");
    }
    String actor = subject == null || subject.userId() == null ? "anonymous" : subject.userId();
    AssetBlobRecord blob;
    try (InputStream body = request.getInputStream()) {
      blob = hosted.storeStagedBlob(
          runtime, "lfs/" + oid, body, "application/octet-stream",
          Map.of("gitLfsOid", oid), actor, remoteAddr);
    }
    if (!oid.equalsIgnoreCase(blob.sha256()) || blob.size() != contentLength) {
      throw new GitLfsException(422, "Git LFS object digest or size mismatch");
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("gitLfsOid", oid);
    attributes.put("gitLfsSize", blob.size());
    staging.stageExternalBlob(
        runtime, stagingSession, "lfs/" + oid, blob.id(), "application/octet-stream",
        attributes, subject == null ? null : subject.permissionSubject(), remoteAddr);
  }
}
