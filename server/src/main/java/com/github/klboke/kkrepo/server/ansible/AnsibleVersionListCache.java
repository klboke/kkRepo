package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.cache.SharedCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Recoverable cache for sorted Galaxy version inventories. Durable revisions remain authoritative. */
@Component
final class AnsibleVersionListCache {
  private static final Logger log = LoggerFactory.getLogger(AnsibleVersionListCache.class);
  private static final String NAMESPACE = "ansible-version-list";
  private static final Duration TTL = Duration.ofMinutes(10);

  private final SharedCache cache;
  private final boolean enabled;

  AnsibleVersionListCache(
      SharedCache cache,
      @Value("${kkrepo.cache.ansible-version-list.enabled:true}") boolean enabled) {
    this.cache = cache;
    this.enabled = enabled;
  }

  Optional<List<String>> find(String identity, String revisionState) {
    if (!enabled || cache == null) return Optional.empty();
    try {
      return cache.getJson(NAMESPACE, key(identity), Snapshot.class)
          .filter(snapshot -> Objects.equals(snapshot.revisionState(), revisionState))
          .filter(snapshot -> snapshot.cachedAt().plus(TTL).isAfter(Instant.now()))
          .map(Snapshot::versions);
    } catch (RuntimeException error) {
      log.warn("Failed reading Ansible version-list cache", error);
      return Optional.empty();
    }
  }

  void put(String identity, String revisionState, List<String> versions) {
    if (!enabled || cache == null) return;
    try {
      cache.putJson(
          NAMESPACE,
          key(identity),
          new Snapshot(List.copyOf(versions), revisionState, Instant.now()),
          TTL);
    } catch (RuntimeException error) {
      log.warn("Failed writing Ansible version-list cache", error);
    }
  }

  private static String key(String identity) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  record Snapshot(List<String> versions, String revisionState, Instant cachedAt) {
    Snapshot {
      versions = versions == null ? List.of() : List.copyOf(versions);
    }
  }
}
