package com.github.klboke.kkrepo.server.goartifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.protocol.goartifact.GoModulePaths;
import com.github.klboke.kkrepo.protocol.goartifact.GoVersions;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.releaseage.MinimumReleaseAge;
import com.github.klboke.kkrepo.server.securityscan.ArtifactDownloadPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/**
 * Go module proxy.
 *
 * <p>Minimum release age (quarantine): when {@code proxy.minimumReleaseAgeMinutes > 0}, versions whose
 * {@code .info} {@code Time} is younger than the cooldown are removed from {@code @v/list}, never
 * returned by {@code @latest}, and rejected with 403 for {@code .info}, {@code .mod} and {@code .zip}.
 * Every decision is derived from shared state (the cached upstream responses in MySQL and blob
 * storage); the node-local {@code publishTimes} cache only avoids re-reading {@code .info} blobs and
 * can be lost at any time. A version's publish time is immutable, so cached entries cannot disagree
 * across replicas, and a denial is never cached.
 *
 * <p>Limit: the GOPROXY {@code Time} is the <em>commit</em> time (even for tagged versions), not the
 * time the version was published. A new tag on an old commit is therefore admitted immediately and a
 * backdated commit date defeats the cooldown. This is documented as commit age, a weaker guard than
 * the registry-supplied timestamps used for Maven and PyPI.
 */
