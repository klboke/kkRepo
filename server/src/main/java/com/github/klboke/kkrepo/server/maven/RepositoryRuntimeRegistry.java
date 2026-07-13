package com.github.klboke.kkrepo.server.maven;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.cache.SharedCache;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves repositories into runtime snapshots usable by the Maven facets.
 *
 * <p>Resolution is cheap in itself but {@code mvn} clients fire hundreds of requests per build,
 * each of which would otherwise trigger one {@code SELECT repository} and (for groups) one
 * {@code SELECT repository_member} + N member resolutions. To absorb that fan-out without
 * sacrificing the "no in-memory state that diverges between replicas" rule, we cache resolved
 * runtimes for a short TTL (default 30s). Writers ({@link
 * com.github.klboke.kkrepo.server.repositories.RepositoryService}) clear the local entry on demand
 * so changes from this replica propagate immediately, and publish a {@code repository} catalog
 * broadcast so sibling replicas flush their cached runtimes within the broadcast poll interval. The
 * TTL remains a safety net for missed broadcasts.
 */
@Component
public class RepositoryRuntimeRegistry {
  private static final String CACHE_NAMESPACE = "repository-runtime";
  private static final String MISSING = "__missing__";
  // Must match RepositoryCatalogCache.CATALOG_NAME — a single repository mutation broadcast both
  // refreshes the catalog snapshot and flushes these cached runtimes on every replica.
  private static final String REPOSITORY_CATALOG_NAME = "repository";

  private final RepositoryDao repositoryDao;
  private final Duration ttl;
  private final SharedCache cache;
  private final ObjectMapper objectMapper;
  private final CatalogCacheBroadcaster broadcaster;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  @Autowired
  public RepositoryRuntimeRegistry(
      RepositoryDao repositoryDao,
      SharedCache cache,
      ObjectMapper objectMapper,
      ObjectProvider<CatalogCacheBroadcaster> broadcasterProvider,
      @Value("${kkrepo.maven.runtime-cache-ttl-seconds:30}") long ttlSeconds) {
    this(repositoryDao, cache, objectMapper,
        broadcasterProvider == null ? null : broadcasterProvider.getIfAvailable(), ttlSeconds);
  }

  RepositoryRuntimeRegistry(
      RepositoryDao repositoryDao,
      SharedCache cache,
      ObjectMapper objectMapper,
      CatalogCacheBroadcaster broadcaster,
      long ttlSeconds) {
    this.repositoryDao = repositoryDao;
    this.cache = cache;
    this.objectMapper = objectMapper;
    this.broadcaster = broadcaster;
    this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
  }

  public RepositoryRuntimeRegistry(RepositoryDao repositoryDao, long ttlSeconds) {
    this.repositoryDao = repositoryDao;
    this.cache = null;
    this.objectMapper = new ObjectMapper();
    this.broadcaster = null;
    this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
  }

  public Optional<RepositoryRuntime> resolve(String name) {
    if (ttl.isZero() || cache == null) {
      return resolveFresh(name);
    }
    Optional<Optional<RepositoryRuntime>> cached = readCached(name);
    if (cached.isPresent()) {
      return cached.get();
    }
    // Multiple concurrent misses on the same name are acceptable; the cache only holds a
    // recoverable runtime snapshot.
    Optional<RepositoryRuntime> resolved = resolveFresh(name);
    if (resolved.isEmpty() || !containsProxySecret(resolved.get())) {
      writeCached(name, resolved);
    }
    return resolved;
  }

  @Transactional(readOnly = true)
  protected Optional<RepositoryRuntime> resolveFresh(String name) {
    return repositoryDao.findByName(name).map(record -> toRuntime(record, new HashSet<>()));
  }

  /** ID-keyed lookup used by background workers (e.g. metadata rebuild) that hold a repo id. */
  @Transactional(readOnly = true)
  public Optional<RepositoryRuntime> resolveById(long id) {
    return repositoryDao.findById(id).map(record -> toRuntime(record, new HashSet<>()));
  }

  /**
   * Subscribe to the shared {@code repository} catalog broadcast. {@code RepositoryService}
   * publishes a refresh on every repository create/update/delete/member change, so sibling replicas
   * flush their cached runtimes within the broadcast poll interval instead of waiting on the TTL.
   * The mutating replica still clears immediately via {@link #invalidate(String)}; the TTL remains a
   * missed-broadcast safety net.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void subscribeToCatalogBroadcast() {
    if (broadcaster == null || cache == null || !subscribed.compareAndSet(false, true)) {
      return;
    }
    broadcaster.subscribe(REPOSITORY_CATALOG_NAME, this::invalidateAll);
  }

  /** Drop a single cached entry — called by {@code RepositoryService} on create/update/delete. */
  public void invalidate(String name) {
    if (cache != null && name != null) cache.evict(CACHE_NAMESPACE, name);
  }

  /**
   * Drop the whole cache. Invoked on a catalog broadcast (any repository mutation), for bulk
   * changes, and as a safety net in tests.
   */
  public void invalidateAll() {
    if (cache != null) cache.evictByPrefix(CACHE_NAMESPACE, "");
  }

  private Optional<Optional<RepositoryRuntime>> readCached(String name) {
    Optional<String> cached = cache.getString(CACHE_NAMESPACE, name);
    if (cached.isEmpty()) {
      return Optional.empty();
    }
    String payload = cached.get();
    if (MISSING.equals(payload)) {
      return Optional.of(Optional.empty());
    }
    try {
      return Optional.of(Optional.of(objectMapper.readValue(payload, RepositoryRuntime.class)));
    } catch (JsonProcessingException e) {
      cache.evict(CACHE_NAMESPACE, name);
      return Optional.empty();
    }
  }

