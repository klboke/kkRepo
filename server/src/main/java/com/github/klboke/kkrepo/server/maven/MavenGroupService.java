package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataMerger;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Maven group facet — dispatches binary artifact requests to members in order (first 200 wins)
 * and merges {@code maven-metadata.xml} responses from all members into a unified document.
 * Round 1 only caches merged metadata; binary artifacts are streamed straight from the winning
 * member so the underlying hosted/proxy facet keeps full authority over caching.
 *
 * <p>Nested groups recurse through {@link #get} so member order is the depth-first order seen
 * during traversal — matches Nexus behavior.
 */
@Service
public class MavenGroupService {
  private static final Logger log = LoggerFactory.getLogger(MavenGroupService.class);
  private final MavenHostedService hosted;
  private final MavenProxyService proxy;
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final MavenAssetWriter writer;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final MavenPathParser parser = new MavenPathParser();
  private final MavenMetadataMerger metadataMerger = new MavenMetadataMerger();

  @Autowired
  public MavenGroupService(MavenHostedService hosted, MavenProxyService proxy,
      AssetDao assetDao, BlobStorageRegistry blobStorageRegistry, MavenAssetWriter writer,
      AssetMetadataCache assetMetadataCache, NexusLikeCacheController cacheController) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
  }

  public MavenGroupService(MavenHostedService hosted, MavenProxyService proxy,
      AssetDao assetDao, BlobStorageRegistry blobStorageRegistry, MavenAssetWriter writer,
      AssetMetadataCache assetMetadataCache) {
    this(hosted, proxy, assetDao, blobStorageRegistry, writer, assetMetadataCache, null);
  }

  public MavenResponse get(RepositoryRuntime group, MavenPath path, boolean headOnly) {
    return get(group, path, headOnly, new DispatchedRepositories());
  }

  private MavenResponse get(
      RepositoryRuntime group, MavenPath path, boolean headOnly, DispatchedRepositories dispatched) {
    if (!group.isGroup()) {
      throw new IllegalStateException("MavenGroupService.get called on non-group " + group.name());
    }
    if (group.members().isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (parser.isRepositoryMetadata(path)) {
      return serveMergedMetadata(group, path, headOnly, dispatched);
    }
    return firstWin(group, path, headOnly, dispatched);
  }

  private MavenResponse firstWin(
      RepositoryRuntime group, MavenPath path, boolean headOnly, DispatchedRepositories dispatched) {
    Optional<CachedAssetMetadata> groupCached = cachedGroupContent(group, path, Instant.now());
    if (groupCached.isPresent()) {
      return serveCached(groupCached.get(), headOnly, path);
    }
    for (RepositoryRuntime member : group.members()) {
      if (dispatched.contains(member)) {
        continue;
      }
      dispatched.add(member);
      try {
        MavenResponse response = dispatch(member, path, headOnly, dispatched);
        cacheGroupContentFromMember(group, member, path);
        return response;
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // try next member
      } catch (MavenExceptions.BadUpstreamException e) {
        // Nexus group repositories only return successful member responses; keep probing.
      } catch (MavenExceptions.MethodNotAllowed ignored) {
        // member doesn't serve this kind of read; skip
      }
    }
    throw new MavenExceptions.MavenNotFoundException(path.path());
  }

  private Optional<CachedAssetMetadata> cachedGroupContent(RepositoryRuntime group, MavenPath path, Instant now) {
    if (assetMetadataCache == null || parser.isRepositoryMetadata(path)) {
      return Optional.empty();
    }
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        group.id(),
        path.path(),
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(group.id(), path.path()), assetDao));
    if (cached.isEmpty()) {
      return Optional.empty();
    }
    if (isGroupCacheFresh(group, path, cached.get(), now)) {
      return cached;
    }
    return Optional.empty();
  }

  private boolean isGroupCacheFresh(
      RepositoryRuntime group,
      MavenPath path,
      CachedAssetMetadata snapshot,
      Instant now) {
    int ttl = group.effectiveContentMaxAgeMinutesOrDefault();
    Optional<NexusLikeCacheInfo> cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes());
    if (cacheController != null && cacheInfo.isPresent()) {
      return !cacheController.isStale(group.id(), NexusCacheType.CONTENT, cacheInfo.get(), ttl, now);
    }
    if (snapshot.lastUpdatedAt() == null) {
      return false;
    }
    if (ttl < 0) {
      return true;
    }
    return snapshot.lastUpdatedAt().plusSeconds(ttl * 60L).isAfter(now);
  }

  private void cacheGroupContentFromMember(RepositoryRuntime group, RepositoryRuntime member, MavenPath path) {
    if (assetMetadataCache == null || writer == null || path.isSubordinate() || parser.isRepositoryMetadata(path)) {
      return;
    }
    try {
      Optional<CachedAssetMetadata> memberSnapshot = assetMetadataCache.find(
          member.id(),
          path.path(),
          () -> AssetMetadataCache.Loaded.from(
              assetDao.findAssetByPath(member.id(), path.path()), assetDao));
      memberSnapshot.ifPresent(snapshot ->
          writer.referenceCachedAsset(group, path, snapshot, "group", member.name()));
    } catch (RuntimeException e) {
      log.warn("Failed writing Maven group cache for group {} member {} path {}",
          group.name(), member.name(), path.path(), e);
    }
  }

  private MavenResponse dispatch(
      RepositoryRuntime member, MavenPath path, boolean headOnly, DispatchedRepositories dispatched) {
    return switch (member.type()) {
      case HOSTED -> hosted.get(member, path, headOnly);
      case PROXY -> proxy.get(member, path, headOnly);
      case GROUP -> get(member, path, headOnly, dispatched);
    };
  }

  private MavenResponse serveMergedMetadata(
      RepositoryRuntime group, MavenPath path, boolean headOnly, DispatchedRepositories dispatched) {
    Instant now = Instant.now();
    int ttl = group.effectiveMetadataMaxAgeMinutesOrDefault();
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        group.id(),
        path.path(),
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(group.id(), path.path()), assetDao));
    if (cached.isPresent() && isGroupMetadataFresh(group, cached.get(), ttl, now)) {
      return serveCached(cached.get(), headOnly, path);
    }
    List<byte[]> members = collectMemberMetadata(group, path, dispatched);
    if (members.isEmpty()) {
      if (cached.isPresent()) {
        // members lost their copies; drop the group's cached merge so we don't keep serving it
        long blobStoreId = requireBlobStore(group);
        BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
        writer.deleteAsset(group, storage, path);
      }
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    byte[] merged = metadataMerger.merge(members, now);
    long blobStoreId = requireBlobStore(group);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    MavenAssetWriter.Stored stored = writer.writeBytes(
        group, storage, blobStoreId, path, merged, MavenContentType.XML, "group", group.name());
    AssetRecord asset = stored.asset();
    AssetBlobRecord blob = stored.blob();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), asset.contentType(), blob.sha1(), asset.lastUpdatedAt());
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), asset.contentType(), blob.sha1(), asset.lastUpdatedAt());
  }

  private boolean isGroupMetadataFresh(
      RepositoryRuntime group,
      CachedAssetMetadata snapshot,
      int ttl,
      Instant now) {
    Optional<NexusLikeCacheInfo> cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes());
    if (cacheController != null && cacheInfo.isPresent()) {
      return !cacheController.isStale(group.id(), NexusCacheType.METADATA, cacheInfo.get(), ttl, now);
    }
    if (snapshot.lastUpdatedAt() == null) {
      return false;
    }
    if (ttl < 0) {
      return true;
    }
    return snapshot.lastUpdatedAt().plusSeconds(ttl * 60L).isAfter(now);
  }

  private List<byte[]> collectMemberMetadata(
      RepositoryRuntime group, MavenPath path, DispatchedRepositories dispatched) {
    List<byte[]> result = new ArrayList<>(group.members().size());
    for (RepositoryRuntime member : group.members()) {
      if (dispatched.contains(member)) {
        continue;
      }
      dispatched.add(member);
      InputStream body = null;
      try {
        MavenResponse response = dispatch(member, path, false, dispatched);
        if (!response.hasBody()) {
          continue;
        }
        body = response.body();
        if (body != null) result.add(body.readAllBytes());
      } catch (MavenExceptions.MavenNotFoundException | MavenExceptions.BadUpstreamException
          | MavenExceptions.MethodNotAllowed ignored) {
        // member doesn't have it / is down; skip
      } catch (IOException e) {
        throw new IllegalStateException("Failed reading metadata from member " + member.name(), e);
      } finally {
        if (body != null) {
          try { body.close(); } catch (IOException ignored) {}
        }
      }
    }
    return result;
  }

  private MavenResponse serveCached(CachedAssetMetadata snapshot, boolean headOnly, MavenPath path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) throw new MavenExceptions.MavenNotFoundException(path.path());
    String etag = blob.sha1();
    Instant lastModified = snapshot.lastUpdatedAt();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), snapshot.contentType(), etag, lastModified);
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(
            BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), snapshot.contentType(), etag, lastModified);
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Group " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static final class DispatchedRepositories {
    private final Set<Long> repositoryIds = new HashSet<>();

    boolean contains(RepositoryRuntime runtime) {
      return repositoryIds.contains(runtime.id());
    }

    void add(RepositoryRuntime runtime) {
      repositoryIds.add(runtime.id());
    }
  }

  // workaround: ByteArrayOutputStream import retained for potential future capture path
  @SuppressWarnings("unused")
  private static byte[] drain(InputStream in) throws IOException {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      in.transferTo(out);
      return out.toByteArray();
    }
  }
}
