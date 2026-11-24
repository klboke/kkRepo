package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import jakarta.annotation.PreDestroy;
import java.net.URI;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Builds and caches the AWS SDK S3 clients (one connection pool each), node-local and rebuildable.
 *
 * <p>Lifetime rules, because a closed client can never be reopened:
 * <ul>
 *   <li>Clients are keyed by store ID <em>and</em> configuration signature. Two callers that disagree
 *       about a store's signature (for example an admin probe and the runtime path) each get their own
 *       client; neither closes the other's.
 *   <li>A client is never closed while it can still be handed out. It leaves the cache only when it
 *       goes idle, or when its store is invalidated, and is then closed after {@link #CLOSE_GRACE} so
 *       requests that already hold it can finish.
 *   <li>Callers that live longer than a request (blob storage wrappers) hold a {@link #handle handle},
 *       which resolves the current client on every use, so an invalidated client is rebuilt by the next
 *       call instead of being used after it was closed.
 * </ul>
 */
@Component
public class S3ClientFactory {
  private static final Logger log = LoggerFactory.getLogger(S3ClientFactory.class);

  /** How long a client removed from the cache stays open for operations that already started. */
  static final Duration CLOSE_GRACE = Duration.ofMinutes(5);
  /** A client nobody asked for in this long is closed; every storage operation counts as a use. */
  static final Duration IDLE_EXPIRY = Duration.ofHours(1);

  private final OutboundTlsTrust tlsTrust;
  private final Function<S3BlobStoreConfig, S3Client> clientBuilder;
  private final Duration closeGrace;
  private final LocalCache<ClientKey, CachedClient> cache;

  public S3ClientFactory() {
    this(OutboundTlsTrust.defaults());
  }

  @Autowired
  public S3ClientFactory(OutboundTlsTrust tlsTrust) {
    this(tlsTrust, null, CLOSE_GRACE);
  }

  /** Test seam: a custom client builder and close grace (zero closes synchronously). */
  S3ClientFactory(
      OutboundTlsTrust tlsTrust, Function<S3BlobStoreConfig, S3Client> clientBuilder, Duration closeGrace) {
    this.tlsTrust = tlsTrust;
    this.clientBuilder = clientBuilder;
    this.closeGrace = closeGrace;
    this.cache = LocalCacheFactory.standard()
        .<ClientKey, CachedClient>builder("s3-clients")
        .expireAfterAccess(IDLE_EXPIRY)
        .removalListener((key, cached, cause) -> closeLater(cached == null ? null : cached.client()))
        .build();
  }

  /** The client for this exact configuration, built on first use. */
  public S3Client client(S3BlobStoreConfig config) {
    return client(new ClientKey(config.id(), config.signature()), config);
  }

  /**
   * A reference that always resolves the live client for this configuration. Hold this, not the
   * client itself, in anything that outlives a single request.
   */
  public Supplier<S3Client> handle(S3BlobStoreConfig config) {
    ClientKey key = new ClientKey(config.id(), config.signature());
    return () -> client(key, config);
  }

  private S3Client client(ClientKey key, S3BlobStoreConfig config) {
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
        log.warn("S3 blob store {} (id={}) is now used with a second configuration; callers disagree about: {}",
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
      log.info("Invalidated {} S3 client(s) of blob store id={}; they close after {}", removed, id, closeGrace);
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
    Set<S3Client> open = new HashSet<>();
    cache.asMap().values().forEach(cached -> open.add(cached.client()));
    cache.invalidateAll();
    open.forEach(S3ClientFactory::closeQuietly);
  }

  private S3Client create(S3BlobStoreConfig config) {
    return clientBuilder == null ? build(config) : clientBuilder.apply(config);
  }

  private void closeLater(S3Client client) {
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

  private S3Client build(S3BlobStoreConfig config) {
    var http = ApacheHttpClient.builder()
        .maxConnections(config.maxConnections())
        .connectionTimeout(Duration.ofMillis(config.connectionTimeoutMs()))
        .socketTimeout(Duration.ofMillis(config.socketTimeoutMs()))
        .connectionAcquisitionTimeout(Duration.ofMillis(config.connectionAcquisitionTimeoutMs()))
        .tcpKeepAlive(config.tcpKeepAlive());
    if (tlsTrust.configured()) {
      http.tlsTrustManagersProvider(tlsTrust::trustManagers);
    }
    var builder = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint()))
        .region(Region.of(config.region()))
        .httpClientBuilder(http)
        .serviceConfiguration(S3Configuration.builder()
            // Disable aws-chunked streaming upload encoding: S3-compatible stores like Aliyun OSS
            // reject it ("aws-chunked encoding is not supported with the specified
            // x-amz-content-sha256 value", HTTP 400). With it off the SDK sends a plain body with a
            // precomputed content length, which AWS S3, RustFS, OSS, COS, OBS all accept.
            .chunkedEncodingEnabled(false)
            .pathStyleAccessEnabled(config.pathStyleAccess())
            .build());
    if (!config.usesDefaultCredentials()) {
      builder.credentialsProvider(StaticCredentialsProvider.create(
          AwsBasicCredentials.create(config.accessKey(), config.secretKey())));
    }
    // With no explicit provider, the SDK owns an independent default credentials chain per client.
    // It refreshes temporary credentials locally on each replica; neither resolved credentials nor
    // tokens are persisted. Closing/replacing one store's client cannot close another store's chain.
    return builder.build();
  }

  private static void closeQuietly(S3Client client) {
    try {
      client.close();
    } catch (RuntimeException ignored) {
    }
  }

  private record ClientKey(long storeId, String signature) {
  }

  private record CachedClient(S3BlobStoreConfig config, S3Client client) {
  }
}
