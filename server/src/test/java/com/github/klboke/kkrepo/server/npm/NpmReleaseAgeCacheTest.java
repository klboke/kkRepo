package com.github.klboke.kkrepo.server.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMinimumReleaseAge;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NpmReleaseAgeCacheTest {
  private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

  @Test
  void analysisIsLoadedOnceForConcurrentRequestsOnTheSameBlobRevision() throws Exception {
    NpmReleaseAgeCache cache = new NpmReleaseAgeCache(10_000, 1024 * 1024, 30);
    CachedAssetMetadata metadata = snapshot(2L, "sha256-a");
    Map<String, Object> root = packument();
    AtomicInteger loads = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch loaderStarted = new CountDownLatch(1);
    CountDownLatch releaseLoader = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    List<Future<NpmMinimumReleaseAge.Analysis>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < 20; index++) {
        futures.add(executor.submit(() -> {
          await(start);
          return cache.analysis(metadata, 60, () -> {
            loads.incrementAndGet();
            loaderStarted.countDown();
            await(releaseLoader);
            return NpmMinimumReleaseAge.analyze(root, 60);
          });
        }));
      }
      start.countDown();
      await(loaderStarted);
      releaseLoader.countDown();

      NpmMinimumReleaseAge.Analysis expected = futures.getFirst().get(5, TimeUnit.SECONDS);
      for (Future<NpmMinimumReleaseAge.Analysis> future : futures) {
        assertSame(expected, future.get(5, TimeUnit.SECONDS));
      }
      assertEquals(1, loads.get(), "Caffeine get must provide per-key single-flight loading");
    } finally {
      releaseLoader.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void analysisKeyChangesWithDurableBlobRevisionAndPolicy() {
    NpmReleaseAgeCache cache = new NpmReleaseAgeCache(10_000, 1024 * 1024, 30);
    Map<String, Object> root = packument();
    AtomicInteger loads = new AtomicInteger();

    NpmMinimumReleaseAge.Analysis first = cache.analysis(
        snapshot(2L, "sha256-a"), 60, () -> loaded(root, loads));
    assertSame(first, cache.analysis(
        snapshot(2L, "sha256-a"), 60, () -> loaded(root, loads)));
    assertNotSame(first, cache.analysis(
        snapshot(3L, "sha256-b"), 60, () -> loaded(root, loads)));
    cache.analysis(snapshot(3L, "sha256-b"), 120, () -> loaded(root, loads));

    assertEquals(3, loads.get());
  }

  @Test
  void filteredResponseIsRebuiltOnlyWhenVisibilityGenerationChanges() {
    NpmReleaseAgeCache cache = new NpmReleaseAgeCache(10_000, 1024 * 1024, 30);
    CachedAssetMetadata metadata = snapshot(2L, "sha256-a");
    Map<String, Object> root = packument();
    NpmMinimumReleaseAge.Analysis analysis = cache.analysis(
        metadata, 60, () -> NpmMinimumReleaseAge.analyze(root, 60));
    AtomicInteger builds = new AtomicInteger();

    byte[] first = cache.response(
        metadata, 60, analysis, NOW, "package-FULL", "base",
        () -> bytes(builds));
    byte[] sameGeneration = cache.response(
        metadata, 60, analysis, NOW.plusSeconds(1), "package-FULL", "base",
        () -> bytes(builds));
    byte[] afterTransition = cache.response(
        metadata, 60, analysis, Instant.parse("2026-07-19T12:30:00Z"),
        "package-FULL", "base", () -> bytes(builds));
    byte[] normalizedNullKey = cache.response(
        metadata, 60, analysis, NOW, null, null, () -> bytes(builds));

    assertSame(first, sameGeneration);
    assertNotSame(first, afterTransition);
    assertNotSame(first, normalizedNullKey);
    assertEquals(3, builds.get());
  }

  private static NpmMinimumReleaseAge.Analysis loaded(
      Map<String, Object> root,
      AtomicInteger loads) {
    loads.incrementAndGet();
    return NpmMinimumReleaseAge.analyze(root, 60);
  }

  private static byte[] bytes(AtomicInteger builds) {
    return ("body-" + builds.incrementAndGet()).getBytes(StandardCharsets.UTF_8);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for test latch");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for test latch", e);
    }
  }

  private static CachedAssetMetadata snapshot(long blobId, String sha256) {
    AssetRecord asset = new AssetRecord(
        1L, 10L, 3L, blobId, RepositoryFormat.NPM, "demo", null,
        "demo", "package-root", "application/json", 7L, null, NOW, Map.of());
    AssetBlobRecord blob = new AssetBlobRecord(
        blobId, 7L, "blob://bucket/object-" + blobId, null, "object-" + blobId, null,
        "sha1", sha256, "md5", 7L, "application/json",
        "proxy", "upstream", NOW, NOW, Map.of());
    return CachedAssetMetadata.of(asset, blob);
  }

  private static Map<String, Object> packument() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("name", "demo");
    root.put("dist-tags", new LinkedHashMap<>(Map.of("latest", "1.1.0")));
    root.put("time", new LinkedHashMap<>(Map.of(
        "1.0.0", "2026-07-19T10:00:00Z",
        "1.1.0", "2026-07-19T11:30:00Z")));
    Map<String, Object> versions = new LinkedHashMap<>();
    versions.put("1.0.0", version("1.0.0"));
    versions.put("1.1.0", version("1.1.0"));
    root.put("versions", versions);
    return root;
  }

  private static Map<String, Object> version(String version) {
    return new LinkedHashMap<>(Map.of(
        "name", "demo",
        "version", version,
        "dist", Map.of(
            "tarball", "https://registry.npmjs.org/demo/-/demo-" + version + ".tgz")));
  }
}
