package com.github.klboke.kkrepo.server.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.core.security.EncryptionSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Caches resolved {@link AuthenticatedSubject} entries for HTTP Basic credentials, keyed by an
 * HMAC of the username and password (NUL-separated).
 *
 * <p>Every Basic-authenticated request otherwise recomputes a 120k-round PBKDF2 hash in
 * {@link SecurityHashing#verifyPassword}, plus a SELECT on security_user and the role lookup.
 * Under CI/CD traffic (the same credential on every request) this PBKDF2 cost dominates the auth
 * path and caps throughput. A short-TTL cache collapses the steady state into a local cache lookup.
 *
 * <p><b>Cache key.</b> Unlike {@link ApiKeyAuthCache} (which hashes a high-entropy random token),
 * passwords may be low-entropy, so a bare SHA-256 of the credential would be brute-forceable if the
 * cache store leaked. The key is therefore HMAC-SHA256(credentialSecret, username || 0x00 ||
 * password) — without the server-side secret the stored key cannot be reversed, and the NUL byte
 * separator prevents username/password boundary collisions. The plaintext password is never stored.
 *
 * <p><b>Multi-replica semantics.</b> Backed by node-local {@link SharedCache}. Each replica may
 * recompute the PBKDF2 result independently, and TTL is the safety net: with a 60s positive TTL, a
 * password change or role update propagates within a minute.
 * A negative TTL caches authentication <i>failures</i> briefly to blunt repeated-PBKDF2 cost from a
 * single wrong credential; because the key includes the exact password, a user retrying with the
 * correct password maps to a different key and is unaffected. On any cache error the loader (real
 * PBKDF2 path) runs, so correctness never depends on the cache being up. See [[encryption-secrets]]
 * for credentialSecret.
 */
@Service
public class BasicAuthCache {
  private static final Logger log = LoggerFactory.getLogger(BasicAuthCache.class);
  private static final String NAMESPACE = "basic-auth";
  private static final String MISSING_MARKER = "__missing__";

  private final SharedCache sharedCache;
  private final ObjectMapper objectMapper;
  private final Cache<String, AuthenticatedSubject> localSubjects;
  private final Cache<String, Boolean> localMissing;
  private final boolean enabled;
  private final Duration positiveTtl;
  private final Duration negativeTtl;

  @Autowired
  public BasicAuthCache(
      SharedCache sharedCache,
      ObjectMapper objectMapper,
      @Value("${kkrepo.cache.basic-auth.enabled:true}") boolean enabled,
      @Value("${kkrepo.cache.basic-auth.ttl-seconds:60}") long ttlSeconds,
      @Value("${kkrepo.cache.basic-auth.missing-ttl-seconds:5}") long missingTtlSeconds) {
    this.sharedCache = sharedCache;
    this.objectMapper = objectMapper;
    this.enabled = enabled;
    this.positiveTtl = ttlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(ttlSeconds);
    this.negativeTtl = missingTtlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(missingTtlSeconds);
    this.localSubjects = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
        .maximumSize(100_000)
        .build();
    this.localMissing = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, missingTtlSeconds)))
        .maximumSize(100_000)
        .build();
  }

  /** Backward-compatible constructor used by focused tests. */
  public BasicAuthCache(
      SharedCache sharedCache,
      boolean enabled,
      long ttlSeconds,
      long missingTtlSeconds) {
    this(sharedCache, new ObjectMapper(), enabled, ttlSeconds, missingTtlSeconds);
  }

  /**
   * Read-through. On miss, invokes the loader (the real PBKDF2/LDAP path) and caches its result
   * (positive or negative). Returns {@link Optional#empty()} for invalid credentials.
   */
  public Optional<AuthenticatedSubject> find(
      String username,
      String password,
      Supplier<Optional<AuthenticatedSubject>> loader) {
    if (!enabled || sharedCache == null || username == null || username.isEmpty() || password == null) {
      return loader.get();
    }
    String key;
    try {
      key = cacheKey(username, password);
    } catch (RuntimeException e) {
      log.warn("Failed deriving basic-auth cache key, falling back to PBKDF2", e);
      return loader.get();
    }
    if (!positiveTtl.isZero()) {
      AuthenticatedSubject local = localSubjects.getIfPresent(key);
      if (local != null) return Optional.of(local);
    }
    if (!negativeTtl.isZero() && localMissing.getIfPresent(key) != null) {
      return Optional.empty();
    }
    try {
      Optional<String> raw = sharedCache.getString(NAMESPACE, key);
      if (raw.isPresent()) {
        if (MISSING_MARKER.equals(raw.get())) {
          if (!negativeTtl.isZero()) localMissing.put(key, Boolean.TRUE);
          return Optional.empty();
        }
        if (!positiveTtl.isZero()) {
          try {
            AuthenticatedSubject cached =
                objectMapper.readValue(raw.get(), AuthenticatedSubject.class);
            localSubjects.put(key, cached);
            return Optional.of(cached);
          } catch (JsonProcessingException malformed) {
            sharedCache.evict(NAMESPACE, key);
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn("Failed reading basic-auth cache, falling back to PBKDF2", e);
      return loader.get();
    }

    Optional<AuthenticatedSubject> loaded = loader.get();
    if (loaded.isEmpty()) {
      localSubjects.invalidate(key);
      if (!negativeTtl.isZero()) localMissing.put(key, Boolean.TRUE);
    } else if (!positiveTtl.isZero()) {
      localMissing.invalidate(key);
      localSubjects.put(key, loaded.get());
    }
    try {
      if (loaded.isEmpty()) {
        if (!negativeTtl.isZero()) {
          sharedCache.putString(NAMESPACE, key, MISSING_MARKER, negativeTtl);
        }
      } else if (!positiveTtl.isZero()) {
        sharedCache.putJson(NAMESPACE, key, loaded.get(), positiveTtl);
      }
    } catch (RuntimeException e) {
      log.warn("Failed writing basic-auth cache", e);
    }
    return loaded;
  }

  /** Evict every cached entry. Use when admins rotate roles or disable users in bulk. */
  public void evictAll() {
    localSubjects.invalidateAll();
    localMissing.invalidateAll();
    if (!enabled || sharedCache == null) {
      return;
    }
    try {
      sharedCache.evictByPrefix(NAMESPACE, "");
    } catch (RuntimeException e) {
      log.warn("Failed bulk-evicting basic-auth cache", e);
    }
  }

  private String cacheKey(String username, String password) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(
          EncryptionSecrets.credentialSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(username.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) 0);
      mac.update(password.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
