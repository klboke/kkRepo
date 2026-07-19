package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.NpmReleaseIndexDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ProxyStateDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.HttpRemoteFetcher;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.maven.RemoteUrlBuilder;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.UpstreamBodyReadException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NpmProxyService {
  private static final long[] BACKOFF_SECONDS = {30, 60, 120, 300, 600, 1800};
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String FULL_METADATA_ATTRIBUTE = "npmFullMetadata";
  private static final String COMPLETE_PUBLISH_TIMES_ATTRIBUTE = "npmCompletePublishTimes";
  static final String POLICY_VALID_UNTIL_CONTEXT = "npm.minimumReleaseAgeValidUntil";

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final NpmAssetWriter writer;
  private final ProxyStateDao proxyStateDao;
  private final HttpRemoteFetcher fetcher;
  private final NpmHostedService hosted;
  private final ObjectMapper mapper;
  private final ProxyNegativeCache negativeCache;
  private final AssetMetadataCache assetMetadataCache;
  private final NexusLikeCacheController cacheController;
  private final NpmReleaseAgeCache releaseAgeCache;
  private final NpmReleaseIndexDao releaseIndexDao;
  private final Clock clock;

  @Autowired
  public NpmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ObjectMapper mapper,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      NpmReleaseAgeCache releaseAgeCache,
      NpmReleaseIndexDao releaseIndexDao) {
    this(assetDao, blobStorageRegistry, writer, proxyStateDao, fetcher, hosted, mapper,
        negativeCache, assetMetadataCache, cacheController, releaseAgeCache, releaseIndexDao,
        Clock.systemUTC());
  }

  /** Compatibility constructor for focused tests and subclasses that never enable the policy. */
  NpmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ObjectMapper mapper,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController) {
    this(assetDao, blobStorageRegistry, writer, proxyStateDao, fetcher, hosted, mapper,
        negativeCache, assetMetadataCache, cacheController,
        new NpmReleaseAgeCache(10000, 16L * 1024 * 1024, 30), null,
        Clock.systemUTC());
  }

  NpmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ObjectMapper mapper,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      Clock clock) {
    this(assetDao, blobStorageRegistry, writer, proxyStateDao, fetcher, hosted, mapper,
        negativeCache, assetMetadataCache, cacheController,
        new NpmReleaseAgeCache(10000, 16L * 1024 * 1024, 30), null, clock);
  }

  NpmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ObjectMapper mapper,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      NpmReleaseAgeCache releaseAgeCache,
      Clock clock) {
    this(assetDao, blobStorageRegistry, writer, proxyStateDao, fetcher, hosted, mapper,
        negativeCache, assetMetadataCache, cacheController, releaseAgeCache, null, clock);
  }

  NpmProxyService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ProxyStateDao proxyStateDao,
      HttpRemoteFetcher fetcher,
      NpmHostedService hosted,
      ObjectMapper mapper,
      ProxyNegativeCache negativeCache,
      AssetMetadataCache assetMetadataCache,
      NexusLikeCacheController cacheController,
      NpmReleaseAgeCache releaseAgeCache,
      NpmReleaseIndexDao releaseIndexDao,
      Clock clock) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.proxyStateDao = proxyStateDao;
    this.fetcher = fetcher;
    this.hosted = hosted;
    this.mapper = mapper;
    this.negativeCache = negativeCache;
    this.assetMetadataCache = assetMetadataCache;
    this.cacheController = cacheController;
    this.releaseAgeCache = releaseAgeCache == null
        ? new NpmReleaseAgeCache(10000, 16L * 1024 * 1024, 30)
        : releaseAgeCache;
    this.releaseIndexDao = releaseIndexDao;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public MavenResponse get(RepositoryRuntime runtime, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
    return get(runtime, path, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("NpmProxyService.get called on non-proxy " + runtime.name());
    }
    return switch (path.kind()) {
      case PACKAGE_ROOT, PACKAGE_VERSION -> getPackage(runtime, path.packageId(), repositoryBaseUrl, headOnly, variant);
      case TARBALL -> getTarball(runtime, path.packageId(), path.tarballName(), headOnly);
      case DIST_TAGS -> getDistTags(runtime, path.packageId(), headOnly);
      default -> throw new NpmExceptions.NpmNotFoundException(path.rawPath());
    };
  }

  public Map<String, Object> search(RepositoryRuntime runtime, String keyword, int limit) {
    if (!runtime.isProxy()) {
      throw new IllegalStateException("NpmProxyService.search called on non-proxy " + runtime.name());
    }
    String text = keyword == null ? "" : keyword;
    String url = RemoteUrlBuilder.repositoryPathWithQueryString(
        runtime.proxyRemoteUrl(),
        "-/v1/search",
        "text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
            + "&size=" + Math.max(1, limit));
    Instant now = clock.instant();
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, null, null, null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.SEARCH)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, "-/v1/search", result -> {
        int status = result.status();
        if (status >= 200 && status < 300) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          return mapper.readValue(UpstreamBodyReadException.readAllBytes(result.body()), MAP_TYPE);
        }
        handleFailure(runtime, Optional.empty(), "Upstream search returned " + status, now);
        return Map.of("objects", List.of(), "total", 0, "time", "0ms");
      });
    } catch (IOException e) {
      handleFailure(runtime, Optional.empty(), "Upstream search IO error: " + e.getMessage(), now);
      return Map.of("objects", List.of(), "total", 0, "time", "0ms");
    }
  }

  public MavenResponse getPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean headOnly) {
    return getPackage(runtime, packageId, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  public MavenResponse getPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    if (runtime.minimumReleaseAgeEnabled()) {
      PolicyPackage resolved = resolvePolicyPackage(runtime, packageId, clock.instant());
      return policyPackageResponse(runtime, packageId, repositoryBaseUrl, resolved, headOnly, variant);
    }
    String path = packageId.id();
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = clock.instant();
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now, NexusCacheType.METADATA)) {
      return hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant);
      throw new NpmExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    MavenResponse response = fetchAndCachePackage(runtime, packageId, repositoryBaseUrl, cached, headOnly, variant, now);
    return response == null ? hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant) : response;
  }

  public MavenResponse getDistTags(RepositoryRuntime runtime, NpmPackageId packageId, boolean headOnly) {
    if (runtime.minimumReleaseAgeEnabled()) {
      PolicyPackage resolved = resolvePolicyPackage(runtime, packageId, clock.instant());
      byte[] bytes = releaseAgeCache.response(
          resolved.metadata(),
          runtime.minimumReleaseAgeMinutesOrDefault(),
          resolved.analysis(),
          resolved.evaluatedAt(),
          "dist-tags",
          "",
          () -> NpmResponseSupport.write(
              mapper,
              resolved.analysis().filteredDistTags(
                  resolved.root().get(), resolved.evaluatedAt())));
      if (headOnly) {
        return MavenResponse.noBody(
            200, bytes.length, NpmResponseSupport.JSON, null, resolved.lastModified());
      }
      return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
          NpmResponseSupport.JSON, null, resolved.lastModified());
    }
    getPackage(runtime, packageId, runtime.name(), true);
    return hosted.getDistTags(runtime, packageId, headOnly);
  }

  public MavenResponse getTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      boolean headOnly) {
    if (runtime.minimumReleaseAgeEnabled()) {
      enforceTarballReleaseAge(runtime, packageId, tarballName, clock.instant());
    }
    String path = packageId.tarballPath(tarballName);
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    Instant now = clock.instant();
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.contentMaxAgeMinutesOrDefault(), now, NexusCacheType.CONTENT)) {
      return hosted.getTarball(runtime, packageId, tarballName, headOnly);
    }
    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new NpmExceptions.NpmNotFoundException(
          "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) return hosted.getTarball(runtime, packageId, tarballName, headOnly);
      throw new NpmExceptions.BadUpstreamException("Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    NpmAssetWriter.Stored stored = fetchAndCacheTarball(runtime, packageId, tarballName, cached, headOnly, now);
    if (stored != null) {
      return tarballResponseFromStored(stored, headOnly);
    }
    return hosted.getTarball(runtime, packageId, tarballName, headOnly);
  }

  private PolicyPackage resolvePolicyPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      Instant now) {
    String path = packageId.id();
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, path);
    int minimumAge = runtime.minimumReleaseAgeMinutesOrDefault();
    Instant lastVerifiedAt = cached.map(CachedAssetMetadata::lastUpdatedAt).orElse(null);
    Instant cachedEvaluationTime = safeEvaluationTime(lastVerifiedAt, now);

    PolicyPackage cachedPolicy = null;
    if (cached.isPresent()
        && isFresh(runtime, cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now,
            NexusCacheType.METADATA)) {
      cachedPolicy = cachedPolicy(cached.get(), minimumAge, cachedEvaluationTime);
      boolean verifiedFull = isFullMetadataVerified(cached.get())
          || cachedPolicy.analysis().hasCompletePublishTimes();
      if (verifiedFull
          && !cachedPolicy.analysis().crossedMaturityBoundary(lastVerifiedAt, now)) {
        return cachedPolicy;
      }
    }

    if (negativeCache.isNotFoundCached(runtime, path)) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    if (proxyStateDao.isBlocked(runtime.id(), now)) {
      if (cached.isPresent()) {
        return cachedPolicy == null
            ? cachedPolicy(cached.get(), minimumAge, cachedEvaluationTime)
            : cachedPolicy;
      }
      throw new NpmExceptions.BadUpstreamException(
          "Upstream temporarily blocked: " + runtime.proxyRemoteUrl());
    }
    return fetchPolicyPackage(runtime, packageId, cached, cachedPolicy, now);
  }

  private PolicyPackage fetchPolicyPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      Optional<CachedAssetMetadata> cached,
      PolicyPackage alreadyLoadedPolicy,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), remotePackagePath(packageId));
    boolean requestFullMetadata = cached.isEmpty()
        || !(isFullMetadataVerified(cached.get())
            || (alreadyLoadedPolicy != null
                && alreadyLoadedPolicy.analysis().hasCompletePublishTimes()));
    Conditional conditional = requestFullMetadata
        ? new Conditional(null, null)
        : conditional(cached);
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, conditional.etag(), conditional.lastModified(), null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withAccept("application/json")
        .withRepository(runtime);
    Instant priorVerification = cached.map(CachedAssetMetadata::lastUpdatedAt).orElse(null);
    Instant priorEvaluationTime = safeEvaluationTime(priorVerification, now);
    int minimumAge = runtime.minimumReleaseAgeMinutesOrDefault();
    try {
      return fetcher.fetchWithBodyRetry(req, packageId.id(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(
              runtime, cached.get(), NexusCacheType.METADATA, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), packageId.id(), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, packageId.id());
          PolicyPackage policy = alreadyLoadedPolicy == null
              ? cachedPolicy(cached.get(), minimumAge, priorEvaluationTime)
              : alreadyLoadedPolicy;
          CachedAssetMetadata refreshed = attributes == null
              ? cached.get().withLastUpdatedAt(now)
              : cached.get().withLastUpdatedAtAndAttributes(now, attributes);
          return new PolicyPackage(
              refreshed, policy.root(), policy.analysis(), now, now);
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, packageId.id());
          CachedPackage stored = persistPackage(runtime, packageId, result, now, minimumAge);
          return new PolicyPackage(
              stored.metadata(),
              PackageRootHolder.loaded(stored.packageRoot()),
              stored.analysis(),
              stored.lastModified(),
              now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return alreadyLoadedPolicy == null
                ? cachedPolicy(cached.get(), minimumAge, priorEvaluationTime)
                : alreadyLoadedPolicy;
          }
          if (status == 404) {
            negativeCache.rememberNotFound(runtime, packageId.id());
          }
          throw new NpmExceptions.NpmNotFoundException(
              "Package '" + packageId.id() + "' not found");
        }
        handleFailure(runtime, cached, "Upstream returned " + status, now);
        if (cached.isPresent()) {
          return alreadyLoadedPolicy == null
              ? cachedPolicy(cached.get(), minimumAge, priorEvaluationTime)
              : alreadyLoadedPolicy;
        }
        throw new NpmExceptions.BadUpstreamException("Upstream returned " + status);
      });
    } catch (IOException e) {
      handleFailure(runtime, cached, "Upstream IO error: " + e.getMessage(), now);
      if (cached.isPresent()) {
        return alreadyLoadedPolicy == null
            ? cachedPolicy(cached.get(), minimumAge, priorEvaluationTime)
            : alreadyLoadedPolicy;
      }
      throw new NpmExceptions.BadUpstreamException("Upstream IO error: " + e.getMessage());
    }
  }

  private MavenResponse policyPackageResponse(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      PolicyPackage resolved,
      boolean headOnly,
      NpmPackumentVariant variant) {
    byte[] bytes = releaseAgeCache.response(
        resolved.metadata(),
        runtime.minimumReleaseAgeMinutesOrDefault(),
        resolved.analysis(),
        resolved.evaluatedAt(),
        "package-" + variant.name(),
        repositoryBaseUrl,
        () -> NpmPackumentResponseWriter.write(
            mapper,
            resolved.root().get(),
            resolved.analysis(),
            resolved.evaluatedAt(),
            variant,
            packageId,
            repositoryBaseUrl));
    MavenResponse response = headOnly
        ? MavenResponse.noBody(
            200, bytes.length, NpmResponseSupport.JSON, null, resolved.lastModified())
        : MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
            NpmResponseSupport.JSON, null, resolved.lastModified());
    return response.withInternalAttribute(
        POLICY_VALID_UNTIL_CONTEXT,
        resolved.analysis().nextMaturityAfter(resolved.evaluatedAt()).orElse(null));
  }

  private void enforceTarballReleaseAge(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      Instant now) {
    if (tryEnforceIndexedTarballReleaseAge(runtime, packageId, tarballName, now)) {
      return;
    }
    PolicyPackage resolved = resolvePolicyPackage(runtime, packageId, now);
    List<String> versions = resolved.analysis().versionsForTarball(tarballName);
    if (versions.isEmpty()) {
      throw new NpmExceptions.ReleaseAgeDenied(
          "Tarball '" + tarballName + "' is blocked by minimumReleaseAge because its version "
              + "cannot be verified from upstream package metadata");
    }
    for (String version : versions) {
      NpmMinimumReleaseAge.Eligibility eligibility = resolved.analysis().eligibility(
          version, resolved.evaluatedAt());
      if (eligibility.eligible()) {
        continue;
      }
      String available = eligibility.availableAt() == null
          ? "unknown (missing or invalid publish time)"
          : eligibility.availableAt().toString();
      throw new NpmExceptions.ReleaseAgeDenied(
          "Tarball '" + tarballName + "' for " + packageId.id() + "@" + version
              + " is blocked by minimumReleaseAge until " + available);
    }
  }

  private boolean tryEnforceIndexedTarballReleaseAge(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      Instant now) {
    if (releaseIndexDao == null) {
      return false;
    }
    Optional<CachedAssetMetadata> cached = lookupCached(runtime, packageId.id());
    if (cached.isEmpty() || cached.get().blob() == null
        || !isFresh(runtime, cached.get(), runtime.metadataMaxAgeMinutesOrDefault(), now,
            NexusCacheType.METADATA)) {
      return false;
    }

    CachedAssetMetadata metadata = cached.get();
    Instant publishedAfterExclusive = null;
    Instant publishedAtOrBefore = null;
    if (metadata.lastUpdatedAt() != null && now != null && now.isAfter(metadata.lastUpdatedAt())) {
      try {
        Duration minimumAge = Duration.ofMinutes(runtime.minimumReleaseAgeMinutesOrDefault());
        publishedAfterExclusive = metadata.lastUpdatedAt().minus(minimumAge);
        publishedAtOrBefore = now.minus(minimumAge);
      } catch (ArithmeticException | DateTimeException e) {
        return false;
      }
    }

    Optional<NpmReleaseIndexDao.TarballPolicy> indexed = releaseIndexDao.findTarballPolicy(
        metadata.assetId(),
        metadata.blob().id(),
        tarballName,
        publishedAfterExclusive,
        publishedAtOrBefore);
    if (indexed.isEmpty()) {
      // One-time upgrade path for package roots written before the durable index existed.
      backfillLegacyReleaseIndex(metadata, runtime.minimumReleaseAgeMinutesOrDefault());
      indexed = releaseIndexDao.findTarballPolicy(
          metadata.assetId(),
          metadata.blob().id(),
          tarballName,
          publishedAfterExclusive,
          publishedAtOrBefore);
    }
    if (indexed.isEmpty() || !(isFullMetadataVerified(metadata)
        || indexed.get().status().completePublishTimes())) {
      return false;
    }
    if (indexed.get().maturityBoundaryCrossed()) {
      return false;
    }
    enforceIndexedTarballRows(
        runtime,
        packageId,
        tarballName,
        safeEvaluationTime(metadata.lastUpdatedAt(), now),
        indexed.get().releases());
    return true;
  }

  private void backfillLegacyReleaseIndex(CachedAssetMetadata metadata, int minimumAge) {
    Map<String, Object> packageRoot = hosted.packageRoot(metadata)
        .orElseThrow(() -> new IllegalStateException(
            "Cached npm package root blob is missing for " + metadata.path()));
    NpmMinimumReleaseAge.ReleaseIndex releaseIndex = NpmMinimumReleaseAge.index(packageRoot);
    backfillReleaseIndex(metadata, releaseIndex);
    releaseAgeCache.remember(
        metadata, minimumAge, NpmMinimumReleaseAge.analyze(releaseIndex, minimumAge));
  }

  private void enforceIndexedTarballRows(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      Instant evaluatedAt,
      List<NpmReleaseIndexDao.Release> rows) {
    if (rows.isEmpty()) {
      throw new NpmExceptions.ReleaseAgeDenied(
          "Tarball '" + tarballName + "' is blocked by minimumReleaseAge because its version "
              + "cannot be verified from upstream package metadata");
    }
    NpmMinimumReleaseAge.Analysis analysis = NpmMinimumReleaseAge.analyze(
        toReleaseIndex(rows), runtime.minimumReleaseAgeMinutesOrDefault());
    for (NpmReleaseIndexDao.Release row : rows) {
      NpmMinimumReleaseAge.Eligibility eligibility = analysis.eligibility(
          row.version(), evaluatedAt);
      if (eligibility.eligible()) {
        continue;
      }
      String available = eligibility.availableAt() == null
          ? "unknown (missing or invalid publish time)"
          : eligibility.availableAt().toString();
      throw new NpmExceptions.ReleaseAgeDenied(
          "Tarball '" + tarballName + "' for " + packageId.id() + "@" + row.version()
              + " is blocked by minimumReleaseAge until " + available);
    }
  }

  Optional<Instant> nextPolicyTransition(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      Instant now) {
    if (!runtime.minimumReleaseAgeEnabled()) {
      return Optional.empty();
    }
    PolicyPackage resolved = resolvePolicyPackage(runtime, packageId, now);
    return resolved.analysis().nextMaturityAfter(resolved.evaluatedAt());
  }

  private PolicyPackage cachedPolicy(
      CachedAssetMetadata metadata,
      int minimumAge,
      Instant evaluatedAt) {
    PackageRootHolder root = new PackageRootHolder(() -> hosted.packageRoot(metadata)
        .orElseThrow(() -> new IllegalStateException(
            "Cached npm package root blob is missing for " + metadata.path())));
    NpmMinimumReleaseAge.Analysis analysis = releaseAgeCache.analysis(
        metadata, minimumAge, () -> loadIndexedAnalysis(metadata, minimumAge, root));
    return new PolicyPackage(metadata, root, analysis, metadata.lastUpdatedAt(), evaluatedAt);
  }

  private NpmMinimumReleaseAge.Analysis loadIndexedAnalysis(
      CachedAssetMetadata metadata,
      int minimumAge,
      PackageRootHolder root) {
    if (releaseIndexDao != null && metadata.blob() != null) {
      Optional<NpmReleaseIndexDao.Snapshot> snapshot = releaseIndexDao.findSnapshot(
          metadata.assetId(), metadata.blob().id());
      if (snapshot.isPresent()) {
        return NpmMinimumReleaseAge.analyze(toReleaseIndex(snapshot.get().releases()), minimumAge);
      }
    }

    NpmMinimumReleaseAge.ReleaseIndex releaseIndex = NpmMinimumReleaseAge.index(root.get());
    backfillReleaseIndex(metadata, releaseIndex);
    return NpmMinimumReleaseAge.analyze(releaseIndex, minimumAge);
  }

  private void backfillReleaseIndex(
      CachedAssetMetadata metadata,
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    if (releaseIndexDao == null || metadata == null || metadata.blob() == null) {
      return;
    }
    releaseIndexDao.replaceIfCurrent(
        metadata.assetId(),
        metadata.blob().id(),
        releaseIndex.releases().stream()
            .allMatch(release -> release.invalidReason() == null),
        toReleaseRows(releaseIndex),
        clock.instant());
  }

  private static NpmMinimumReleaseAge.ReleaseIndex toReleaseIndex(
      List<NpmReleaseIndexDao.Release> rows) {
    return new NpmMinimumReleaseAge.ReleaseIndex(rows.stream()
        .map(row -> new NpmMinimumReleaseAge.IndexedRelease(
            row.version(), row.publishedAt(), row.invalidReason(), row.tarballName()))
        .toList());
  }

  private static List<NpmReleaseIndexDao.Release> toReleaseRows(
      NpmMinimumReleaseAge.ReleaseIndex releaseIndex) {
    java.util.ArrayList<NpmReleaseIndexDao.Release> rows = new java.util.ArrayList<>();
    int ordinal = 0;
    for (NpmMinimumReleaseAge.IndexedRelease release : releaseIndex.releases()) {
      rows.add(new NpmReleaseIndexDao.Release(
          ordinal++, release.version(), release.publishedAt(), release.invalidReason(),
          release.tarballName()));
    }
    return List.copyOf(rows);
  }

  private static boolean isFullMetadataVerified(CachedAssetMetadata metadata) {
    if (metadata == null || metadata.blob() == null || metadata.blob().attributes() == null) {
      return false;
    }
    Object value = metadata.blob().attributes().get(FULL_METADATA_ATTRIBUTE);
    return value != null && Boolean.parseBoolean(value.toString());
  }

  private static Instant safeEvaluationTime(Instant lastVerifiedAt, Instant now) {
    if (lastVerifiedAt == null || now == null) {
      return null;
    }
    return lastVerifiedAt.isBefore(now) ? lastVerifiedAt : now;
  }

  private record PolicyPackage(
      CachedAssetMetadata metadata,
      PackageRootHolder root,
      NpmMinimumReleaseAge.Analysis analysis,
      Instant lastModified,
      Instant evaluatedAt) {
  }

  private static final class PackageRootHolder {
    private Supplier<Map<String, Object>> loader;
    private Map<String, Object> value;

    private PackageRootHolder(Supplier<Map<String, Object>> loader) {
      this.loader = loader;
    }

    static PackageRootHolder loaded(Map<String, Object> value) {
      PackageRootHolder holder = new PackageRootHolder(null);
      holder.value = Objects.requireNonNull(value, "value");
      return holder;
    }

    synchronized Map<String, Object> get() {
      if (value == null) {
        value = Objects.requireNonNull(loader.get(), "package root loader returned null");
        loader = null;
      }
      return value;
    }
  }

  private MavenResponse tarballResponseFromStored(NpmAssetWriter.Stored stored, boolean headOnly) {
    try {
      if (headOnly) {
        stored.discardBody();
        return MavenResponse.noBody(200, stored.blob().size(), stored.asset().contentType(),
            stored.blob().sha1(), stored.asset().lastUpdatedAt());
      }
      return MavenResponse.ok(stored.openBody(), stored.blob().size(), stored.asset().contentType(),
          stored.blob().sha1(), stored.asset().lastUpdatedAt());
    } catch (RuntimeException e) {
      stored.discardBody();
      throw e;
    }
  }

  private MavenResponse fetchAndCachePackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      NpmPackumentVariant variant,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), remotePackagePath(packageId));
    Conditional conditional = conditional(cached);
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, conditional.etag(), conditional.lastModified(), null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.METADATA)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, packageId.id(), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, cached.get(), NexusCacheType.METADATA, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), packageId.id(), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, packageId.id());
          return null;
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, packageId.id());
          CachedPackage stored = persistPackage(runtime, packageId, result, now);
          return packageResponse(packageId, repositoryBaseUrl, stored, headOnly, variant);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return hosted.getPackage(runtime, packageId, repositoryBaseUrl, headOnly, variant);
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, packageId.id());
          throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
        }
        handleFailure(runtime, cached, "Upstream returned " + status, now);
        return null;
      });
    } catch (IOException e) {
      handleFailure(runtime, cached, "Upstream IO error: " + e.getMessage(), now);
      return null;
    }
  }

  private CachedPackage persistPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      HttpRemoteFetcher.Result result,
      Instant now) throws IOException {
    return persistPackage(runtime, packageId, result, now, null);
  }

  private CachedPackage persistPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      HttpRemoteFetcher.Result result,
      Instant now,
      Integer minimumReleaseAgeMinutes) throws IOException {
    String contentType = result.contentType();
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    byte[] packageBytes = UpstreamBodyReadException.readAllBytes(result.body());
    Map<String, Object> packageRoot = mapper.readValue(packageBytes, MAP_TYPE);
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, String> extras = remoteAttributes(result);
    NpmMinimumReleaseAge.ReleaseIndex releaseIndex = minimumReleaseAgeMinutes == null
        ? null
        : NpmMinimumReleaseAge.index(packageRoot);
    NpmMinimumReleaseAge.Analysis analysis = releaseIndex == null
        ? null
        : NpmMinimumReleaseAge.analyze(releaseIndex, minimumReleaseAgeMinutes);
    if (analysis != null) {
      extras.put(FULL_METADATA_ATTRIBUTE, Boolean.TRUE.toString());
      extras.put(
          COMPLETE_PUBLISH_TIMES_ATTRIBUTE,
          Boolean.toString(analysis.hasCompletePublishTimes()));
    }
    NpmAssetWriter.Stored stored = releaseIndex == null
        ? writer.writePackageRoot(runtime, storage, blobStoreId, packageId,
            packageBytes, "proxy", runtime.proxyRemoteUrl(), extras)
        : writer.writePackageRoot(runtime, storage, blobStoreId, packageId,
            packageBytes, "proxy", runtime.proxyRemoteUrl(), extras, releaseIndex);
    updateCacheInfo(runtime, stored.asset(), NexusCacheType.METADATA, now);
    proxyStateDao.recordSuccess(runtime.id(), now);
    CachedAssetMetadata metadata = CachedAssetMetadata.of(stored.asset(), stored.blob());
    if (analysis != null) {
      releaseAgeCache.remember(metadata, minimumReleaseAgeMinutes, analysis);
    }
    return new CachedPackage(packageRoot, stored.asset().lastUpdatedAt(), metadata, analysis);
  }

  private MavenResponse packageResponse(
      NpmPackageId packageId,
      String repositoryBaseUrl,
      CachedPackage stored,
      boolean headOnly,
      NpmPackumentVariant variant) {
    byte[] bytes = NpmPackumentResponseWriter.write(
        mapper,
        stored.packageRoot(),
        null,
        null,
        variant,
        packageId,
        repositoryBaseUrl);
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, NpmResponseSupport.JSON, null, stored.lastModified());
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        NpmResponseSupport.JSON, null, stored.lastModified());
  }

  private record CachedPackage(
      Map<String, Object> packageRoot,
      Instant lastModified,
      CachedAssetMetadata metadata,
      NpmMinimumReleaseAge.Analysis analysis) {
    private CachedPackage(Map<String, Object> packageRoot, Instant lastModified) {
      this(packageRoot, lastModified, null, null);
    }
  }

  private NpmAssetWriter.Stored fetchAndCacheTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      Optional<CachedAssetMetadata> cached,
      boolean headOnly,
      Instant now) {
    String url = buildRemoteUrl(runtime.proxyRemoteUrl(), packageId.tarballPath(tarballName));
    Conditional conditional = conditional(cached);
    HttpRemoteFetcher.Request req = new HttpRemoteFetcher.Request(
        url, conditional.etag(), conditional.lastModified(), null, false)
        .withTimeoutProfile(HttpRemoteFetcher.TimeoutProfile.CONTENT)
        .withRepository(runtime);
    try {
      return fetcher.fetchWithBodyRetry(req, packageId.tarballPath(tarballName), result -> {
        int status = result.status();
        if (status == 304 && cached.isPresent()) {
          Map<String, Object> attributes = refreshedAttributes(runtime, cached.get(), NexusCacheType.CONTENT, now);
          if (attributes == null) {
            assetDao.touchAssetLastUpdated(cached.get().assetId(), now);
          } else {
            assetDao.touchAssetLastUpdatedAndAttributes(cached.get().assetId(), now, attributes);
          }
          assetMetadataCache.touchVerified(runtime.id(), packageId.tarballPath(tarballName), now, attributes);
          proxyStateDao.recordSuccess(runtime.id(), now);
          negativeCache.invalidate(runtime, packageId.tarballPath(tarballName));
          return null;
        }
        if (status >= 200 && status < 300) {
          negativeCache.invalidate(runtime, packageId.tarballPath(tarballName));
          return persistTarball(runtime, packageId, tarballName, result, !headOnly, now);
        }
        if (status == 404 || status == 410) {
          proxyStateDao.recordSuccess(runtime.id(), now);
          if (cached.isPresent()) {
            return null;
          }
          if (status == 404) negativeCache.rememberNotFound(runtime, packageId.tarballPath(tarballName));
          throw new NpmExceptions.NpmNotFoundException(
              "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
        }
        handleFailure(runtime, cached, "Upstream returned " + status, now);
        return null;
      });
    } catch (IOException e) {
      handleFailure(runtime, cached, "Upstream IO error: " + e.getMessage(), now);
      return null;
    }
  }

  private NpmAssetWriter.Stored persistTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      HttpRemoteFetcher.Result result,
      boolean keepResponseFile,
      Instant now) {
    String contentType = result.contentType();
    if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
      throw new NpmExceptions.NpmNotFoundException(
          "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
    }
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    NpmAssetWriter.Stored stored = writer.writeTarball(runtime, storage, blobStoreId, packageId,
        inferVersion(packageId, tarballName), tarballName, result.body(),
        contentType, "proxy", runtime.proxyRemoteUrl(), remoteAttributes(result), keepResponseFile);
    updateCacheInfo(runtime, stored.asset(), NexusCacheType.CONTENT, now);
    proxyStateDao.recordSuccess(runtime.id(), now);
    return stored;
  }

  private void handleFailure(RepositoryRuntime runtime, Optional<CachedAssetMetadata> cached, String error, Instant now) {
    int failCount = proxyStateDao.loadState(runtime.id())
        .map(ProxyStateDao.ProxyRemoteState::failCount).orElse(0);
    long block = runtime.autoBlockOrDefault()
        ? BACKOFF_SECONDS[Math.min(failCount, BACKOFF_SECONDS.length - 1)]
        : 0;
    proxyStateDao.recordFailure(runtime.id(), block, error, now);
    if (cached.isPresent()) {
      return;
    }
    throw new NpmExceptions.BadUpstreamException(error);
  }

  private boolean isFresh(
      RepositoryRuntime runtime,
      CachedAssetMetadata snapshot,
      int ttlMinutes,
      Instant now,
      NexusCacheType type) {
    Optional<NexusLikeCacheInfo> cacheInfo = NexusLikeCacheInfo.fromAttributes(snapshot.attributes());
    if (cacheController != null && cacheInfo.isPresent()) {
      return !cacheController.isStale(runtime.id(), type, cacheInfo.get(), ttlMinutes, now);
    }
    if (snapshot.lastUpdatedAt() == null) return false;
    if (ttlMinutes < 0) return true;
    return snapshot.lastUpdatedAt().plusSeconds(ttlMinutes * 60L).isAfter(now);
  }

  private Conditional conditional(Optional<CachedAssetMetadata> cached) {
    if (cached.isEmpty() || cached.get().blob() == null) {
      return new Conditional(null, null);
    }
    String etag = stringAttr(cached.get().blob().attributes(), "remoteEtag");
    Instant lastModified = null;
    String lm = stringAttr(cached.get().blob().attributes(), "remoteLastModified");
    if (lm != null) {
      try { lastModified = Instant.parse(lm); } catch (RuntimeException ignored) {}
    }
    return new Conditional(etag, lastModified);
  }

  private Map<String, String> remoteAttributes(HttpRemoteFetcher.Result result) {
    Map<String, String> extras = new HashMap<>();
    if (result.etag() != null) extras.put("remoteEtag", result.etag());
    if (result.lastModified() != null) extras.put("remoteLastModified", result.lastModified().toString());
    return extras;
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private void updateCacheInfo(
      RepositoryRuntime runtime,
      AssetRecord asset,
      NexusCacheType type,
      Instant now) {
    if (cacheController == null || asset == null) {
      return;
    }
    Map<String, Object> attrs = new HashMap<>(asset.attributes() == null ? Map.of() : asset.attributes());
    Map<String, Object> refreshed =
        NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), type, now));
    assetDao.updateAssetAttributes(asset.id(), refreshed);
  }

  private Map<String, Object> refreshedAttributes(
      RepositoryRuntime runtime,
      CachedAssetMetadata snapshot,
      NexusCacheType type,
      Instant now) {
    if (cacheController == null) {
      return null;
    }
    Map<String, Object> attrs = new HashMap<>(snapshot.attributes() == null ? Map.of() : snapshot.attributes());
    return NexusLikeCacheInfo.applyToAttributes(attrs, cacheController.current(runtime.id(), type, now));
  }

  private String inferVersion(NpmPackageId packageId, String tarballName) {
    String prefix = packageId.name() + "-";
    if (tarballName.startsWith(prefix) && tarballName.endsWith(".tgz")) {
      return tarballName.substring(prefix.length(), tarballName.length() - ".tgz".length());
    }
    return "";
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Proxy " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private String remotePackagePath(NpmPackageId packageId) {
    if (packageId.scope() == null) {
      return packageId.name();
    }
    return "@" + packageId.scope() + "%2F" + packageId.name();
  }

  private static String buildRemoteUrl(String base, String path) {
    return RemoteUrlBuilder.repositoryPathString(base, path);
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    if (attrs == null) return null;
    Object v = attrs.get(key);
    return v == null ? null : v.toString();
  }

  private record Conditional(String etag, Instant lastModified) {}
}
