package com.github.klboke.kkrepo.server.cache;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NexusLikeCacheController {
  private static final Logger log = LoggerFactory.getLogger(NexusLikeCacheController.class);
  private static final String TOKEN_VERSION_PREFIX = "repo:";
  private static final String DEFAULT_TOKEN = "0";
  private static final Object PENDING_INVALIDATIONS =
      NexusLikeCacheController.class.getName() + ".PENDING_INVALIDATIONS";

  private final VersionWatermark watermark;
  private final LocalCache<String, String> localTokens;

  public NexusLikeCacheController(
      VersionWatermark watermark,
      @Value("${kkrepo.cache.repository-token.local-ttl-seconds:2}") long localTokenTtlSeconds) {
    this.watermark = watermark;
    this.localTokens = LocalCacheFactory.standard()
        .<String, String>builder("nexus-like-repository-tokens")
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, localTokenTtlSeconds)))
        .maximumSize(100_000)
        .build();
  }

  public NexusLikeCacheInfo current(long repositoryId, NexusCacheType type) {
    return current(repositoryId, type, Instant.now());
  }

  public NexusLikeCacheInfo current(long repositoryId, NexusCacheType type, Instant now) {
    return new NexusLikeCacheInfo(now, currentToken(repositoryId, type), type);
  }

  public boolean isStale(
      long repositoryId,
      NexusCacheType type,
      NexusLikeCacheInfo cacheInfo,
      int maxAgeMinutes,
      Instant now) {
    if (cacheInfo == null || cacheInfo.lastVerified() == null) {
      return true;
    }
    if (cacheInfo.invalidated()) {
      return true;
    }
    String currentToken = currentToken(repositoryId, type);
    if (currentToken != null && !currentToken.equals(cacheInfo.cacheToken())) {
      return true;
    }
    if (maxAgeMinutes < 0) {
      return false;
    }
    return !cacheInfo.lastVerified().plusSeconds(maxAgeMinutes * 60L).isAfter(now);
  }

  public String currentToken(long repositoryId, NexusCacheType type) {
    String key = tokenKey(repositoryId, type);
    try {
      return localTokens.get(key, ignored -> loadToken(key));
    } catch (RuntimeException e) {
      log.warn("Failed loading repository cache token {}", key, e);
      return DEFAULT_TOKEN;
    }
  }

  public void invalidate(long repositoryId, NexusCacheType type) {
    try {
      invalidateOrThrow(repositoryId, type);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating repository cache token {}", tokenKey(repositoryId, type), e);
    }
  }

  /** Advances a cache token and propagates persistence failures to correctness-critical callers. */
  public void invalidateOrThrow(long repositoryId, NexusCacheType type) {
    invalidateTokenKeyOrThrow(tokenKey(repositoryId, type));
  }

  public void invalidateAll(long repositoryId) {
    invalidate(repositoryId, NexusCacheType.CONTENT);
    invalidate(repositoryId, NexusCacheType.METADATA);
  }

  public void invalidateAfterCommit(long repositoryId, NexusCacheType type) {
    deferAfterCommit(tokenKey(repositoryId, type));
  }

  public void invalidateAllAfterCommit(long repositoryId) {
    invalidateAfterCommit(repositoryId, NexusCacheType.CONTENT);
    invalidateAfterCommit(repositoryId, NexusCacheType.METADATA);
  }

  private String loadToken(String key) {
    return Long.toString(watermark.current(versionName(key)));
  }

  private void deferAfterCommit(String key) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      invalidateTokenKey(key);
      return;
    }
    @SuppressWarnings("unchecked")
    Set<String> pending =
        (Set<String>) TransactionSynchronizationManager.getResource(PENDING_INVALIDATIONS);
    if (pending == null) {
      pending = new LinkedHashSet<>();
      TransactionSynchronizationManager.bindResource(PENDING_INVALIDATIONS, pending);
      Set<String> snapshot = pending;
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          for (String pendingKey : snapshot) {
            invalidateTokenKey(pendingKey);
          }
        }

        @Override
        public void afterCompletion(int status) {
          TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_INVALIDATIONS);
        }
      });
    }
    pending.add(key);
  }

  private void invalidateTokenKey(String key) {
    try {
      invalidateTokenKeyOrThrow(key);
    } catch (RuntimeException e) {
      log.warn("Failed invalidating repository cache token {}", key, e);
    }
  }

  private void invalidateTokenKeyOrThrow(String key) {
    try {
      watermark.bump(versionName(key));
    } finally {
      localTokens.invalidate(key);
    }
  }

  private static String tokenKey(long repositoryId, NexusCacheType type) {
    if (type == null) {
      throw new IllegalArgumentException("Cache type is required");
    }
    return repositoryId + ":" + type.name();
  }

  private static String versionName(String key) {
    return TOKEN_VERSION_PREFIX + key;
  }
}
