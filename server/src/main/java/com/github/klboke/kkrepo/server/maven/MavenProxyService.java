package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.maven.MavenContentType;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataVersionFilter;
import com.github.klboke.kkrepo.protocol.maven.metadata.MavenMetadataXml;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.HashType;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPath;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.proxy.ProxyRequestAudit;
import com.github.klboke.kkrepo.server.releaseage.MinimumReleaseAge;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Maven proxy facet — caches upstream content into our own blob store and serves it back.
 * <p>
 * Caching rules:
 * <ul>
 *   <li>Metadata paths use {@code metadataMaxAgeMinutes}; everything else uses
 *       {@code contentMaxAgeMinutes}. {@code -1} disables age-based revalidation.</li>
 *   <li>Fresh cache hit → served from blob store with no network call.</li>
 *   <li>Stale or missing → conditional GET upstream (If-None-Match + If-Modified-Since from the
 *       cached blob's stored {@code remoteEtag} / {@code remoteLastModified} attributes).</li>
 *   <li>304 Not Modified → bump asset {@code last_updated_at} so it goes hot again, serve cached.</li>
 *   <li>200 OK → stream upstream body straight to S3 via {@link MavenAssetWriter}, persist new
 *       blob + remote ETag/LM; we skip generating local checksum siblings because Maven clients
 *       request those URLs directly (and we want each cached as its own asset).</li>
 *   <li>404/410 → don't auto-block (legitimate miss); if we had cached, drop it; respond 404.</li>
 *   <li>Other failures → {@link ProxyStateDao#recordFailure}; if {@code autoBlock} and we have a
 *       cached copy, serve stale; otherwise 502.</li>
 *   <li>Circuit breaker open (blocked_until &gt; now) → never call upstream; serve stale or 502.</li>
 * </ul>
 * Backoff schedule for repeated failures: 30s → 5min → 30min cap.
 *
 * <p>Minimum release age (quarantine): when {@code proxy.minimumReleaseAgeMinutes > 0}, release
 * versions (never SNAPSHOTs) whose upstream publish time is younger than the cooldown are removed
 * from artifact-level {@code maven-metadata.xml} (and from its checksum files, which are derived
 * from the filtered bytes) and rejected with 403 for every artifact file, checksum and signature.
 * The publish time of a version is the upstream {@code Last-Modified} of its {@code .pom}, read with
 * a HEAD request. Decisions are recomputed per request from the shared cached upstream metadata;
 * the node-local {@code publishTimes} cache only avoids repeated HEADs, is rebuildable, and holds an
 * immutable timestamp so replicas cannot disagree. A denial is never negative-cached.
 */
@Service
public class MavenProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
  private static final int PUBLISH_TIME_LOOKUP_PARALLELISM = 8;

  private final Clock clock;
  private final LocalCache<String, Instant> publishTimes = LocalCacheFactory.standard()
      .<String, Instant>builder("maven-release-age-publish-times")
      .maximumSize(100_000)
      .expireAfterWrite(Duration.ofHours(6))
      .build();
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final MavenAssetWriter writer;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final ArtifactDownloadPolicy downloadPolicy;
  private final MavenPathParser parser = new MavenPathParser();

  @Autowired
  public MavenProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      ArtifactDownloadPolicy downloadPolicy) {
    this(assetDao, blobStorageRegistry, writer, proxyStateDao, fetcher, negativeCache,
        assetMetadataCache, cacheController, downloadPolicy, Clock.systemUTC());
  }

  MavenProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      ArtifactDownloadPolicy downloadPolicy,
      Clock clock) {
    this.clock = clock;
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.downloadPolicy = downloadPolicy;
  }

  public MavenProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController) {
    this(
        assetDao,
        blobStorageRegistry,
        writer,
        proxyStateDao,
        fetcher,
        negativeCache,
        assetMetadataCache,
        cacheController,
        null);
  }

  public MavenProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      MavenAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache) {
    this(
        assetDao,
        blobStorageRegistry,
        writer,
        proxyStateDao,
        fetcher,
        negativeCache,
        assetMetadataCache,
        null,
        null);
  }

  public MavenResponse get(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("MavenProxyService.get called on non-proxy " + runtime.name());
    }
    // Maven artifacts always have a file name. Trailing-slash / directory-shaped requests
    // would otherwise round-trip to upstream and (for many Maven mirrors) come back as an HTML
    // index page that we'd then happily cache as an "asset". Stop them at the door.
    if (path.path().endsWith("/") || path.fileName().isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (runtime.minimumReleaseAgeEnabled()) {
      MavenResponse quarantined = applyMinimumReleaseAge(runtime, path, headOnly);
      if (quarantined != null) {
        return quarantined;
      }
    }
    return serve(runtime, path, headOnly);
  }

  /** Cached/conditional upstream fetch with no release-age policy applied. */
  private MavenResponse serve(RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
    Optional<CachedAssetMetadata> cached = assetMetadataCache.find(
        runtime.id(),
        path.path(),
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(runtime.id(), path.path()), assetDao));
    Instant now = clock.instant();
    int ttl = ttlMinutes(runtime, path);
    boolean fresh = cached.isPresent() && isFresh(runtime, path, cached.get(), ttl, now);
    if (fresh) {
      return serveCached(cached.get(), headOnly, path);
    }
    if (negativeCache != null && negativeCache.isNotFoundCached(runtime, path)) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
      throw new MavenExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCache(runtime, path, cached, headOnly, now);
  }

  // ---- minimum release age -------------------------------------------------------------------

  /**
   * Returns the response for metadata that had to be rewritten, or {@code null} when the request
   * should be served normally (after the age check passed or when the policy does not apply).
   */
  private MavenResponse applyMinimumReleaseAge(
      RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
    if (parser.isRepositoryMetadata(path)) {
      return filteredMetadata(runtime, path, headOnly);
    }
    Coordinates coordinates = path.main().coordinates();
    if (coordinates == null || isSnapshot(coordinates)) {
      return null;
    }
    // Decide before the cache lookup so content cached before the policy was enabled is gated too.
    // An upstream that has neither the pom nor this file raises MavenNotFoundException from here, and
    // that is deliberately NOT turned into "allowed": the request answers 404 without consulting the
    // cache. Falling through would serve bytes cached before the policy was enabled, or a release the
    // upstream has since withdrawn, which is exactly what the cooldown must not do. A 404 (unlike a
    // 403) still lets clients and groups try their next repository.
    Instant published = publishTime(
        runtime, coordinates.groupId(), coordinates.artifactId(), coordinates.version(), path.main())
        .orElse(null);
    MinimumReleaseAge.require(
        coordinates.groupId() + ":" + coordinates.artifactId() + ":" + coordinates.version(),
        published, runtime.minimumReleaseAgeMinutesOrDefault(), clock.instant());
    return null;
  }

  private MavenResponse filteredMetadata(
      RepositoryRuntime runtime, MavenPath path, boolean headOnly) {
    MavenPath metadataPath = path.main();
    String[] artifact = artifactDirectory(metadataPath);
    if (artifact == null) {
      return null; // group-level or base-version (SNAPSHOT) metadata carries no release versions
    }
    byte[] upstream = readBounded(serve(runtime, metadataPath, false));
    MavenMetadataVersionFilter.Result filtered;
    try {
      List<String> releases = MavenMetadataXml.read(upstream).versions.stream()
          .filter(version -> version != null && !version.isBlank() && !version.endsWith("-SNAPSHOT"))
          .toList();
      java.util.Map<String, Instant> times = publishTimes(runtime, artifact[0], artifact[1], releases);
      int minutes = runtime.minimumReleaseAgeMinutesOrDefault();
      Instant now = clock.instant();
      var result = MavenMetadataVersionFilter.retain(upstream, version ->
          version.endsWith("-SNAPSHOT") || MinimumReleaseAge.eligible(times.get(version), minutes, now));
      if (result.isEmpty()) {
        return null;
      }
      filtered = result.get();
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Unreadable maven-metadata.xml: " + e.getMessage());
    }
    if (filtered.removed().isEmpty()) {
      return null; // nothing hidden, keep the upstream bytes and checksums byte-for-byte
    }
    if (filtered.kept().isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    byte[] body = filtered.xml();
    String contentType = MavenContentType.XML;
    if (path.isHash()) {
      body = checksum(body, path.hashType()).getBytes(StandardCharsets.UTF_8);
      contentType = "text/plain";
    }
    String etag = checksum(body, HashType.SHA1);
    if (headOnly) {
      return MavenResponse.noBody(200, body.length, contentType, etag, clock.instant());
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, contentType, etag, clock.instant());
  }

  /** {groupId, artifactId} for an artifact-level metadata path, or null for any other level. */
  private String[] artifactDirectory(MavenPath metadataPath) {
    String path = metadataPath.path();
    String[] segments = path.split("/");
    if (segments.length < 3 || !"maven-metadata.xml".equals(segments[segments.length - 1])) {
      return null;
    }
    // A base-version (SNAPSHOT) directory ends in -SNAPSHOT; everything else here is g/a/.
    if (segments[segments.length - 2].endsWith("-SNAPSHOT")) {
      return null;
    }
    String artifactId = segments[segments.length - 2];
    String groupId = String.join(".", java.util.Arrays.copyOf(segments, segments.length - 2));
    return new String[] {groupId, artifactId};
  }

  /**
   * Publish time of a release version. The request path is authoritative for the coordinates, never
   * the XML body. Unknown (HEAD failed, no Last-Modified) is reported as empty and treated as
   * quarantined by the caller (fail closed). An upstream that answers 404 for both the pom and the
   * requested file does not have the version at all, which is reported as not found instead.
   */
  private Optional<Instant> publishTime(
      RepositoryRuntime runtime, String groupId, String artifactId, String version,
      MavenPath requested) {
    String key = publishKey(runtime, groupId, artifactId, version);
    Instant known = publishTimes.getIfPresent(key);
    if (known != null) {
      return Optional.of(known);
    }
    Head head = headLastModified(runtime, pomPath(groupId, artifactId, version));
    if (head.lastModified() == null && requested != null) {
      // No pom (or no Last-Modified on it): fall back to the requested file itself.
      Head file = headLastModified(runtime, requested.path());
      head = file.lastModified() != null ? file : new Head(null, head.absent() && file.absent());
    }
    if (head.lastModified() != null) {
      publishTimes.put(key, head.lastModified());
      return Optional.of(head.lastModified());
    }
    if (head.absent()) {
      throw new MavenExceptions.MavenNotFoundException(
          groupId + ":" + artifactId + ":" + version + " does not exist upstream");
    }
    return Optional.empty();
  }

  /**
   * Publish times for {@code versions} of one artifact, with bounded parallel HEADs for the ones not
   * known yet. Versions whose time cannot be determined map to {@code null}.
   */
  private java.util.Map<String, Instant> publishTimes(
      RepositoryRuntime runtime, String groupId, String artifactId, List<String> versions) {
    java.util.Map<String, Instant> result = new java.util.LinkedHashMap<>();
    List<String> unknown = new ArrayList<>();
    for (String version : versions) {
      Instant known = publishTimes.getIfPresent(publishKey(runtime, groupId, artifactId, version));
      if (known != null) {
        result.put(version, known);
      } else {
        unknown.add(version);
      }
    }
    if (unknown.isEmpty()) {
      return result;
    }
    Semaphore permits = new Semaphore(PUBLISH_TIME_LOOKUP_PARALLELISM);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      java.util.Map<String, Future<Instant>> pending = new java.util.LinkedHashMap<>();
      for (String version : unknown) {
        pending.put(version, executor.submit(() -> {
          permits.acquire();
          try {
            return publishTime(runtime, groupId, artifactId, version, null).orElse(null);
          } catch (RuntimeException failed) {
            return null;
          } finally {
            permits.release();
          }
        }));
      }
      for (var entry : pending.entrySet()) {
        try {
          result.put(entry.getKey(), entry.getValue().get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new MavenExceptions.BadUpstreamException("Interrupted while checking release ages");
        } catch (ExecutionException e) {
          result.put(entry.getKey(), null);
        }
      }
    }
    return result;
  }

  /** {@code absent} is true only when the upstream answered 404/410, not for other failures. */
  private record Head(Instant lastModified, boolean absent) {}

  private Head headLastModified(RepositoryRuntime runtime, String path) {
    HttpRemoteFetcher.Request request = new HttpRemoteFetcher.Request(
        buildRemoteUrl(runtime.proxyRemoteUrl(), path), null, null, null, true)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepositoryForContent(runtime);
    try {
      return fetcher.fetchWithBodyRetry(request, path, result ->
          result.status() >= 200 && result.status() < 300
              ? new Head(result.lastModified(), false)
              : new Head(null, result.status() == 404 || result.status() == 410));
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException(
          "Unable to determine publish time of " + path + ": " + e.getMessage());
    }
  }

  private static boolean isSnapshot(Coordinates coordinates) {
    return coordinates.snapshot()
        || (coordinates.version() != null && coordinates.version().endsWith("-SNAPSHOT"));
  }

  private static String pomPath(String groupId, String artifactId, String version) {
    return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
        + artifactId + "-" + version + ".pom";
  }

  /**
   * Cache identity of a publish time. It includes the upstream the time was read from: a proxy's
   * remote URL can be edited without any signal reaching this node-local cache, and a timestamp
   * learned from the former upstream must never answer for the new one (a warm node would otherwise
   * admit what a cold node rejects).
   */
  private static String publishKey(
      RepositoryRuntime runtime, String groupId, String artifactId, String version) {
    return runtime.id() + "|" + runtime.proxyRemoteUrl() + "|" + groupId + ":" + artifactId + ":" + version;
  }

  private static byte[] readBounded(MavenResponse response) {
    try (InputStream body = response.body()) {
      if (body == null) {
        return new byte[0];
      }
      byte[] bytes = body.readNBytes(MAX_METADATA_BYTES + 1);
      if (bytes.length > MAX_METADATA_BYTES) {
        throw new MavenExceptions.BadUpstreamException("maven-metadata.xml exceeds the release-age limit");
      }
      return bytes;
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Failed reading metadata: " + e.getMessage());
    }
  }

  private static String checksum(byte[] body, HashType type) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(type.javaAlgorithm()).digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private MavenResponse fetchAndCache(RepositoryRuntime runtime, MavenPath path,
      Optional<CachedAssetMetadata> cached, boolean headOnly, Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), path.path());
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      String lm = stringAttr(attrs, "remoteLastModified");
      if (lm != null) {
        try { lastModified = Instant.parse(lm); } catch (RuntimeException ignored) {}
      }
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, headOnly)
        .withTimeoutProfile(mavenTimeoutProfile(path))
        .withRepositoryForContent(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path.path(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, path, cached.get(), now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          // #5: refresh the cached snapshot in-place so other replicas observe the freshness bump
          // without re-reading MySQL on their next hit.
          assetMetadataCache.touchVerified(runtime.id(), path.path(), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (negativeCache != null) negativeCache.invalidate(runtime, path);
          return serveCached(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          if (negativeCache != null) negativeCache.invalidate(runtime, path);
          if (headOnly) {
            proxyStateDao.recordSuccess(runtime.id(), now);
            // An uncached HEAD has no bytes from which to create a scan subject. The first GET
            // persists the complete asset and applies policy to that concrete content generation
            // before opening its response body.
            return remoteHeadResponse(result);
          }
          return persistAndServe(runtime, path, result, headOnly, now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return serveCached(cached.get(), headOnly, path);
          }
          if (status == 404 && negativeCache != null) negativeCache.rememberNotFound(runtime, path);
          throw new MavenExceptions.MavenNotFoundException(path.path());
        }
        return handleUpstreamFailure(runtime, path, cached, headOnly,
            "Upstream returned " + status, now);
      });
    } catch (IOException e) {
      return handleUpstreamFailure(runtime, path, cached, headOnly,
          "Upstream IO error: " + e.getMessage(), now);
    }
  }

  private MavenResponse persistAndServe(RepositoryRuntime runtime, MavenPath path,
      HttpRemoteFetcher.Result result, boolean headOnly, Instant now) {
    String contentType = result.contentType();
    // Defense in depth: some upstream mirrors return an HTML directory page (200 OK) for
    // unknown / directory-shaped paths instead of a 404. We must not cache that as the asset
    // — otherwise the bogus entry shows up in browse_node and poisons future lookups.
    // Real Maven content is never text/html; metadata XML files have content-type application/xml.
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, String> extras = new HashMap<>();
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    MavenAssetWriter.Stored stored = writer.writePrimary(
        runtime, storage, blobStoreId, path, result.body(),
        contentType, "proxy", ProxyRequestAudit.currentClientIp(),
        extras, false, !headOnly);
    try {
      proxyStateDao.recordSuccess(runtime.id(), now);
      AssetRecord asset = stored.asset();
      AssetBlobRecord blob = stored.blob();
      if (downloadPolicy != null) {
        downloadPolicy.beforeReadFromRepository(asset.id(), blob.id(), asset.repositoryId());
      }
      String etag = blob.sha1();
      Instant lastModified = asset.lastUpdatedAt();
      if (headOnly) {
        stored.discardBody();
        return MavenResponse.noBody(200, blob.size(), asset.contentType(), etag, lastModified);
      }
      return MavenResponse.ok(stored.openBody(), blob.size(), asset.contentType(), etag, lastModified);
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private MavenResponse remoteHeadResponse(HttpRemoteFetcher.Result result) {
    return MavenResponse.noBody(
        result.status(),
        result.contentLength(),
        result.contentType(),
        result.etag(),
        result.lastModified());
  }

  private MavenResponse serveCached(CachedAssetMetadata snapshot, boolean headOnly, MavenPath path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (downloadPolicy != null) {
      downloadPolicy.beforeReadFromRepository(
          snapshot.assetId(), blob.id(), snapshot.repositoryId());
    }
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

  private MavenResponse handleUpstreamFailure(RepositoryRuntime runtime, MavenPath path,
      Optional<CachedAssetMetadata> cached, boolean headOnly, String error, Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
    throw new MavenExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(
      RepositoryRuntime runtime,
      MavenPath path,
      CachedAssetMetadata snapshot,
      int ttlMinutes,
      Instant now) {
    Optional<NexusLikeCacheInfo> cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes());
    if (cacheController != null && cacheInfo.isPresent()) {
      return !cacheController.isStale(runtime.id(), cacheType(path), cacheInfo.get(), ttlMinutes, now);
    }
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private Map<String, Object> refreshedAttributes(
      RepositoryRuntime runtime,
      MavenPath path,
      CachedAssetMetadata snapshot,
      Instant now) {
    if (cacheController == null) {
      return null;
    }
    Map<String, Object> attrs = new LinkedHashMap<>();
    if (snapshot.attributes() != null) {
      attrs.putAll(snapshot.attributes());
    }
    return NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), cacheType(path), now));
  }

  private int ttlMinutes(RepositoryRuntime runtime, MavenPath path) {
    return parser.isRepositoryMetadata(path)
        ? runtime.metadataMaxAgeMinutesOrDefault()
        : runtime.contentMaxAgeMinutesOrDefault();
  }

  private static NexusCacheType cacheType(MavenPath path) {
    return "maven-metadata.xml".equals(path.main().fileName())
        ? NexusCacheType.METADATA
        : NexusCacheType.CONTENT;
  }

  private static HttpRemoteFetcher.TimeoutProfile mavenTimeoutProfile(MavenPath path) {
    return cacheType(path) == NexusCacheType.METADATA
        ? HttpRemoteFetcher.TimeoutProfile.METADATA
        : HttpRemoteFetcher.TimeoutProfile.CONTENT;
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Proxy " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String buildRemoteUrl(String base, String path) {
    return RemoteUrlBuilder.repositoryPathString(base, path);
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    if (attrs == null) return null;
    Object v = attrs.get(key);
    return v == null ? null : v.toString();
  }

}
