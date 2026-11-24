package com.github.klboke.kkrepo.storage.s3;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.transport.HttpClientOptions;
import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClient;
import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClientBuilder;
import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds and caches native Aliyun OSS SDK v2 clients, one per blob-store config signature. Mirrors
 * {@link S3ClientFactory}: each replica owns its SDK clients (connection pools) — node-local state,
 * not coherent cross-replica truth, so this cache is safe under the stateless-service constraint.
 *
 * <p>The native OSS SDK uses OSS's own signing (no aws-chunked / payload-reset issues that the AWS
 * S3 SDK hits against OSS), so it needs none of the chunked-encoding / fromFile workarounds.
 */
@Component
public class OssClientFactory {
  private static final Logger log = LoggerFactory.getLogger(OssClientFactory.class);

  private final OutboundTlsTrust tlsTrust;
  private final Function<S3BlobStoreConfig, OSSClient> clientBuilder;
  private final Duration closeGrace;
  private final LocalCache<ClientKey, CachedClient> cache;

  public OssClientFactory() {
    this(OutboundTlsTrust.defaults());
  }

  @Autowired
  public OssClientFactory(OutboundTlsTrust tlsTrust) {
    this(tlsTrust, null, S3ClientFactory.CLOSE_GRACE);
  }

  /** Test seam: a custom client builder and close grace (zero closes synchronously). */
  OssClientFactory(
      OutboundTlsTrust tlsTrust, Function<S3BlobStoreConfig, OSSClient> clientBuilder, Duration closeGrace) {
    this.tlsTrust = tlsTrust;
    this.clientBuilder = clientBuilder;
    this.closeGrace = closeGrace;
    this.cache = LocalCacheFactory.standard()
        .<ClientKey, CachedClient>builder("oss-clients")
        .expireAfterAccess(S3ClientFactory.IDLE_EXPIRY)
        .removalListener((key, cached, cause) -> closeLater(cached == null ? null : cached.client()))
        .build();
  }

  /** The client for this exact configuration, built on first use. */
  public OSSClient client(S3BlobStoreConfig config) {
    return client(new ClientKey(config.id(), config.signature()), config);
  }

  /** A reference that always resolves the live client; see {@link S3ClientFactory#handle}. */
  public Supplier<OSSClient> handle(S3BlobStoreConfig config) {
    ClientKey key = new ClientKey(config.id(), config.signature());
    return () -> client(key, config);
  }

  private OSSClient client(ClientKey key, S3BlobStoreConfig config) {
    CachedClient cached = cache.getIfPresent(key);
    if (cached != null) {
      return cached.client();
    }
    return cache.get(key, ignored -> createCached(config)).client();
  }

  private CachedClient createCached(S3BlobStoreConfig config) {
    for (CachedClient other : cache.asMap().values()) {
      if (other.config().id() == config.id()) {
        // Two callers disagree about this store's configuration. Each now owns its own client, so
        // neither closes the other's, but the disagreement itself is worth knowing about.
        log.warn("OSS blob store {} (id={}) is now used with a second configuration; callers disagree about: {}",
            config.name(), config.id(), other.config().differingFields(config));
        break;
      }
    }
    return new CachedClient(config, create(config));
  }

  /** Drops every client of this store (deleted or reconfigured); the next use builds a fresh one. */
  public void invalidate(long id) {
    int removed = 0;
    for (ClientKey key : Set.copyOf(cache.asMap().keySet())) {
      if (key.storeId() == id) {
        cache.invalidate(key);
        removed++;
      }
    }
    if (removed > 0) {
      log.info("Invalidated {} OSS client(s) of blob store id={}; they close after {}", removed, id, closeGrace);
    }
  }

  Set<Long> cachedStoreIds() {
    Set<Long> ids = new HashSet<>();
    for (ClientKey key : cache.asMap().keySet()) {
      ids.add(key.storeId());
    }
    return Set.copyOf(ids);
  }

  @PreDestroy
  void shutdown() {
    Set<OSSClient> open = new HashSet<>();
    cache.asMap().values().forEach(cached -> open.add(cached.client()));
    cache.invalidateAll();
    open.forEach(OssClientFactory::closeQuietly);
  }

  private OSSClient create(S3BlobStoreConfig config) {
    return clientBuilder == null ? build(config) : clientBuilder.apply(config);
  }

  private void closeLater(OSSClient client) {
    if (client == null) {
      return;
    }
    if (closeGrace.isZero()) {
      closeQuietly(client);
      return;
    }
    CompletableFuture.runAsync(
        () -> closeQuietly(client),
        CompletableFuture.delayedExecutor(closeGrace.toMillis(), TimeUnit.MILLISECONDS));
  }

  private OSSClient build(S3BlobStoreConfig config) {
    return OSSClient.newBuilder()
        .region(config.region())
        .endpoint(config.endpoint())
        .credentialsProvider(new StaticCredentialsProvider(config.accessKey(), config.secretKey()))
        .usePathStyle(config.pathStyleAccess())
        .retryMaxAttempts(3)
        .httpClient(buildHttpClient(config, tlsTrust))
        .build();
  }

  static Apache5HttpClient buildHttpClient(S3BlobStoreConfig config) {
    return buildHttpClient(config, OutboundTlsTrust.defaults());
  }

  private static Apache5HttpClient buildHttpClient(S3BlobStoreConfig config, OutboundTlsTrust tlsTrust) {
    // Socket/connect timeout tuning lives in HTTP options; pool lease/max settings are builder-only
    // in Aliyun OSS SDK v2 0.4.1's Apache5 transport.
    HttpClientOptions httpOptions = HttpClientOptions.custom()
        .maxConnections(config.maxConnections())
        .connectTimeout(Duration.ofMillis(config.connectionTimeoutMs()))
        .readWriteTimeout(Duration.ofMillis(config.socketTimeoutMs()))
        .keepAliveTimeout(Duration.ofSeconds(30))
        .build();
    var builder = Apache5HttpClientBuilder.create()
        .options(httpOptions)
        .maxConnections(config.maxConnections())
        .connectionRequestTimeout(config.connectionAcquisitionTimeoutMs());
    if (tlsTrust.configured()) {
      builder.x509TrustManagers(tlsTrust.trustManagers());
    }
    return builder.build();
  }

  private static void closeQuietly(OSSClient client) {
    try {
      client.close();
    } catch (Exception ignored) {
      // best-effort close on cache eviction / shutdown
    }
  }

  private record ClientKey(long storeId, String signature) {
  }

  private record CachedClient(S3BlobStoreConfig config, OSSClient client) {
  }
}
