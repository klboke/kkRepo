package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
import com.github.klboke.kkrepo.protocol.helm.HelmIndex;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Nexus-compatible ordered aggregation for classic Helm chart repositories. */
@Service
public class HelmGroupService {
  static final int MAX_AGGREGATED_INDEX_BYTES = 64 * 1024 * 1024;

  private final HelmHostedService hosted;
  private final HelmProxyService proxy;
  private final HelmGroupIndexCache indexCache;
  private final GroupMemberAssetCache memberAssetCache;
  private final BlobStorageRegistry blobStorageRegistry;
  private final HelmAssetWriter writer;
  private final HelmAssetReader reader;

  public HelmGroupService(
      HelmHostedService hosted,
      HelmProxyService proxy,
      HelmGroupIndexCache indexCache,
      GroupMemberAssetCache memberAssetCache,
      BlobStorageRegistry blobStorageRegistry,
      HelmAssetWriter writer,
      HelmAssetReader reader) {
    this.hosted = hosted;
    this.proxy = proxy;
    this.indexCache = indexCache;
    this.memberAssetCache = memberAssetCache;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
  }

  public MavenResponse get(RepositoryRuntime group, String rawPath, boolean headOnly) {
    ensureGroup(group);
    String path = HelmHostedService.normalizePath(rawPath);
    HelmAssetKind kind = readableKind(path);
    return kind == HelmAssetKind.INDEX
        ? getIndex(group, headOnly, new HashSet<>()).response()
        : getAsset(group, path, kind, headOnly, new HashSet<>());
  }

  private IndexResult getIndex(
      RepositoryRuntime group, boolean headOnly, Set<Long> resolvingGroups) {
    if (!resolvingGroups.add(group.id())) {
      throw new MavenExceptions.MethodNotAllowed(
          "Cyclic Helm group repository membership: " + group.name());
    }
    try {
      Instant now = Instant.now();
      if (indexCache != null) {
        Optional<CachedAssetMetadata> cached = indexCache.findFresh(group, now);
        if (cached.isPresent()) {
          return new IndexResult(
              reader.serveSnapshot(
                  cached.orElseThrow(), headOnly, HelmHostedService.INDEX_PATH),
              true);
        }
      }

      NexusLikeCacheInfo cacheInfo = indexCache == null ? null : indexCache.current(group, now);
      MemberIndexes memberIndexes = memberIndexes(group, resolvingGroups);
      byte[] body = HelmIndex.mergeGroupIndexes(memberIndexes.indexes(), now);
      ensureIndexWithinLimit(body.length);
      // A partial response is still useful, but no replica may publish it as fresh under the
      // shared watermark and thereby hide a recovered member until metadata expiry.
      if (!memberIndexes.complete() || indexCache == null || !indexCache.enabled()) {
        return new IndexResult(
            indexResponse(body, headOnly, null, now), memberIndexes.complete());
      }

      long blobStoreId = requireBlobStore(group);
      BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
      HelmAssetWriter.Stored stored = writer.writeBytes(
          group,
          storage,
          blobStoreId,
          HelmHostedService.INDEX_PATH,
          body,
          HelmIndex.CONTENT_TYPE,
          HelmAssetKind.INDEX,
          null,
          indexCache.freshAttributes(cacheInfo),
          java.util.Map.of(),
          "group",
          null);
      reader.beforeRead(stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
      return new IndexResult(
          indexResponse(body, headOnly, stored.blob().sha1(), stored.asset().lastUpdatedAt()),
          true);
    } finally {
      resolvingGroups.remove(group.id());
    }
  }

  private MemberIndexes memberIndexes(
      RepositoryRuntime group, Set<Long> resolvingGroups) {
    List<byte[]> indexes = new ArrayList<>();
    boolean complete = true;
    int total = 0;
    for (RepositoryRuntime member : group.members()) {
      if (!eligible(member)) continue;
      MavenResponse response;
      try {
        if (member.isGroup()) {
          IndexResult nested = getIndex(member, false, resolvingGroups);
          response = nested.response();
          complete &= nested.complete();
        } else {
          response = member.isHosted()
              ? hosted.get(member, HelmHostedService.INDEX_PATH, false)
              : proxy.get(member, HelmHostedService.INDEX_PATH, false);
          if (member.isProxy() && Boolean.FALSE.equals(
              response.internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE))) {
            complete = false;
          }
        }
      } catch (MavenExceptions.MavenNotFoundException ignored) {
        // Proxy 404s are negative-cached for a much shorter interval than a durable group index.
        // Serve the healthy subset, but do not publish it as complete under the group watermark.
        complete = false;
        continue;
      } catch (MavenExceptions.BadUpstreamException
          | MavenExceptions.MethodNotAllowed ignored) {
        complete = false;
        continue;
      }
      byte[] body;
      try {
        body = readBounded(response, MAX_AGGREGATED_INDEX_BYTES - total);
      } catch (IOException e) {
        complete = false;
        continue;
      }
      // Isolate an invalid upstream/member index instead of making every healthy member unusable.
      try {
        HelmIndex.entries(body);
      } catch (RuntimeException ignored) {
        complete = false;
        continue;
      }
      total += body.length;
      indexes.add(body);
    }
    return new MemberIndexes(indexes, complete);
  }

