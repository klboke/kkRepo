package com.github.klboke.kkrepo.server.gitlfs;

import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.*;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsException;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsProtocol;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsBatchResult;
import com.github.klboke.kkrepo.protocol.gitlfs.GitLfsBatchResult.ObjectResult;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import jakarta.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hosted Git LFS state: upload leases and quotas, verified blob writes, publication transactions,
 * audit identity and deletion. Protocol parsing and HTTP rendering belong to the Git LFS plugin;
 * this service only takes neutral, already parsed values.
 */
@Service
public class GitLfsHostedService {
  /** Lifetime of a Batch upload action, matching the lease issued by {@link GitLfsDao#create}. */
  static final long UPLOAD_ACTION_SECONDS = 900;
  private final AssetDao assets;
  private final ComponentDao components;
  private final BrowseNodeDao browse;
  private final GitLfsDao uploads;
  private final BlobStorageRegistry storages;
  private final GitLfsAccess access;
  private final TransactionTemplate transactions;
  // Execution resources only; all ownership, expiry and publication truth is in the database.
  private final Semaphore slots;
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name("gitlfs-upload-heartbeat").factory());

  public GitLfsHostedService(AssetDao assets, ComponentDao components, BrowseNodeDao browse,
      GitLfsDao uploads, BlobStorageRegistry storages, GitLfsAccess access,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.gitlfs.concurrent-uploads:4}") int concurrentUploads) {
    if (concurrentUploads < 1) throw new IllegalArgumentException("Git LFS upload concurrency must be positive");
    this.assets = assets;
    this.components = components;
    this.browse = browse;
    this.uploads = uploads;
    this.storages = storages;
    this.access = access;
    this.transactions = new TransactionTemplate(transactionManager);
    this.slots = new Semaphore(concurrentUploads);
  }

  @PreDestroy void close() { heartbeat.shutdownNow(); }

  /**
   * Decides each Batch object for {@code caller}: per-object permission, write policy, stored size,
   * upload lease or download availability. Repeated OIDs share the decision of their first valid
   * occurrence, so one lease is issued per object.
   *
   * @param objectBaseUrl public repository URL, ending with {@code /}, for the object actions
   */
  public GitLfsBatchResult batch(RepositoryRuntime repository, GitLfsProtocol.Batch batch,
      GitLfsCaller caller, String objectBaseUrl) {
    var subject = caller.subject();
    List<String> oids = batch.objects().stream().filter(GitLfsProtocol.ObjectRequest::valid)
        .map(GitLfsProtocol.ObjectRequest::oid).distinct().toList();
    Set<String> allowed = access.allowedObjects(subject, repository.name(), oids,
        batch.upload() ? PermissionAction.ADD : PermissionAction.READ);
    Map<String, AssetRecord> existing = assets.findAssetsByPaths(repository.id(), oids);
    List<ObjectResult> results = new ArrayList<>();
    Map<String, ObjectResult> repeated = new HashMap<>();
    for (var object : batch.objects()) {
      results.add(decide(repository, batch, object, allowed, existing, repeated, subject));
    }
    if (caller.anonymous() && !results.isEmpty()
        && results.stream().allMatch(result -> result.isError() && result.errorCode() == 403)) {
      throw new GitLfsException(401, "Authentication required");
    }
    return new GitLfsBatchResult(objectBaseUrl, results);
  }

  private ObjectResult decide(RepositoryRuntime repository, GitLfsProtocol.Batch batch,
      GitLfsProtocol.ObjectRequest object, Set<String> allowed, Map<String, AssetRecord> existing,
      Map<String, ObjectResult> repeated, AuthenticatedSubject subject) {
    String oid = object.oid();
    long size = object.size();
    if (!object.valid()) return ObjectResult.error(oid, size, 422, object.error());
    if (!allowed.contains(oid)) return ObjectResult.error(oid, size, 403, "Permission denied");
    if (batch.upload() && "DENY".equalsIgnoreCase(repository.writePolicy())) {
      return ObjectResult.error(oid, size, 403, "Repository is read only");
    }
    ObjectResult earlier = repeated.get(oid);
    if (earlier != null) {
      return earlier.size() == size ? earlier : ObjectResult.error(oid, size, 422, "Conflicting object sizes");
    }
    ObjectResult result;
    AssetRecord asset = existing.get(oid);
    if (asset != null && !Objects.equals(asset.size(), size)) {
      result = ObjectResult.error(oid, size, 422, "Object size does not match stored content");
    } else if (batch.upload()) {
      result = asset != null
          ? ObjectResult.present(oid, size)
          : ObjectResult.upload(oid, size,
              createUpload(repository, oid, size, GitLfsAccess.subjectKey(subject)).id(), UPLOAD_ACTION_SECONDS);
    } else {
      result = asset == null
          ? ObjectResult.error(oid, size, 404, "Object does not exist on the server")
          : ObjectResult.download(oid, size);
    }
    repeated.put(oid, result);
    return result;
  }

  /**
   * Streams the bytes of an upload action. The lease owner and permission are checked again before
   * and after the transfer; size and SHA-256 are verified while streaming.
   *
   * @param uploadId value of the upload action header, or {@code null}
   * @param contentLength declared request length, or {@code -1} when unknown
   */
  public void put(RepositoryRuntime repository, String oid, String uploadId, long contentLength,
      InputStream body, GitLfsCaller caller) throws IOException {
    String id = uploadId;
    if (id == null || !id.matches("[0-9a-f-]{36}")) throw new GitLfsException(422, "Obtain an upload action through Batch first");
    var initial = uploads.find(id).orElseThrow(() -> new GitLfsException(410, "Upload action expired"));
    String owner = GitLfsAccess.subjectKey(caller.subject());
    if (initial.repositoryId() != repository.id() || !initial.oid().equals(oid) || !initial.subjectKey().equals(owner)) {
      throw new GitLfsException(403, "Upload action does not belong to this request");
    }
    access.requireUpload(caller, repository.name(), oid, owner);
    if ("DENY".equalsIgnoreCase(repository.writePolicy())) {
      throw new GitLfsException(403, "Repository is read only");
    }
    if (contentLength >= 0 && contentLength != initial.size()) {
      throw new GitLfsException(422, "Content-Length does not match Batch size");
    }
    if (!slots.tryAcquire()) throw new GitLfsException(429, "Upload capacity exhausted; retry later");
    try {
      BlobStorage storage = storages.forBlobStoreId(initial.blobStoreId());
      BlobReference target = storage.prepareVerifiedUpload(repository.name(), oid, initial.size());
      // Safe replay after a lost success response: validate bytes, never replace an immutable asset.
      if ("PUBLISHED".equals(initial.state()) && assets.findAssetByPath(repository.id(), oid).isPresent()) {
        VerifiedBlobReader reader = new VerifiedBlobReader(target, body);
        while (reader.nextPart().length > 0) { }
        reader.finish();
        return;
      }
      var upload = uploads.claim(id, owner, BlobReferenceCodec.format(target), target.objectKey())
          .orElseThrow(() -> new GitLfsException(409, "Upload action is stale or another upload is active; repeat Batch"));
      transfer(repository, upload, target, storage, body, caller.subject().userId(), caller.clientAddress(),
          () -> access.requireUpload(caller, repository.name(), oid, owner));
    } catch (BlobIntegrityException invalid) {
      throw new GitLfsException(422, invalid.getMessage());
    } finally {
      slots.release();
    }
  }

  /** Privileged migration replay, with exactly the same streaming verifier and publication fence. */
  public AssetRecord restore(RepositoryRuntime repository, String oid, long size, InputStream body,
      String user, String ip) {
    if (repository.format() != RepositoryFormat.GITLFS || !repository.isHosted()
        || !GitLfsProtocol.validOid(oid) || size < 0 || size > access.maxObjectBytes()) {
      throw new IllegalArgumentException("Unsupported Git LFS migration identity or size");
    }
    if (!slots.tryAcquire()) throw new GitLfsException(429, "Upload capacity exhausted; retry later");
    try {
      // Resume never replaces content; the persisted SHA-256 and size must match the source identity.
      var prior = assets.findAssetByPath(repository.id(), oid);
      if (prior.isPresent()) {
        var existing = blob(prior.get());
        if (!oid.equals(existing.sha256()) || size != existing.size()) throw new IllegalArgumentException("LFS migration identity conflict");
        return prior.get();
      }
      BlobStorage storage = storages.forBlobStoreId(repository.blobStoreId());
      BlobReference target = storage.prepareVerifiedUpload(repository.name(), oid, size);
      String owner = "privileged-nexus-migration";
      var ready = createUpload(repository, oid, size, owner);
      var upload = uploads.claim(ready.id(), owner, BlobReferenceCodec.format(target), target.objectKey())
          .orElseThrow(() -> new GitLfsException(409, "Another LFS upload is active"));
      transfer(repository, upload, target, storage, body, user, ip, () -> {});
      return asset(repository.id(), oid);
    } finally {
      slots.release();
    }
  }

  private void transfer(RepositoryRuntime repository, GitLfsDao.Upload upload, BlobReference target,
      BlobStorage storage, InputStream body, String user, String ip, Runnable reauthorize) {
    String id = upload.id();
    AtomicBoolean lost = new AtomicBoolean();
    ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
      try { if (!uploads.renew(id, upload.fence())) lost.set(true); }
      catch (RuntimeException failure) { lost.set(true); }
    }, 30, 30, TimeUnit.SECONDS);
    try {
      InputStream input = new FilterInputStream(body) {
        private void check() { if (lost.get()) throw new GitLfsException(409, "Upload ownership lost"); }
        @Override public int read() throws IOException { check(); return super.read(); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
          check(); return in.read(bytes, offset, length);
        }
      };
      VerifiedBlobDigests digests = storage.uploadVerified(target, input,
          handle -> uploads.multipartStarted(id, upload.fence(), handle));
      reauthorize.run();
      if (lost.get()) throw new GitLfsException(409, "Upload ownership lost");
      boolean published = Boolean.TRUE.equals(transactions.execute(status -> publish(repository, upload,
          target, digests, user, ip)));
      if (!published) uploads.abandon(id, upload.fence());
    } catch (RuntimeException error) {
      try { uploads.abandon(id, upload.fence()); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
      throw error;
    } finally {
      renewal.cancel(false);
    }
  }

  private GitLfsDao.Upload createUpload(RepositoryRuntime repository, String oid, long size, String owner) {
    try {
      return uploads.create(repository.id(), oid, size, owner, repository.blobStoreId());
    } catch (GitLfsRepositoryStateException changed) {
      throw switch (changed.reason()) {
        case MISSING -> new GitLfsException(404, "Repository does not exist");
        case OFFLINE -> new GitLfsException(503, "Repository is offline");
        case READ_ONLY -> new GitLfsException(403, "Repository is read only");
        case CONFIGURATION_CHANGED -> new GitLfsException(409, "Repository configuration changed; repeat Batch");
      };
    }
  }

  private boolean publish(RepositoryRuntime repository, GitLfsDao.Upload upload,
      BlobReference target, VerifiedBlobDigests digests, String user, String ip) {
    if (!uploads.lockForPublication(upload.id(), upload.fence())) throw new GitLfsException(409, "Upload action expired or repository changed");
    var prior = assets.findAssetByPath(repository.id(), upload.oid());
    if (prior.isPresent()) {
      if (!Objects.equals(prior.get().size(), digests.size())) throw new GitLfsException(422, "Object size conflict");
      return false;
    }
    Instant now = Instant.now();
    String reference = BlobReferenceCodec.format(target);
    AssetBlobRecord blob = assets.insertBlobOrFindExisting(new AssetBlobRecord(null,
        upload.blobStoreId(), reference, PersistenceHashes.blobRefHash(reference), target.objectKey(),
        PersistenceHashes.objectKeyHash(target.objectKey()), digests.sha1(), digests.sha256(), digests.md5(),
        digests.size(), "application/octet-stream", user, ip, now, now, Map.of()));
    long component = components.upsertReturningId(new ComponentRecord(null, repository.id(), RepositoryFormat.GITLFS,
        "", upload.oid(), "", "gitlfs", PersistenceHashes.componentCoordinateHash("", upload.oid(), ""),
        Map.of("oid", upload.oid()), now));
    long asset = assets.insertAsset(new AssetRecord(null, repository.id(), component, blob.id(), RepositoryFormat.GITLFS,
        upload.oid(), PersistenceHashes.pathHash(upload.oid()), upload.oid(), "gitlfs", "application/octet-stream",
        digests.size(), null, now, Map.of("oid", upload.oid())));
    browse.upsertPathAncestors(repository.id(), upload.oid(), asset, component);
    uploads.published(upload.id(), upload.fence(), asset);
    return true;
  }

  /** Confirms that {@code oid} is published with {@code size} bytes. */
  public void verify(RepositoryRuntime repository, String oid, long size) {
    AssetRecord asset = asset(repository.id(), oid);
    if (!Objects.equals(asset.size(), size)) {
      throw new GitLfsException(422, "Object size does not match stored content");
    }
  }

  /** Management deletion unlinks metadata; FK tombstones fence old uploads and generic GC owns bytes. */
  public int deleteById(long repositoryId, long assetId) {
    return transactions.execute(status -> {
      var asset = assets.findAssetById(assetId)
          .filter(row -> row.repositoryId() == repositoryId && row.format() == RepositoryFormat.GITLFS);
      if (asset.isEmpty()) return 404;
      var stored = asset.get();
      browse.deleteByAssetId(assetId);
      if (assets.deleteAssetById(assetId) == 0) return 404;
      if (stored.componentId() != null) components.deleteIfNoAssets(stored.componentId());
      if (stored.assetBlobId() != null) assets.markBlobDeletedIfUnreferenced(stored.assetBlobId(), "Git LFS asset unlinked");
      return 204;
    });
  }

  public AssetRecord asset(long repositoryId, String oid) {
    return assets.findAssetByPath(repositoryId, oid)
        .orElseThrow(() -> new GitLfsException(404, "Object does not exist on the server"));
  }

  public AssetBlobRecord blob(AssetRecord asset) {
    if (asset.assetBlobId() == null) throw new GitLfsException(404, "Object content is unavailable");
    return assets.findBlobById(asset.assetBlobId()).orElseThrow(() -> new GitLfsException(404, "Object content is unavailable"));
  }

}
