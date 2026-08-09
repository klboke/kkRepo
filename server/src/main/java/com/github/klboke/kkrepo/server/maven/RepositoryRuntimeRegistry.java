package com.github.klboke.kkrepo.server.maven;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.atomic.AtomicLong;
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
 * runtimes and repository records in typed node-local caches for a short TTL (default 30s).
 * The typed hot tier avoids serializing and deserializing the same immutable runtime on every
 * request; the existing {@link SharedCache} entry remains a recoverable second-level cache during
 * rolling upgrades. Writers ({@link
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
  private final Cache<String, Optional<RepositoryRuntime>> localRuntimes;
  private final Cache<String, Optional<RepositoryRecord>> localRecordsByName;
  private final Cache<Long, RepositoryRecord> localRecordsById;
  private final AtomicBoolean subscribed = new AtomicBoolean();
  private final AtomicLong configurationVersion = new AtomicLong();

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
    Duration localTtl = this.ttl.isZero() ? Duration.ofSeconds(1) : this.ttl;
    this.localRuntimes = Caffeine.newBuilder()
        .expireAfterWrite(localTtl)
        .maximumSize(100_000)
        .build();
    this.localRecordsByName = Caffeine.newBuilder()
        .expireAfterWrite(localTtl)
        .maximumSize(100_000)
        .build();
    this.localRecordsById = Caffeine.newBuilder()
        .expireAfterWrite(localTtl)
        .maximumSize(100_000)
        .build();
  }

  public RepositoryRuntimeRegistry(RepositoryDao repositoryDao, long ttlSeconds) {
    this.repositoryDao = repositoryDao;
    this.cache = null;
    this.objectMapper = new ObjectMapper();
    this.broadcaster = null;
    this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
    Duration localTtl = this.ttl.isZero() ? Duration.ofSeconds(1) : this.ttl;
    this.localRuntimes = Caffeine.newBuilder()
        .expireAfterWrite(localTtl)
        .maximumSize(100_000)
        .build();
    this.localRecordsByName = Caffeine.newBuilder()
        .expireAfterWrite(localTtl)
        .maximumSize(100_000)
        .build();
    this.localRecordsById = Caffeine.newBuilder()
        .expireAfterWrite(localTtl)
        .maximumSize(100_000)
        .build();
  }

  public Optional<RepositoryRuntime> resolve(String name) {
    if (ttl.isZero() || cache == null) {
      return resolveFresh(name);
    }
    Optional<RepositoryRuntime> local = localRuntimes.getIfPresent(name);
    if (local != null) {
      return local;
    }
    Optional<Optional<RepositoryRuntime>> cached = readCached(name);
    if (cached.isPresent()) {
      localRuntimes.put(name, cached.get());
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
    return repositoryDao.findByName(name).map(record -> {
      RepositoryRuntime runtime = toRuntime(record, new HashSet<>());
      if (!ttl.isZero() && !containsProxySecret(runtime)) {
        cacheRecord(record);
      }
      return runtime;
    });
  }

  /**
   * Resolve a record already loaded by the request security filter. This avoids a second
   * repository SELECT in the controller while preserving the same runtime snapshot and
   * invalidation rules used by name-based resolution.
   */
  public Optional<RepositoryRuntime> resolve(RepositoryRecord record) {
    if (record == null) return Optional.empty();
    if (!ttl.isZero()) {
      RepositoryRecord cachedRecord = localRecordsById.getIfPresent(record.id());
      Optional<RepositoryRuntime> cachedRuntime = localRuntimes.getIfPresent(record.name());
      if (record.equals(cachedRecord) && cachedRuntime != null) {
        return cachedRuntime;
      }
    }
    RepositoryRuntime runtime = toRuntime(record, new HashSet<>());
    if (!ttl.isZero() && !containsProxySecret(runtime)) {
      cacheRecord(record);
      writeCached(record.name(), Optional.of(runtime));
    }
    return Optional.of(runtime);
  }

  /** Name-keyed record lookup used by request filters before protocol dispatch. */
  public Optional<RepositoryRecord> findRecordByName(String name) {
    if (!ttl.isZero()) {
      Optional<RepositoryRecord> cached = localRecordsByName.getIfPresent(name);
      if (cached != null) return cached;
    }
    Optional<RepositoryRecord> loaded = repositoryDao.findByName(name);
    if (!ttl.isZero()) {
      if (loaded.isEmpty()) {
        localRecordsByName.put(name, Optional.empty());
      } else if (!containsProxySecret(loaded.get())) {
        cacheRecord(loaded.get());
      }
    }
    return loaded;
  }

  /** ID-keyed durable record lookup used by format-specific configuration caches. */
  public Optional<RepositoryRecord> findRecordById(long id) {
    if (!ttl.isZero()) {
      RepositoryRecord cached = localRecordsById.getIfPresent(id);
      if (cached != null) return Optional.of(cached);
    }
    Optional<RepositoryRecord> loaded = repositoryDao.findById(id);
    if (!ttl.isZero()) {
      loaded.filter(record -> !containsProxySecret(record)).ifPresent(this::cacheRecord);
    }
    return loaded;
  }

  /** Changes whenever this replica flushes repository configuration. */
  public long configurationVersion() {
    return configurationVersion.get();
  }

  /** ID-keyed lookup used by background workers (e.g. metadata rebuild) that hold a repo id. */
  @Transactional(readOnly = true)
  public Optional<RepositoryRuntime> resolveById(long id) {
    return findRecordById(id).flatMap(this::resolve);
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
    if (broadcaster == null || !subscribed.compareAndSet(false, true)) {
      return;
    }
    broadcaster.subscribe(REPOSITORY_CATALOG_NAME, this::invalidateAll);
  }

  /** Drop a single cached entry — called by {@code RepositoryService} on create/update/delete. */
  public void invalidate(String name) {
    configurationVersion.incrementAndGet();
    if (name != null) {
      localRuntimes.invalidate(name);
      Optional<RepositoryRecord> cached = localRecordsByName.getIfPresent(name);
      localRecordsByName.invalidate(name);
      if (cached != null) {
        cached.map(RepositoryRecord::id).ifPresent(localRecordsById::invalidate);
      }
      localRecordsById.asMap().entrySet().removeIf(entry -> name.equals(entry.getValue().name()));
    }
    if (cache != null && name != null) cache.evict(CACHE_NAMESPACE, name);
  }

  /**
   * Drop the whole cache. Invoked on a catalog broadcast (any repository mutation), for bulk
   * changes, and as a safety net in tests.
   */
  public void invalidateAll() {
    configurationVersion.incrementAndGet();
    localRuntimes.invalidateAll();
    localRecordsByName.invalidateAll();
    localRecordsById.invalidateAll();
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
    localRuntimes.put(name, runtime);
    if (cache == null) return;
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

  private void cacheRecord(RepositoryRecord record) {
    localRecordsByName.put(record.name(), Optional.of(record));
    localRecordsById.put(record.id(), record);
  }

  private static boolean containsProxySecret(RepositoryRecord record) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    Object proxyRaw = attrs.get("proxy");
    if (!(proxyRaw instanceof Map<?, ?> proxy)) return false;
    return nonBlank(proxy.get("remotePassword"))
        || nonBlank(proxy.get("remoteBearerToken"))
        || nonBlank(proxy.get("outboundProxyPassword"));
  }

  private static boolean nonBlank(Object value) {
    return value != null && !value.toString().isBlank();
  }

  private RepositoryRuntime toRuntime(RepositoryRecord record, Set<Long> resolving) {
    Map<String, Object> attrs = record.attributes() == null ? Map.of() : record.attributes();
    Object proxyRaw = attrs.get("proxy");
    Object rawRaw = attrs.get("raw");
    Object dockerRaw = attrs.get("docker");
    Object cargoRaw = attrs.get("cargo");

    Integer contentMaxAge = null;
    Integer metadataMaxAge = null;
    Integer minimumReleaseAge = null;
    Boolean autoBlock = null;
    String proxyRemoteUsername = null;
    String proxyRemotePassword = null;
    String proxyRemoteBearerToken = null;
    com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig outboundProxy = null;
    if (proxyRaw instanceof Map<?, ?> proxyMap) {
      contentMaxAge = asInt(proxyMap.get("contentMaxAgeMinutes"));
      metadataMaxAge = asInt(proxyMap.get("metadataMaxAgeMinutes"));
      minimumReleaseAge = asInt(proxyMap.get("minimumReleaseAgeMinutes"));
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
      outboundProxy = readOutboundProxy(proxyMap);
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
        members,
        outboundProxy,
        minimumReleaseAge);
  }

  private static com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig readOutboundProxy(Map<?, ?> proxyMap) {
    return com.github.klboke.kkrepo.server.proxy.OutboundProxyConfig.fromAttributes(proxyMap);
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
    if (runtime.outboundProxy() != null
        && runtime.outboundProxy().password() != null
        && !runtime.outboundProxy().password().isBlank()) {
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
