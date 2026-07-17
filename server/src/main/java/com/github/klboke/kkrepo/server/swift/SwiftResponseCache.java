package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.protocol.swift.SwiftMediaTypes;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Recoverable cache for small Swift JSON responses.
 *
 * <p>Database releases, tombstones, proxy inventory, and repository revisions remain authoritative.
 * Every hit is checked against the durable revision state, so cache loss or replica-local eviction
 * only causes the next request to rebuild the representation.
 */
@Component
final class SwiftResponseCache {
  private static final Logger log = LoggerFactory.getLogger(SwiftResponseCache.class);
  private static final String NAMESPACE = "swift-response";

  private final SharedCache cache;
  private final boolean enabled;

  SwiftResponseCache(
      SharedCache cache,
      @Value("${kkrepo.cache.swift-response.enabled:true}") boolean enabled) {
    this.cache = cache;
    this.enabled = enabled;
  }

  Optional<Snapshot> find(
      String identity, String revisionState, int maxAgeMinutes, Instant now) {
    if (!cacheable(maxAgeMinutes)) {
      return Optional.empty();
    }
    try {
      return cache.getJson(NAMESPACE, key(identity), Snapshot.class)
          .filter(snapshot -> Objects.equals(snapshot.revisionState(), revisionState))
          .filter(snapshot -> maxAgeMinutes < 0
              || snapshot.cachedAt().plusSeconds(maxAgeMinutes * 60L).isAfter(now));
    } catch (RuntimeException e) {
      log.warn("Failed reading Swift response cache", e);
      return Optional.empty();
    }
  }

  void put(
      String identity,
      String revisionState,
      int maxAgeMinutes,
      Instant now,
      byte[] body,
      String etag,
      Map<String, String> headers) {
    if (!cacheable(maxAgeMinutes)) {
      return;
    }
    try {
      Duration ttl = maxAgeMinutes < 0
          ? Duration.ofDays(1)
          : Duration.ofMinutes(maxAgeMinutes);
      cache.putJson(
          NAMESPACE,
          key(identity),
          new Snapshot(
              Arrays.copyOf(body, body.length),
              etag,
              headers == null ? Map.of() : new LinkedHashMap<>(headers),
              revisionState,
              now),
          ttl);
    } catch (RuntimeException e) {
      log.warn("Failed writing Swift response cache", e);
    }
  }

  private boolean cacheable(int maxAgeMinutes) {
    return enabled && cache != null && maxAgeMinutes != 0;
  }

  private static String key(String identity) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  record Snapshot(
      byte[] body,
      String etag,
      Map<String, String> headers,
      String revisionState,
      Instant cachedAt) {
    Snapshot {
      body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    MavenResponse response(boolean headOnly) {
      MavenResponse response = headOnly
          ? MavenResponse.noBody(200, body.length, SwiftMediaTypes.JSON, etag, null)
          : MavenResponse.ok(
              new ByteArrayInputStream(body), body.length, SwiftMediaTypes.JSON, etag, null);
      headers.forEach(response::withHeader);
      return response;
    }
  }
}
