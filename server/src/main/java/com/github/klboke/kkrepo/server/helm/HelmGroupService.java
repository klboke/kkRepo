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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Nexus-compatible ordered aggregation for classic Helm chart repositories. */
@Service
public class HelmGroupService {
  static final int MAX_AGGREGATED_INDEX_BYTES = 64 * 1024 * 1024;
  private static final Logger log = LoggerFactory.getLogger(HelmGroupService.class);

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
    return getIndex(group, headOnly, resolvingGroups, null);
  }

  private IndexResult getIndex(
      RepositoryRuntime group,
      boolean headOnly,
      Set<Long> resolvingGroups,
      String releasePath) {
    if (!resolvingGroups.add(group.id())) {
      throw new MavenExceptions.MethodNotAllowed(
          "Cyclic Helm group repository membership: " + group.name());
    }
    try {
      Instant now = Instant.now();
      if (indexCache != null) {
        Optional<CachedAssetMetadata> cached = indexCache.findFresh(group, now);
        if (cached.isPresent()) {
          CachedAssetMetadata snapshot = cached.orElseThrow();
          MavenResponse cachedResponse = reader.serveSnapshot(
              snapshot, false, HelmHostedService.INDEX_PATH);
          try {
            // Group metadata is reconstructable. Open and validate the cached blob before
            // accepting its watermark so a missing/corrupt object cannot pin every replica to a
            // broken lazy response until the normal metadata TTL expires.
            byte[] cachedBody = readBounded(cachedResponse, MAX_AGGREGATED_INDEX_BYTES);
            HelmIndex.ValidatedIndex validated = HelmIndex.parseValidatedIndex(cachedBody);
            return new IndexResult(
                indexResponse(
                    cachedBody,
                    headOnly,
                    cachedResponse.etag(),
                    cachedResponse.lastModified()),
                true,
                indexCache.memberIndexFreshUntil(snapshot),
                selectedRelease(validated, releasePath));
          } catch (IOException | RuntimeException e) {
            log.warn(
                "Failed reading durable Helm group index cache for {}; rebuilding",
                group.name(),
                e);
            invalidateGroupIndexCache(group.id());
          }
        }
      }

      AggregatedIndex aggregated = aggregateIndex(group, resolvingGroups);
      boolean durableCacheEnabled = indexCache != null && indexCache.enabled();
      if (aggregated.memberIndexes().complete()
          && durableCacheEnabled
          && !aggregated.watermarkStable()) {
        // A member changed while its bytes were being collected. Retry once against the new
        // generation; sustained churn remains safe to serve but must not seed this group or a
        // containing group's durable cache.
        aggregated = aggregateIndex(group, resolvingGroups);
      }
      MemberIndexes memberIndexes = aggregated.memberIndexes();
      byte[] body = aggregated.body();
      // A partial response is still useful, but no replica may publish it as fresh under the
      // shared watermark and thereby hide a recovered member until metadata expiry.
      if (!memberIndexes.complete() || !durableCacheEnabled) {
        return new IndexResult(
            indexResponse(body, headOnly, null, aggregated.generatedAt()),
            memberIndexes.complete(),
            memberIndexes.freshUntil(),
            selectedRelease(body, releasePath));
      }

      long blobStoreId = requireBlobStore(group);
      if (!aggregated.watermarkStable()) {
        return new IndexResult(
            indexResponse(body, headOnly, null, aggregated.generatedAt()),
            false,
            memberIndexes.freshUntil(),
            selectedRelease(body, releasePath));
      }
      HelmAssetWriter.Stored stored;
      try {
        BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
        stored = writer.writeBytes(
            group,
            storage,
            blobStoreId,
            HelmHostedService.INDEX_PATH,
            body,
            HelmIndex.CONTENT_TYPE,
            HelmAssetKind.INDEX,
            null,
            indexCache.freshAttributes(
                group,
                aggregated.cacheInfo(),
                memberIndexes.freshUntil(),
                aggregated.memberAssetGeneration()),
            java.util.Map.of(),
            "group",
            null);
      } catch (RuntimeException e) {
        // Persistence is an optimization: a complete authoritative merge remains safe to serve
        // from memory when the group's cache blob store or metadata write is temporarily down.
        log.warn(
            "Failed persisting durable Helm group index cache for {}; serving in-memory merge",
            group.name(),
            e);
        invalidateGroupIndexCache(group.id());
        return new IndexResult(
            indexResponse(body, headOnly, null, aggregated.generatedAt()),
            true,
            memberIndexes.freshUntil(),
            selectedRelease(body, releasePath));
      }
      reader.beforeRead(stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
      return new IndexResult(
          indexResponse(body, headOnly, stored.blob().sha1(), stored.asset().lastUpdatedAt()),
          true,
          memberIndexes.freshUntil(),
          selectedRelease(body, releasePath));
    } finally {
      resolvingGroups.remove(group.id());
    }
  }

  private AggregatedIndex aggregateIndex(
      RepositoryRuntime group, Set<Long> resolvingGroups) {
    Instant generatedAt = Instant.now();
    boolean trackWatermark = indexCache != null && indexCache.enabled();
    String memberGenerationBefore = trackWatermark ? currentMemberGeneration(group) : null;
    NexusLikeCacheInfo before = trackWatermark ? currentWatermark(group) : null;
    MemberIndexes members = memberIndexes(group, resolvingGroups);
    byte[] body = HelmIndex.mergeGroupIndexes(members.indexes(), generatedAt);
    ensureIndexWithinLimit(body.length);
    NexusLikeCacheInfo after = trackWatermark ? currentWatermark(group) : null;
    String memberGenerationAfter = trackWatermark ? currentMemberGeneration(group) : null;
    boolean stable = !trackWatermark
        || (sameGeneration(before, after)
            && memberGenerationBefore != null
            && memberGenerationBefore.equals(memberGenerationAfter));
    return new AggregatedIndex(
        members, body, generatedAt, after, memberGenerationAfter, stable);
  }

  private NexusLikeCacheInfo currentWatermark(RepositoryRuntime group) {
    try {
      return indexCache.current(group, Instant.now());
    } catch (RuntimeException e) {
      log.warn("Failed reading Helm group index watermark for {}", group.name(), e);
      return null;
    }
  }

  private String currentMemberGeneration(RepositoryRuntime group) {
    try {
      return indexCache.memberAssetGeneration(group);
    } catch (RuntimeException e) {
      log.warn("Failed reading Helm group member generation for {}", group.name(), e);
      return null;
    }
  }

  private static boolean sameGeneration(
      NexusLikeCacheInfo before, NexusLikeCacheInfo after) {
    return before != null
        && after != null
        && Objects.equals(before.cacheToken(), after.cacheToken());
  }

  private MemberIndexes memberIndexes(
      RepositoryRuntime group, Set<Long> resolvingGroups) {
    List<byte[]> indexes = new ArrayList<>();
    boolean complete = true;
    Instant freshUntil = null;
    int total = 0;
    for (RepositoryRuntime member : group.members()) {
      if (!eligible(member)) continue;
      MavenResponse response;
      Instant memberFreshUntil = null;
      try {
        if (member.isGroup()) {
          IndexResult nested = getIndex(member, false, resolvingGroups);
          response = nested.response();
          complete &= nested.complete();
          memberFreshUntil = nested.freshUntil();
        } else {
          response = member.isHosted()
              ? hosted.get(member, HelmHostedService.INDEX_PATH, false)
              : proxy.getIndexForGroup(member, false);
          if (member.isProxy() && Boolean.FALSE.equals(
              response.internalAttribute(HelmProxyService.INDEX_AUTHORITATIVE_ATTRIBUTE))) {
            complete = false;
          }
          if (member.isProxy()) {
            memberFreshUntil = indexFreshUntil(response);
            if (member.metadataMaxAgeMinutesOrDefault() >= 0 && memberFreshUntil == null) {
              // A finite proxy metadata TTL without its absolute deadline cannot safely seed a
              // durable group cache: doing so would restart the member's full TTL at merge time.
              complete = false;
            }
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
      } catch (RuntimeException e) {
        // Hosted index generation can fail before it reaches a protocol exception (for example,
        // when the member blob store is unavailable). Keep that failure at the member boundary so
        // healthy members remain readable, but never publish the degraded merge as fresh.
        log.warn(
            "Failed reading Helm member index {} for group {}; isolating member",
            member.name(),
            group.name(),
            e);
        complete = false;
        continue;
      }
      byte[] body;
      try {
        body = readBounded(response, MAX_AGGREGATED_INDEX_BYTES - total);
      } catch (MetadataLimitExceeded e) {
        throw e;
      } catch (IOException | RuntimeException e) {
        complete = false;
        continue;
      }
      // Isolate an invalid upstream/member index instead of making every healthy member unusable.
      try {
        HelmIndex.validateIndex(body);
      } catch (RuntimeException ignored) {
        complete = false;
        continue;
      }
      total += body.length;
      indexes.add(body);
      freshUntil = earliest(freshUntil, memberFreshUntil);
    }
    return new MemberIndexes(indexes, complete, freshUntil);
  }

  private static Instant indexFreshUntil(MavenResponse response) {
    Object raw = response.internalAttribute(HelmProxyService.INDEX_FRESH_UNTIL_ATTRIBUTE);
    if (raw instanceof Instant instant) return instant;
    if (raw == null) return null;
    try {
      return Instant.parse(raw.toString());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Instant earliest(Instant current, Instant candidate) {
    if (candidate == null) return current;
    return current == null || candidate.isBefore(current) ? candidate : current;
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
      GroupMemberAssetCache.Generation winnerGeneration =
          captureWinnerGeneration(group, cacheType, path);
      ReleaseSelection selection = advertisedRelease(group, path);
      HelmIndex.Release release = selection.release();
      Optional<Long> cachedMemberId = memberAssetCache == null || winnerGeneration == null
          ? Optional.empty()
          : memberAssetCache.getIfCurrent(group, path, cacheType, winnerGeneration);
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
                  dispatchAsset(cachedMember, path, kind, false, resolvingGroups);
              if (matchesAdvertisedDigest(cachedMember, kind, release, response)) {
                return materializeCandidateBody(cachedMember, path, response, headOnly);
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
          MavenResponse response = dispatchAsset(member, path, kind, false, resolvingGroups);
          if (!matchesAdvertisedDigest(member, kind, release, response)) {
            response.closeBodyIfOpen();
            continue;
          }
          response = materializeCandidateBody(member, path, response, headOnly);
          if (memberAssetCache != null && winnerGeneration != null && selection.complete()) {
            memberAssetCache.putIfCurrent(
                group, path, cacheType, member.id(), winnerGeneration);
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

  private static MavenResponse materializeCandidateBody(
      RepositoryRuntime member, String path, MavenResponse response, boolean headOnly) {
    try {
      MavenResponse materialized = response.materializeBody();
      if (!headOnly) return materialized;
      materialized.closeBodyIfOpen();
      MavenResponse head = MavenResponse.noBody(
          materialized.status(),
          materialized.contentLength(),
          materialized.contentType(),
          materialized.etag(),
          materialized.lastModified());
      materialized.headers().forEach(head::withHeader);
      return head;
    } catch (RuntimeException e) {
      response.closeBodyIfOpen();
      throw new MavenExceptions.BadUpstreamException(
          "Failed opening Helm group member asset " + member.name() + "/" + path,
          e);
    }
  }

  private GroupMemberAssetCache.Generation captureWinnerGeneration(
      RepositoryRuntime group, NexusCacheType cacheType, String path) {
    if (memberAssetCache == null || indexCache == null) return null;
    String sourceGeneration = currentWinnerGeneration(group, path);
    if (sourceGeneration == null || sourceGeneration.isBlank()) return null;
    return memberAssetCache.captureGeneration(group, cacheType)
        .map(generation -> generation.withSourceGeneration(sourceGeneration))
        .orElse(null);
  }

  private String currentWinnerGeneration(RepositoryRuntime group, String path) {
    try {
      return indexCache.winnerAssetGeneration(group, path);
    } catch (RuntimeException e) {
      log.warn("Failed reading Helm group winner generation for {} path {}", group.name(), path, e);
      return null;
    }
  }

  private ReleaseSelection advertisedRelease(RepositoryRuntime group, String path) {
    IndexResult index = null;
    try {
      index = getIndex(group, false, new HashSet<>(), path);
      HelmIndex.Release release = index.release()
          .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
      return new ReleaseSelection(release, index.complete());
    } catch (MavenExceptions.MavenNotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new MavenExceptions.BadUpstreamException(
          "Invalid Helm group index for " + group.name(), e);
    } finally {
      if (index != null) index.response().closeBodyIfOpen();
    }
  }

  private boolean memberAdvertises(
      RepositoryRuntime member, HelmIndex.Release release, String path) {
    if (member.isGroup()) {
      IndexResult nested = null;
      try {
        nested = getIndex(member, false, new HashSet<>(), path);
        return nested.release()
            .map(candidate -> HelmIndex.sameRelease(candidate, release))
            .orElse(false);
      } catch (MavenExceptions.MavenNotFoundException
          | MavenExceptions.BadUpstreamException
          | MavenExceptions.MethodNotAllowed ignored) {
        return false;
      } catch (RuntimeException ignored) {
        return false;
      } finally {
        if (nested != null) nested.response().closeBodyIfOpen();
      }
    }
    try {
      MavenResponse response = member.isHosted()
          ? hosted.get(member, HelmHostedService.INDEX_PATH, false)
          : proxy.getIndexForGroup(member, false);
      byte[] body = readBounded(response, MAX_AGGREGATED_INDEX_BYTES);
      return HelmIndex.parseValidatedIndex(body).containsRelease(release, path);
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
    // Hosted repositories and nested groups are authoritative for the digest they advertise. A
    // nested group independently reloads its own index while resolving, so the outer boundary must
    // verify the returned bytes again in case the nested winner changed after the outer merge.
    // Direct proxy members retain proxy behavior when an upstream index digest disagrees with its
    // release asset (the official Helm examples repository currently has such a release).
    if (!(member.isHosted() || member.isGroup())
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

  private static MetadataLimitExceeded metadataLimitExceeded() {
    return new MetadataLimitExceeded();
  }

  private static MavenResponse indexResponse(
      byte[] body, boolean headOnly, String etag, Instant lastModified) {
    if (headOnly) {
      return MavenResponse.noBody(200, body.length, HelmIndex.CONTENT_TYPE, etag, lastModified);
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, HelmIndex.CONTENT_TYPE, etag, lastModified);
  }

  private static Optional<HelmIndex.Release> selectedRelease(
      HelmIndex.ValidatedIndex index, String path) {
    return path == null ? Optional.empty() : index.releaseForPath(path);
  }

  private static Optional<HelmIndex.Release> selectedRelease(byte[] body, String path) {
    return path == null
        ? Optional.empty()
        : HelmIndex.parseValidatedIndex(body).releaseForPath(path);
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

  private void invalidateGroupIndexCache(long groupId) {
    try {
      indexCache.invalidateGroupAfterCommit(groupId);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating broken Helm group index cache for group {}", groupId, e);
    }
  }

  private static long requireBlobStore(RepositoryRuntime runtime) {
    if (runtime.blobStoreId() == null) {
      throw new IllegalStateException("Helm group " + runtime.name() + " has no blob store assigned");
    }
    return runtime.blobStoreId();
  }

  private record IndexResult(
      MavenResponse response,
      boolean complete,
      Instant freshUntil,
      Optional<HelmIndex.Release> release) {
    private IndexResult {
      release = release == null ? Optional.empty() : release;
    }
  }

  private record ReleaseSelection(HelmIndex.Release release, boolean complete) {}

  private record MemberIndexes(List<byte[]> indexes, boolean complete, Instant freshUntil) {
  }

  private record AggregatedIndex(
      MemberIndexes memberIndexes,
      byte[] body,
      Instant generatedAt,
      NexusLikeCacheInfo cacheInfo,
      String memberAssetGeneration,
      boolean watermarkStable) {
  }

  private static final class MetadataLimitExceeded
      extends MavenExceptions.BadUpstreamException {
    private MetadataLimitExceeded() {
      super("Helm group index exceeds the 64 MiB aggregation limit");
    }
  }
}
