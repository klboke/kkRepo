package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.protocol.maven.policy.LayoutPolicy;
import com.github.klboke.kkrepo.protocol.maven.policy.LayoutPolicyValidator;
import com.github.klboke.kkrepo.protocol.maven.policy.VersionPolicy;
import com.github.klboke.kkrepo.protocol.maven.policy.VersionPolicyValidator;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicyGate;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hosted Maven facet — owns GET/HEAD/PUT/DELETE against assets backed by this repository's own
 * blob store. No proxy fetch, no group fan-out. Ordinary repository PUT mirrors Nexus by storing
 * only the requested path; component-upload and maintenance paths opt into generated checksum and
 * metadata work.
 */
@Service
public class MavenHostedService {
  private final MavenPathParser parser = new MavenPathParser();
  private final LayoutPolicyValidator layoutValidator = new LayoutPolicyValidator(parser);
  private final VersionPolicyValidator versionValidator = new VersionPolicyValidator();
  private final WritePolicyGate writeGate = new WritePolicyGate(parser);
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final MavenAssetWriter writer;
  private final MavenMetadataService metadataService;
  private final MetadataRebuildDao metadataRebuildDao;
  private final AssetMetadataCache assetMetadataCache;
  private final boolean syncMetadataRebuild;

  public MavenHostedService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      MavenMetadataService metadataService,
      MetadataRebuildDao metadataRebuildDao,
      AssetMetadataCache assetMetadataCache,
      @Value("${kkrepo.maven.sync-metadata-rebuild:false}") boolean syncMetadataRebuild) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.metadataService = metadataService;
    this.metadataRebuildDao = metadataRebuildDao;
    this.assetMetadataCache = assetMetadataCache;
    this.syncMetadataRebuild = syncMetadataRebuild;
  }

  public MavenPathParser parser() {
    return parser;
  }

  public MavenResponse get(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
    CachedAssetMetadata snapshot = assetMetadataCache.find(
        runtime.id(),
        path.path(),
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(runtime.id(), path.path()), assetDao))
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path()));
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    BlobReference ref = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    String etag = blob.sha1();
    Instant lastModified = snapshot.lastUpdatedAt();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), snapshot.contentType(), etag, lastModified);
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(ref)
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), snapshot.contentType(), etag, lastModified);
  }

  public MavenResponse put(RepositoryRuntime runtime, MavenPath path, InputStream body,
      String contentTypeHint, String createdBy, String createdByIp) {
    return put(runtime, path, body, contentTypeHint, createdBy, createdByIp, false, false);
  }

  public MavenResponse put(RepositoryRuntime runtime, MavenPath path, InputStream body,
      String contentTypeHint, String createdBy, String createdByIp, boolean writeChecksumSiblings) {
    return put(runtime, path, body, contentTypeHint, createdBy, createdByIp,
        writeChecksumSiblings, writeChecksumSiblings);
  }

  public MavenResponse put(RepositoryRuntime runtime, MavenPath path, InputStream body,
      String contentTypeHint, String createdBy, String createdByIp,
      boolean writeChecksumSiblings, boolean rebuildMetadata) {
    enforceWriteableMethod(runtime);
    enforceLayout(runtime, path);
    enforceVersion(runtime, path);
    Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), path.path());
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (!writeGate.canWrite(policy, path, existing.isPresent())) {
      throw new MavenExceptions.WritePolicyDenied(
          "Write policy " + policy + " forbids writing " + path.path());
    }
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    writer.writePrimary(
        runtime, storage, blobStoreId, path, body, contentTypeHint, createdBy, createdByIp,
        java.util.Map.of(), writeChecksumSiblings);
    cancelPendingRebuildForExplicitMetadata(runtime, path);
    if (rebuildMetadata) {
      rebuildMetadataAfterChange(runtime, storage, blobStoreId, path, createdBy, createdByIp);
    }
    return MavenResponse.created();
  }

  public MavenResponse delete(RepositoryRuntime runtime, MavenPath path) {
    enforceWriteableMethod(runtime);
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (!writeGate.canDelete(policy, path)) {
      throw new MavenExceptions.WritePolicyDenied(
          "Write policy " + policy + " forbids deleting " + path.path());
    }
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    int deleted = writer.deleteAsset(runtime, storage, path);
    if (deleted == 0) {
      return MavenResponse.noBody(404);
    }
    rebuildMetadataAfterChange(runtime, storage, blobStoreId, path, "system", null);
    return MavenResponse.noBody(204);
  }

  private void rebuildMetadataAfterChange(
      RepositoryRuntime runtime, BlobStorage storage, long blobStoreId,
      MavenPath path, String createdBy, String createdByIp) {
    if (path.isHash()) return;
    if (parser.isRepositoryMetadata(path)) return;
    Coordinates coords = path.main().coordinates();
    if (coords == null) return;
    if (syncMetadataRebuild) {
      metadataService.rebuildGa(runtime, storage, blobStoreId,
          coords.groupId(), coords.artifactId(), createdBy, createdByIp);
      if (coords.snapshot()) {
        metadataService.rebuildBaseVersionIfSnapshot(
            runtime, storage, blobStoreId, coords, createdBy, createdByIp);
      }
      return;
    }
    // Async path: enqueue once per (repo, GA) and once per (repo, GAV-SNAPSHOT). The marker
    // primary key dedupes burst PUTs for the same artifact (e.g. jar + pom + sources + javadoc
    // deployed back-to-back collapse to one rebuild). MetadataRebuildWorker drains and runs.
    metadataRebuildDao.enqueue(runtime.id(), MetadataRebuildScope.ga(coords.groupId(), coords.artifactId()));
    if (coords.snapshot()) {
      metadataRebuildDao.enqueue(runtime.id(),
          MetadataRebuildScope.gav(coords.groupId(), coords.artifactId(), coords.baseVersion()));
    }
  }

  private void cancelPendingRebuildForExplicitMetadata(RepositoryRuntime runtime, MavenPath path) {
    if (!parser.isRepositoryMetadata(path)) return;
    MetadataPath metadata = MetadataPath.from(path.path());
    if (metadata == null) return;
    if (metadata.baseVersion() != null) {
      metadataRebuildDao.delete(runtime.id(),
          MetadataRebuildScope.gav(metadata.groupId(), metadata.artifactId(), metadata.baseVersion()));
      return;
    }
    metadataRebuildDao.delete(runtime.id(),
        MetadataRebuildScope.ga(metadata.groupId(), metadata.artifactId()));
  }

  private void enforceLayout(RepositoryRuntime runtime, MavenPath path) {
    LayoutPolicy policy = LayoutPolicy.parse(runtime.layoutPolicy());
    if (!layoutValidator.isLayoutCompliant(policy, path)) {
      throw new MavenExceptions.LayoutPolicyViolation(
          "Layout policy " + policy + " rejects " + path.path());
    }
  }

  private void enforceVersion(RepositoryRuntime runtime, MavenPath path) {
    VersionPolicy policy = VersionPolicy.parse(runtime.versionPolicy());
    if (!versionValidator.isVersionCompliant(policy, path)) {
      throw new MavenExceptions.VersionPolicyViolation(
          "Version policy " + policy + " rejects " + path.path());
    }
  }

  private void enforceWriteableMethod(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Write methods are only valid on hosted repositories");
    }
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private record MetadataPath(String groupId, String artifactId, String baseVersion) {
    private static MetadataPath from(String path) {
      String suffix = "/maven-metadata.xml";
      if (path == null || !path.endsWith(suffix)) return null;
      String base = path.substring(0, path.length() - suffix.length());
      if (base.isBlank()) return null;
      String[] parts = base.split("/");
      if (parts.length < 2) return null;
      String baseVersion = parts[parts.length - 1];
      if (parts.length >= 3 && baseVersion.endsWith("SNAPSHOT")) {
        return new MetadataPath(
            joinGroup(parts, parts.length - 2),
            parts[parts.length - 2],
            baseVersion);
      }
      return new MetadataPath(
          joinGroup(parts, parts.length - 1),
          parts[parts.length - 1],
          null);
    }

    private static String joinGroup(String[] parts, int exclusiveEnd) {
      StringBuilder groupId = new StringBuilder();
      for (int i = 0; i < exclusiveEnd; i++) {
        if (i > 0) groupId.append('.');
        groupId.append(parts[i]);
      }
      return groupId.toString();
    }
  }
}
