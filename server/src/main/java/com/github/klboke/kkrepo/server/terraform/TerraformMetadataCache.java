package com.github.klboke.kkrepo.server.terraform;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheInfo;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recoverable shared cache for small Terraform protocol documents.
 *
 * <p>The cache never owns correctness. Provider route assets and upstream metadata assets remain the
 * durable source of truth; cache loss only makes the next request verify and rebuild the snapshot.
 */
@Component
final class TerraformMetadataCache {
  private static final Logger log = LoggerFactory.getLogger(TerraformMetadataCache.class);
  private static final String NAMESPACE = "terraform-metadata";

  private final SharedCache sharedCache;
  private final NexusLikeCacheController cacheController;
  private final boolean enabled;

  TerraformMetadataCache(
      SharedCache sharedCache,
      NexusLikeCacheController cacheController,
      @Value("${kkrepo.cache.terraform-metadata.enabled:true}") boolean enabled) {
    this.sharedCache = sharedCache;
    this.cacheController = cacheController;
    this.enabled = enabled;
  }

  Optional<ProviderSnapshot> findProvider(
      RepositoryRuntime runtime, String identity, int maxAgeMinutes, Instant now) {
    if (!cacheable(maxAgeMinutes)) {
      return Optional.empty();
    }
    try {
      Optional<ProviderSnapshot> snapshot =
          sharedCache.getJson(NAMESPACE, key("provider", runtime.id(), identity), ProviderSnapshot.class);
      if (snapshot.isEmpty() || cacheController.isStale(
          runtime.id(), NexusCacheType.METADATA, snapshot.get().cacheInfo(), maxAgeMinutes, now)) {
        return Optional.empty();
      }
      return snapshot;
    } catch (RuntimeException e) {
      log.warn("Failed reading Terraform provider metadata cache for repository {}", runtime.id(), e);
      return Optional.empty();
    }
  }

  void putProvider(
      RepositoryRuntime runtime,
      String identity,
      int maxAgeMinutes,
      Instant now,
      Map<String, Object> body,
      String localDownload,
      String localSums,
      String localSignature) {
    if (!cacheable(maxAgeMinutes)) {
      return;
    }
    try {
      sharedCache.putJson(
          NAMESPACE,
          key("provider", runtime.id(), identity),
          new ProviderSnapshot(
              new LinkedHashMap<>(body),
              localDownload,
              localSums,
              localSignature,
              cacheController.current(runtime.id(), NexusCacheType.METADATA, now)),
          Duration.ofMinutes(maxAgeMinutes));
    } catch (RuntimeException e) {
      log.warn("Failed writing Terraform provider metadata cache for repository {}", runtime.id(), e);
    }
  }

  Optional<GroupSnapshot> findGroup(
      RepositoryRuntime group,
      List<RepositoryRuntime> members,
      String identity,
      int maxAgeMinutes,
      Instant now) {
    if (!cacheable(maxAgeMinutes)) {
      return Optional.empty();
    }
    try {
      Optional<GroupSnapshot> snapshot =
          sharedCache.getJson(NAMESPACE, key("group", group.id(), identity), GroupSnapshot.class);
      if (snapshot.isEmpty() || cacheController.isStale(
          group.id(), NexusCacheType.METADATA, snapshot.get().cacheInfo(), maxAgeMinutes, now)) {
        return Optional.empty();
      }
      for (RepositoryRuntime member : members) {
        if (member == null) {
          continue;
        }
        String cachedToken = snapshot.get().memberTokens().get(Long.toString(member.id()));
        if (!java.util.Objects.equals(
            cachedToken, cacheController.currentToken(member.id(), NexusCacheType.METADATA))) {
          return Optional.empty();
        }
      }
      return snapshot;
    } catch (RuntimeException e) {
      log.warn("Failed reading Terraform group metadata cache for repository {}", group.id(), e);
      return Optional.empty();
    }
  }

  void putGroup(
      RepositoryRuntime group,
      List<RepositoryRuntime> members,
      String identity,
      int maxAgeMinutes,
      Instant now,
      Map<String, Object> body) {
    if (!cacheable(maxAgeMinutes)) {
      return;
    }
    try {
      Map<String, String> memberTokens = new LinkedHashMap<>();
      for (RepositoryRuntime member : members) {
        if (member != null) {
          memberTokens.put(
              Long.toString(member.id()),
              cacheController.currentToken(member.id(), NexusCacheType.METADATA));
        }
      }
      sharedCache.putJson(
          NAMESPACE,
          key("group", group.id(), identity),
          new GroupSnapshot(
              new LinkedHashMap<>(body),
              memberTokens,
              cacheController.current(group.id(), NexusCacheType.METADATA, now)),
          Duration.ofMinutes(maxAgeMinutes));
    } catch (RuntimeException e) {
      log.warn("Failed writing Terraform group metadata cache for repository {}", group.id(), e);
    }
  }

  void invalidateMemberAfterCommit(long repositoryId) {
    cacheController.invalidateAfterCommit(repositoryId, NexusCacheType.METADATA);
  }

  private boolean cacheable(int maxAgeMinutes) {
    return enabled && sharedCache != null && cacheController != null && maxAgeMinutes > 0;
  }

  private static String key(String kind, long repositoryId, String identity) {
    return kind + ":" + repositoryId + ":" + sha256(identity);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  record ProviderSnapshot(
      Map<String, Object> body,
      String localDownload,
      String localSums,
      String localSignature,
      NexusLikeCacheInfo cacheInfo) {}

  record GroupSnapshot(
      Map<String, Object> body,
      Map<String, String> memberTokens,
      NexusLikeCacheInfo cacheInfo) {}
}