  private MavenResponse getAsset(
      RepositoryRuntime group,
      String path,
      HelmAssetKind kind,
      boolean headOnly,
      Set<Long> resolvingGroups) {
    if (!resolvingGroups.add(group.id())) {
      throw new MavenExceptions.MethodNotAllowed(
          "Cyclic Helm group repository membership: " + group.name());
    }
    // Chart archives and their provenance siblings share the content winner token. The Helm
    // group index has its own METADATA watermark and must not be expired by a package cache fill.
    NexusCacheType cacheType = NexusCacheType.CONTENT;
    try {
      HelmIndex.Release release = advertisedRelease(group, path);
      Optional<Long> cachedMemberId = memberAssetCache == null
          ? Optional.empty()
          : memberAssetCache.get(group, path, cacheType);
      if (cachedMemberId.isPresent()) {
        RepositoryRuntime cachedMember = group.members().stream()
            .filter(HelmGroupService::eligible)
            .filter(member -> member.id() == cachedMemberId.orElseThrow())
            .findFirst()
            .orElse(null);
        if (cachedMember != null) {
          try {
            if (memberAdvertises(cachedMember, release, path)) {
              MavenResponse response =
                  dispatchAsset(cachedMember, path, kind, headOnly, resolvingGroups);
              if (matchesAdvertisedDigest(cachedMember, kind, release, response)) {
                return response;
              }
              response.closeBodyIfOpen();
            }
          } catch (MavenExceptions.MavenNotFoundException
              | MavenExceptions.BadUpstreamException
              | MavenExceptions.MethodNotAllowed ignored) {
            // Re-resolve against the authoritative merged-index release below.
          }
          memberAssetCache.evict(group, path, cacheType);
        }
      }

      for (RepositoryRuntime member : group.members()) {
        if (!eligible(member)) continue;
        if (cachedMemberId.isPresent() && member.id() == cachedMemberId.orElseThrow()) continue;
        if (!memberAdvertises(member, release, path)) continue;
        try {
          MavenResponse response = dispatchAsset(member, path, kind, headOnly, resolvingGroups);
          if (!matchesAdvertisedDigest(member, kind, release, response)) {
            response.closeBodyIfOpen();
            continue;
          }
          if (memberAssetCache != null) {
            memberAssetCache.put(group, path, cacheType, member.id());
          }
          return response;
        } catch (MavenExceptions.MavenNotFoundException
            | MavenExceptions.BadUpstreamException
            | MavenExceptions.MethodNotAllowed ignored) {
          // Continue in configured member order until one member serves the path.
        }
      }
      throw new MavenExceptions.MavenNotFoundException(path);
    } finally {
      resolvingGroups.remove(group.id());
    }
  }

  private HelmIndex.Release advertisedRelease(RepositoryRuntime group, String path) {
    try {
      IndexResult index = getIndex(group, false, new HashSet<>());
      byte[] body = readBounded(index.response(), MAX_AGGREGATED_INDEX_BYTES);
      return HelmIndex.releaseForPath(body, path)
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException(
          "Failed reading Helm group index for " + group.name(), e);
    } catch (MavenExceptions.MavenNotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid Helm group index for " + group.name(), e);
    }
  }

