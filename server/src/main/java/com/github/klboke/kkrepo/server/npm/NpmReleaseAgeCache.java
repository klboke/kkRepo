package com.github.klboke.kkrepo.server.npm;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Node-local, bounded cache for state derived from an immutable npm packument blob revision.
 *
 * <p>The raw packument in shared blob storage remains the source of truth. Keys include the durable
 * blob identity and the configured policy, so losing this cache only costs a rebuild and stale
 * entries cannot be selected after another replica stores a new packument revision. The local
 * cache's atomic {@code get(key, mappingFunction)} also gives each replica per-key single-flight
 * loading.
 */
@Service
public class NpmReleaseAgeCache {
  private final LocalCache<AnalysisKey, NpmMinimumReleaseAge.Analysis> analyses;
  private final LocalCache<ResponseKey, byte[]> responses;

  public NpmReleaseAgeCache(
      @Value("${kkrepo.cache.npm-release-age.maximum-version-entries:100000}")
      long maximumVersionEntries,
      @Value("${kkrepo.cache.npm-release-age.response-maximum-bytes:33554432}")
      long responseMaximumBytes,
      @Value("${kkrepo.cache.npm-release-age.expire-after-access-minutes:30}")
      long expireAfterAccessMinutes) {
    Duration expiry = Duration.ofMinutes(Math.max(1, expireAfterAccessMinutes));
    this.analyses = LocalCacheFactory.standard()
        .<AnalysisKey, NpmMinimumReleaseAge.Analysis>builder("npm-release-age-analyses")
        .maximumWeight(
            Math.max(1, maximumVersionEntries),
            (ignored, analysis) -> analysis.cacheWeight())
        .expireAfterAccess(expiry)
        .build();
    this.responses = LocalCacheFactory.standard()
        .<ResponseKey, byte[]>builder("npm-release-age-responses")
        .maximumWeight(
            Math.max(1, responseMaximumBytes),
            (ignored, bytes) -> Math.max(1, bytes.length))
        .expireAfterAccess(expiry)
        .build();
  }

  public NpmMinimumReleaseAge.Analysis analysis(
      CachedAssetMetadata metadata,
      int minimumReleaseAgeMinutes,
      Supplier<NpmMinimumReleaseAge.Analysis> analysisLoader) {
    AnalysisKey key = analysisKey(metadata, minimumReleaseAgeMinutes);
    return analyses.get(key, ignored -> Objects.requireNonNull(
        analysisLoader.get(), "analysisLoader returned null"));
  }

  public void remember(
      CachedAssetMetadata metadata,
      int minimumReleaseAgeMinutes,
      NpmMinimumReleaseAge.Analysis analysis) {
    analyses.put(
        analysisKey(metadata, minimumReleaseAgeMinutes),
        Objects.requireNonNull(analysis, "analysis"));
  }

  public byte[] response(
      CachedAssetMetadata metadata,
      int minimumReleaseAgeMinutes,
      NpmMinimumReleaseAge.Analysis analysis,
      Instant evaluatedAt,
      String kind,
      String scope,
      Supplier<byte[]> responseBuilder) {
    AnalysisKey analysisKey = analysisKey(metadata, minimumReleaseAgeMinutes);
    ResponseKey key = new ResponseKey(
        analysisKey,
        analysis.visibilityGeneration(evaluatedAt),
        normalize(kind),
        normalize(scope));
    return responses.get(key, ignored -> Objects.requireNonNull(
        responseBuilder.get(), "responseBuilder returned null"));
  }

  private static AnalysisKey analysisKey(
      CachedAssetMetadata metadata,
      int minimumReleaseAgeMinutes) {
    Objects.requireNonNull(metadata, "metadata");
    CachedAssetMetadata.CachedBlob blob = Objects.requireNonNull(
        metadata.blob(), "npm package-root metadata has no blob");
    return new AnalysisKey(
        metadata.repositoryId(),
        normalize(metadata.path()),
        blob.id(),
        normalize(blob.sha256()),
        minimumReleaseAgeMinutes);
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private record AnalysisKey(
      long repositoryId,
      String path,
      long blobId,
      String sha256,
      int minimumReleaseAgeMinutes) {
  }

  private record ResponseKey(
      AnalysisKey analysisKey,
      int visibilityGeneration,
      String kind,
      String scope) {
  }
}
