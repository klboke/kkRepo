package com.github.klboke.kkrepo.server.routing.gitlfs;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.plugin.api.RepositoryContext;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsBatchResult;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsByteRange;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsHostOperations;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsStoredObject;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.config.RepositoryUrlPrefix;
import com.github.klboke.kkrepo.server.gitlfs.GitLfsAccess;
import com.github.klboke.kkrepo.server.gitlfs.GitLfsHostedService;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.offload.DownloadOffload;
import com.github.klboke.kkrepo.server.routing.PluginRepositoryContext;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Host side of the Git LFS plugin. Each operation unwraps the routed request from the plugin
 * context, derives the caller identity from what the security filter authenticated, and delegates
 * to the existing hosted service, download policy and blob storage.
 */
@Component
public class ServerGitLfsHostOperations implements GitLfsHostOperations {
  private static final String RESOLVED_BLOB_ATTRIBUTE =
      ServerGitLfsHostOperations.class.getName() + ".blob.";
  private final GitLfsHostedService service;
  private final GitLfsAccess access;
  private final BlobStorageRegistry storages;
  private final ArtifactDownloadPolicy downloads;
  private final ForwardedHeaderPolicy forwarded;
  private DownloadOffload downloadOffload;

  public ServerGitLfsHostOperations(GitLfsHostedService service, GitLfsAccess access,
      BlobStorageRegistry storages, ArtifactDownloadPolicy downloads, ForwardedHeaderPolicy forwarded) {
    this.service = service;
    this.access = access;
    this.storages = storages;
    this.downloads = downloads;
    this.forwarded = forwarded;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDownloadOffload(DownloadOffload downloadOffload) {
    this.downloadOffload = downloadOffload;
  }

  @Override
  public long maxObjectBytes() {
    return access.maxObjectBytes();
  }

  @Override
  public GitLfsBatchResult batch(RepositoryContext repository, GitLfsProtocol.Batch batch) {
    PluginRepositoryContext host = PluginRepositoryContext.of(repository);
    HttpServletRequest request = host.servletRequest();
    // The security filter authorized the operation of the Batch it parsed; only that Batch is served.
    if (!(request.getAttribute(GitLfsAccess.BATCH_ATTRIBUTE) instanceof GitLfsProtocol.Batch authorized)) {
      throw new GitLfsException(422, "Missing Batch request");
    }
    if (!authorized.equals(batch)) {
      throw new GitLfsException(422, "Batch request does not match the authorized request");
    }
    String base = forwarded.serverBaseUrl(request) + request.getContextPath()
        + RepositoryUrlPrefix.path(request) + host.runtime().name() + "/";
    return service.batch(host.runtime(), batch, access.caller(request), base);
  }

  @Override
  public void verify(RepositoryContext repository, String oid, long size) {
    service.verify(PluginRepositoryContext.of(repository).runtime(), oid, size);
  }

  @Override
  public void upload(RepositoryContext repository, String oid, String uploadId, long contentLength,
      InputStream body) throws IOException {
    PluginRepositoryContext host = PluginRepositoryContext.of(repository);
    service.put(host.runtime(), oid, uploadId, contentLength, body, access.caller(host.servletRequest()));
  }

  @Override
  public GitLfsStoredObject find(RepositoryContext repository, String oid) {
    PluginRepositoryContext host = PluginRepositoryContext.of(repository);
    var asset = service.asset(host.runtime().id(), oid);
    var blob = service.blob(asset);
    downloads.beforeReadFromRepository(asset.id(), blob.id(), host.runtime().id());
    // The blob reference stays in the host; open() accepts only objects resolved for this request.
    host.servletRequest().setAttribute(RESOLVED_BLOB_ATTRIBUTE + oid, blob);
    return new GitLfsStoredObject(oid, blob.size(), blob.sha1(), asset.lastUpdatedAt());
  }

  @Override
  public InputStream open(RepositoryContext repository, GitLfsStoredObject object, GitLfsByteRange range)
      throws IOException {
    PluginRepositoryContext host = PluginRepositoryContext.of(repository);
    if (!(host.servletRequest().getAttribute(RESOLVED_BLOB_ATTRIBUTE + object.oid())
        instanceof AssetBlobRecord blob) || blob.size() != object.size()) {
      throw new IllegalStateException("Git LFS object was not resolved for this request: " + object.oid());
    }
    var storage = storages.forBlobStoreId(blob.blobStoreId());
    var reference = BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    return (range == null ? storage.get(reference) : storage.getRange(reference, range.start(), range.length()))
        .orElseThrow(() -> new GitLfsException(404, "Object content is unavailable"));
  }

  @Override
  public boolean offload(RepositoryContext repository, int status,
      Map<String, List<String>> headers, InputStream body) {
    if (downloadOffload == null) return false;
    HttpServletRequest request = PluginRepositoryContext.of(repository).servletRequest();
    HttpHeaders springHeaders = new HttpHeaders();
    headers.forEach((name, values) -> {
      if (name != null && values != null) springHeaders.put(name, new ArrayList<>(values));
    });
    boolean handed = downloadOffload.tryHandoff(request, status, springHeaders, body);
    if (handed) {
      headers.clear();
      springHeaders.forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
    }
    return handed;
  }
}