  private boolean memberAdvertises(
      RepositoryRuntime member, HelmIndex.Release release, String path) {
    try {
      MavenResponse response = member.isGroup()
          ? getIndex(member, false, new HashSet<>()).response()
          : member.isHosted()
              ? hosted.get(member, HelmHostedService.INDEX_PATH, false)
              : proxy.get(member, HelmHostedService.INDEX_PATH, false);
      byte[] body = readBounded(response, MAX_AGGREGATED_INDEX_BYTES);
      return HelmIndex.containsRelease(body, release, path);
    } catch (IOException
        | MavenExceptions.MavenNotFoundException
        | MavenExceptions.BadUpstreamException
        | MavenExceptions.MethodNotAllowed ignored) {
      return false;
    } catch (RuntimeException ignored) {
      // A malformed member index cannot be trusted to serve the release selected by the group.
      return false;
    }
  }

  private MavenResponse dispatchAsset(
      RepositoryRuntime member,
      String path,
      HelmAssetKind kind,
      boolean headOnly,
      Set<Long> resolvingGroups) {
    return switch (member.type()) {
      case HOSTED -> hosted.get(member, path, headOnly);
      case PROXY -> proxy.get(member, path, headOnly);
      case GROUP -> getAsset(member, path, kind, headOnly, resolvingGroups);
    };
  }

  private static boolean matchesAdvertisedDigest(
      RepositoryRuntime member,
      HelmAssetKind kind,
      HelmIndex.Release release,
      MavenResponse response) {
    // A hosted member is authoritative for both its generated index and stored blob, so a
    // mismatch identifies the asynchronous overwrite window. Proxy repositories must preserve
    // direct-proxy behavior when an upstream publishes an index digest that disagrees with its
    // release asset (the official Helm examples repository currently has such a release). Nested
    // groups apply this check recursively to any hosted leaf before returning its response.
    if (!member.isHosted()
        || kind != HelmAssetKind.PACKAGE
        || release.digest() == null
        || release.digest().isBlank()) {
      return true;
    }
    Object actual = response.internalAttribute(HelmAssetReader.SHA256_ATTRIBUTE);
    return actual != null
        && normalizeDigest(release.digest()).equalsIgnoreCase(normalizeDigest(actual.toString()));
  }

  private static String normalizeDigest(String digest) {
    String value = digest.trim();
    return value.regionMatches(true, 0, "sha256:", 0, 7) ? value.substring(7) : value;
  }

  static byte[] readBounded(MavenResponse response, int remaining) throws IOException {
    if (remaining < 0) throw metadataLimitExceeded();
    try (InputStream body = response.body()) {
      if (body == null) return new byte[0];
      byte[] bytes = body.readNBytes(remaining + 1);
      if (bytes.length > remaining) throw metadataLimitExceeded();
      return bytes;
    }
  }

  static void ensureIndexWithinLimit(long size) {
    if (size > MAX_AGGREGATED_INDEX_BYTES) throw metadataLimitExceeded();
  }

  private static MavenExceptions.BadUpstreamException metadataLimitExceeded() {
    return new MavenExceptions.BadUpstreamException(
        "Helm group index exceeds the 64 MiB aggregation limit");
  }

  private static MavenResponse indexResponse(
      byte[] body, boolean headOnly, String etag, Instant lastModified) {
    if (headOnly) {
      return MavenResponse.noBody(200, body.length, HelmIndex.CONTENT_TYPE, etag, lastModified);
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, HelmIndex.CONTENT_TYPE, etag, lastModified);
  }

  private static boolean eligible(RepositoryRuntime member) {
    return member != null && member.online() && member.format() == RepositoryFormat.HELM;
  }

  private static HelmAssetKind readableKind(String path) {
    try {
      return HelmAssetKind.fromPath(path);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
  }

  private static void ensureGroup(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.HELM || !runtime.isGroup()) {
      throw new MavenExceptions.MethodNotAllowed(
          "Operation is only valid on Helm group repositories");
    }
  }

  private static long requireBlobStore(RepositoryRuntime runtime) {
    if (runtime.blobStoreId() == null) {
      throw new IllegalStateException("Helm group " + runtime.name() + " has no blob store assigned");
    }
    return runtime.blobStoreId();
  }

  private record IndexResult(MavenResponse response, boolean complete) {
  }

  private record MemberIndexes(List<byte[]> indexes, boolean complete) {
  }
}