  private void writeCached(String name, Optional<RepositoryRuntime> runtime) {
    if (runtime.isEmpty()) {
      cache.putString(CACHE_NAMESPACE, name, MISSING, ttl);
      return;
    }
    try {
      cache.putString(CACHE_NAMESPACE, name, objectMapper.writeValueAsString(runtime.get()), ttl);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed caching repository runtime " + name, e);
    }
  }

  private RepositoryRuntime toRuntime(RepositoryRecord record, Set<Long> resolving) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    Object proxyRaw = attrs.get("proxy");
    Object rawRaw = attrs.get("raw");
    Object dockerRaw = attrs.get("docker");
    Object cargoRaw = attrs.get("cargo");

    Integer contentMaxAge = null;
    Integer metadataMaxAge = null;
    Boolean autoBlock = null;
    String proxyRemoteUsername = null;
    String proxyRemotePassword = null;
    String proxyRemoteBearerToken = null;
    if (proxyRaw instanceof Map<?, ?> proxyMap) {
      contentMaxAge = asInt(proxyMap.get("contentMaxAgeMinutes"));
      metadataMaxAge = asInt(proxyMap.get("metadataMaxAgeMinutes"));
      autoBlock = asBool(proxyMap.get("autoBlock"));
      Object username = proxyMap.get("remoteUsername");
      if (username != null && !username.toString().isBlank()) {
        proxyRemoteUsername = username.toString();
      }
      Object password = proxyMap.get("remotePassword");
      if (password != null && !password.toString().isBlank()) {
        proxyRemotePassword = password.toString();
      }
      Object bearerToken = proxyMap.get("remoteBearerToken");
      if (bearerToken != null && !bearerToken.toString().isBlank()) {
        proxyRemoteBearerToken = bearerToken.toString();
      }
    }
    String rawContentDisposition = null;
    if (rawRaw instanceof Map<?, ?> rawMap) {
      Object value = rawMap.get("contentDisposition");
      if (value != null && !value.toString().isBlank()) {
        rawContentDisposition = value.toString();
      }
    }
    Boolean dockerConnectorEnabled = null;
    Integer dockerConnectorPort = null;
    String dockerConnectorPublicUrl = null;
    if (dockerRaw instanceof Map<?, ?> dockerMap) {
      dockerConnectorEnabled = asBool(dockerMap.get("connectorEnabled"));
      dockerConnectorPort = asInt(dockerMap.get("connectorPort"));
      Object publicUrl = dockerMap.get("connectorPublicUrl");
      if (publicUrl != null && !publicUrl.toString().isBlank()) {
        dockerConnectorPublicUrl = publicUrl.toString();
      }
    }
    Boolean cargoRequireAuthentication = null;
    if (usesCargoAuthenticationHint(record) && cargoRaw instanceof Map<?, ?> cargoMap) {
      cargoRequireAuthentication = asBool(cargoMap.get("requireAuthentication"));
    }

    List<RepositoryRuntime> members = List.of();
    if (record.type() == RepositoryType.GROUP) {
      if (!resolving.add(record.id())) {
        members = List.of();
      } else {
        try {
          List<RepositoryRecord> rows = repositoryDao.listMembers(record.id());
          List<RepositoryRuntime> resolved = new ArrayList<>(rows.size());
          for (RepositoryRecord row : rows) {
            resolved.add(toRuntime(row, resolving));
          }
          members = List.copyOf(resolved);
        } finally {
          resolving.remove(record.id());
        }
      }
    }

    return new RepositoryRuntime(
        record.id(),
        record.name(),
        record.format(),
        record.type(),
        record.recipeName(),
        record.online(),
        record.blobStoreId(),
        record.writePolicy(),
        record.versionPolicy(),
        record.layoutPolicy(),
        record.strictContentTypeValidation(),
        record.proxyRemoteUrl(),
        contentMaxAge,
        metadataMaxAge,
        autoBlock,
        proxyRemoteUsername,
        proxyRemotePassword,
        proxyRemoteBearerToken,
        rawContentDisposition,
        dockerConnectorEnabled,
        dockerConnectorPort,
        dockerConnectorPublicUrl,
        cargoRequireAuthentication,
        members);
  }

  private static Integer asInt(Object value) {
    if (value == null) return null;
    if (value instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Boolean asBool(Object value) {
    if (value == null) return null;
    if (value instanceof Boolean b) return b;
    return Boolean.parseBoolean(value.toString());
  }

  private static boolean containsProxySecret(RepositoryRuntime runtime) {
    if (runtime.proxyRemotePassword() != null && !runtime.proxyRemotePassword().isBlank()) {
      return true;
    }
    if (runtime.proxyRemoteBearerToken() != null && !runtime.proxyRemoteBearerToken().isBlank()) {
      return true;
    }
    for (RepositoryRuntime member : runtime.members()) {
      if (containsProxySecret(member)) {
        return true;
      }
    }
    return false;
  }

  private static boolean usesCargoAuthenticationHint(RepositoryRecord record) {
    return record.format() == RepositoryFormat.CARGO
        && (record.type() == RepositoryType.PROXY || record.type() == RepositoryType.GROUP);
  }
}