@Service
public class GoProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
  private static final int PUBLISH_TIME_LOOKUP_PARALLELISM = 8;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final GoAssetWriter writer;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final ArtifactDownloadPolicy downloadPolicy;
  private final Clock clock;
  private final LocalCache<String, Instant> publishTimes = LocalCacheFactory.standard()
      .<String, Instant>builder("go-release-age-publish-times")
      .maximumSize(100_000)
      .expireAfterWrite(Duration.ofHours(6))
      .build();

  public GoProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      GoAssetWriter writer,
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
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public GoProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      GoAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      ArtifactDownloadPolicy downloadPolicy) {
    this(
        assetDao,
        blobStorageRegistry,
        writer,
        proxyStateDao,
        fetcher,
        negativeCache,
        assetMetadataCache,
        downloadPolicy,
        Clock.systemUTC());
  }

  GoProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      GoAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      ArtifactDownloadPolicy downloadPolicy,
      Clock clock) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.downloadPolicy = downloadPolicy;
    this.clock = clock;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    if (runtime.format() != RepositoryFormat.GO || !runtime.isProxy()) {
      throw new MavenExceptions.MethodNotAllowed("Go proxy requests require a go proxy repository");
    }
    GoPath path = parse(rawPath);
    if (!runtime.minimumReleaseAgeEnabled()) {
      return serve(runtime, path, headOnly);
    }
    return switch (path.kind()) {
      case LIST -> filteredList(runtime, path, headOnly);
      case LATEST -> filteredLatest(runtime, path, headOnly);
      case INFO -> checkedInfo(runtime, path, headOnly);
      case MODULE, PACKAGE -> {
        // Decide before the cache lookup so already-cached content is gated too.
        MinimumReleaseAge.require(
            describe(path), publishTime(runtime, path.module(), path.version()).orElse(null),
            runtime.minimumReleaseAgeMinutesOrDefault(), clock.instant());
        yield serve(runtime, path, headOnly);
      }
    };
  }

  /** Cached/conditional upstream fetch with no release-age policy applied. */
  private MavenResponse serve(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path.path());
    Instant now = clock.instant();
    int ttl = path.metadata()
        ? runtime.metadataMaxAgeMinutesOrDefault()
        : runtime.contentMaxAgeMinutesOrDefault();
    if (cached.isPresent() && isFresh(cached.get(), ttl, now)) {
      return serveCached(cached.get(), headOnly, path);
    }
    if (negativeCache.isNotFoundCached(runtime, path.path())) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
      throw new MavenExceptions.BadUpstreamException(
          "Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchAndCache(runtime, path, cached, headOnly, now);
  }

  // ---- minimum release age -------------------------------------------------------------------

  private MavenResponse filteredList(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    MavenResponse raw = serve(runtime, path, false);
    byte[] body = readBounded(raw);
    List<String> kept = new ArrayList<>();
    for (Map.Entry<String, Instant> entry : publishTimes(runtime, path.module(), versions(body)).entrySet()) {
      if (MinimumReleaseAge.eligible(
          entry.getValue(), runtime.minimumReleaseAgeMinutesOrDefault(), clock.instant())) {
        kept.add(entry.getKey());
      }
    }
    String filtered = String.join("\n", kept) + (kept.isEmpty() ? "" : "\n");
    return rebuild(raw, filtered.getBytes(StandardCharsets.UTF_8), headOnly);
  }

  private MavenResponse filteredLatest(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    MavenResponse raw = serve(runtime, path, false);
    byte[] body = readBounded(raw);
    Instant upstreamTime = infoTime(body).orElse(null);
    int minutes = runtime.minimumReleaseAgeMinutesOrDefault();
    if (MinimumReleaseAge.eligible(upstreamTime, minutes, clock.instant())) {
      return rebuild(raw, body, headOnly);
    }
    // The newest upstream version is quarantined: answer with the newest tagged version that has
    // matured, exactly as if the quarantined releases did not exist yet.
    GoPath list = GoPath.parse(GoModulePaths.escape(path.module()) + "/@v/list");
    byte[] listBody = readBounded(serve(runtime, list, false));
    List<GoVersions.Candidate> mature = new ArrayList<>();
    for (Map.Entry<String, Instant> entry : publishTimes(runtime, path.module(), versions(listBody)).entrySet()) {
      if (MinimumReleaseAge.eligible(entry.getValue(), minutes, clock.instant())) {
        mature.add(new GoVersions.Candidate(entry.getKey(), entry.getValue()));
      }
    }
    GoVersions.Candidate selected = GoVersions.latest(mature)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path()));
    return GoResponses.info(JSON, selected.version(), selected.time(), raw.lastModified(), headOnly);
  }

  /** {@code .info} carries its own Time, so it is checked from the body before anything is returned. */
  private MavenResponse checkedInfo(RepositoryRuntime runtime, GoPath path, boolean headOnly) {
    MavenResponse raw = serve(runtime, path, false);
    byte[] body = readBounded(raw);
    Instant published = infoTime(body).orElse(null);
    MinimumReleaseAge.require(
        describe(path), published, runtime.minimumReleaseAgeMinutesOrDefault(), clock.instant());
    if (path.version() != null && GoVersions.isCanonical(path.version()) && published != null) {
      publishTimes.put(publishKey(runtime, path.module(), path.version()), published);
    }
    return rebuild(raw, body, headOnly);
  }

  /** Commit time (the GOPROXY {@code Time}) of a canonical version, read from the cached upstream {@code .info}. */
  private Optional<Instant> publishTime(RepositoryRuntime runtime, String module, String version) {
    if (version == null || !GoVersions.isCanonical(version)) {
      throw new MavenExceptions.MavenNotFoundException(module + "@" + version);
    }
    String key = publishKey(runtime, module, version);
    Instant known = publishTimes.getIfPresent(key);
    if (known != null) {
      return Optional.of(known);
    }
    GoPath info = GoPath.versioned(module, version, GoAssetKind.INFO);
    Optional<Instant> published = infoTime(readBounded(serve(runtime, info, false)));
    published.ifPresent(time -> publishTimes.put(key, time));
    return published;
  }

  /**
   * Publish times for {@code versions}, fetching missing {@code .info} documents with bounded
   * parallelism. A version whose time cannot be determined (not found, upstream error, malformed)
   * is reported as {@code null} so the caller treats it as quarantined (fail closed).
   */
  private Map<String, Instant> publishTimes(
      RepositoryRuntime runtime, String module, List<String> versions) {
    Map<String, Instant> result = new java.util.LinkedHashMap<>();
    Semaphore permits = new Semaphore(PUBLISH_TIME_LOOKUP_PARALLELISM);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<String, Future<Instant>> pending = new java.util.LinkedHashMap<>();
      for (String version : versions) {
        pending.put(version, executor.submit(() -> {
          permits.acquire();
          try {
            return publishTime(runtime, module, version).orElse(null);
          } catch (RuntimeException unknown) {
            return null;
          } finally {
            permits.release();
          }
        }));
      }
      for (Map.Entry<String, Future<Instant>> entry : pending.entrySet()) {
        try {
          result.put(entry.getKey(), entry.getValue().get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new MavenExceptions.BadUpstreamException("Interrupted while checking Go release ages");
        } catch (ExecutionException e) {
          result.put(entry.getKey(), null);
        }
      }
    }
    return result;
  }

  private static List<String> versions(byte[] listBody) {
    List<String> versions = new ArrayList<>();
    for (String line : new String(listBody, StandardCharsets.UTF_8).split("\\R")) {
      String version = line.trim();
      // Non-canonical lines cannot be looked up, so they are never served while the policy is on.
      if (!version.isEmpty() && GoVersions.isCanonical(version)) {
        versions.add(version);
      }
    }
    return versions;
  }

  private static Optional<Instant> infoTime(byte[] infoBody) {
    try {
      JsonNode time = JSON.readTree(infoBody).get("Time");
      return time != null && time.isTextual() ? Optional.of(Instant.parse(time.textValue())) : Optional.empty();
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  private static byte[] readBounded(MavenResponse response) {
    try (InputStream body = response.body()) {
      if (body == null) {
        return new byte[0];
      }
      byte[] bytes = body.readNBytes(MAX_METADATA_BYTES + 1);
      if (bytes.length > MAX_METADATA_BYTES) {
        throw new MavenExceptions.BadUpstreamException("Go metadata exceeds the release-age limit");
      }
      return bytes;
    } catch (IOException e) {
      throw new MavenExceptions.BadUpstreamException("Failed reading Go metadata: " + e.getMessage());
    }
  }

  private static MavenResponse rebuild(MavenResponse original, byte[] body, boolean headOnly) {
    if (headOnly) {
      return MavenResponse.noBody(
          200, body.length, original.contentType(), original.etag(), original.lastModified());
    }
    return MavenResponse.ok(
        new ByteArrayInputStream(body), body.length, original.contentType(), original.etag(),
        original.lastModified());
  }

  /**
   * Cache identity of a publish time. It includes the upstream the time was read from: a proxy's
   * remote URL can be edited without any signal reaching this node-local cache, and a timestamp
   * learned from the former upstream must never answer for the new one (a warm node would otherwise
   * admit what a cold node rejects).
   */
  private static String publishKey(RepositoryRuntime runtime, String module, String version) {
    return runtime.id() + "|" + runtime.proxyRemoteUrl() + "|" + module + "@" + version;
  }

  private static String describe(GoPath path) {
    return "Go module " + path.module() + "@" + path.version();
  }


  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private GoPath parse(String rawPath) {
    try {
      return GoPath.parse(rawPath);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException(e.getMessage());
    }
  }

  private MavenResponse fetchAndCache(
      RepositoryRuntime runtime,
      GoPath path,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), path.path());
    String etag = null;
    Instant lastModified = null;
    if (cached.isPresent() && cached.get().blob() != null) {
      Map<String, Object> attrs = cached.get().blob().attributes();
      etag = stringAttr(attrs, "remoteEtag");
      lastModified = instantAttr(attrs, "remoteLastModified");
    }
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, etag, lastModified, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepositoryForContent(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, path.path(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          assetMetadataCache.touchVerified(runtime.id(), path.path(), now);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, path.path());
          return serveCached(cached.get(), headOnly, path);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, path.path());
          return persistAndServe(runtime, path, result, headOnly, now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return serveCached(cached.get(), headOnly, path);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, path.path());
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

  private MavenResponse persistAndServe(
      RepositoryRuntime runtime,
      GoPath path,
      HttpRemoteFetcher.Result result,
      boolean headOnly,
      Instant now) {
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, String> extras = new HashMap<>();
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    GoAssetWriter.Stored stored = writer.write(
        runtime, storage, blobStoreId, path, result.body(), extras, !headOnly);
    try {
      if (downloadPolicy != null) {
        downloadPolicy.beforeReadFromRepository(
            stored.asset().id(), stored.blob().id(), stored.asset().repositoryId());
      }
      proxyStateDao.recordSuccess(runtime.id(), now);
      if (headOnly) {
        stored.discardBody();
        return toResponse(storage, stored.asset().lastUpdatedAt(), stored.blob(), true, path);
      }
      String etag = stringAttr(stored.blob().attributes(), "remoteEtag");
      Instant lastModified = instantAttr(stored.blob().attributes(), "remoteLastModified");
      if (lastModified == null) lastModified = stored.asset().lastUpdatedAt();
      return MavenResponse.ok(
          stored.openBody(), stored.blob().size(), path.proxyContentType(), etag, lastModified);
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private MavenResponse serveCached(CachedAssetMetadata snapshot, boolean headOnly, GoPath path) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new MavenExceptions.MavenNotFoundException(path.path());
    }
    if (downloadPolicy != null) {
      downloadPolicy.beforeReadFromRepository(
          snapshot.assetId(), blob.id(), snapshot.repositoryId());
    }
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    return toResponse(storage, snapshot.lastUpdatedAt(), blob, headOnly, path);
  }

  private MavenResponse toResponse(
      BlobStorage storage,
      Instant assetLastUpdatedAt,
      AssetBlobRecord blob,
      boolean headOnly,
      GoPath path) {
    String etag = stringAttr(blob.attributes(), "remoteEtag");
    Instant lastModified = instantAttr(blob.attributes(), "remoteLastModified");
    if (lastModified == null) lastModified = assetLastUpdatedAt;
    String contentType = path.proxyContentType();
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, etag, lastModified);
    }
    return MavenResponse.ok(
        () -> storage.get(BlobReferenceCodec.reference(blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path.path())),
        blob.size(), contentType, etag, lastModified);
  }

  private MavenResponse handleUpstreamFailure(
      RepositoryRuntime runtime,
      GoPath path,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      String error,
      Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) return serveCached(cached.get(), headOnly, path);
    throw new MavenExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(CachedAssetMetadata snapshot, int ttlMinutes, Instant now) {
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
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

  private static Instant instantAttr(Map<String, Object> attrs, String key) {
    String raw = stringAttr(attrs, key);
    if (raw == null) return null;
    try {
      return Instant.parse(raw);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

}
