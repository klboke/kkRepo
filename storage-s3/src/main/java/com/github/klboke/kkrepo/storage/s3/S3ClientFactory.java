package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
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
 * The lifetime rules (clients keyed by store ID and signature, deferred close, leases for response
 * streams, handles that resolve the live client) live in {@link ClientCache}.
 */
@Component
public class S3ClientFactory {
  private static final Logger log = LoggerFactory.getLogger(S3ClientFactory.class);

  /** How long a client removed from the cache stays open for operations that already started. */
  static final Duration CLOSE_GRACE = Duration.ofMinutes(5);
  /**
   * A client nobody asked for in this long is retired; every storage operation counts as a use. Streams
   * that are still being read hold a lease, so this never cuts one off.
   */
  static final Duration IDLE_EXPIRY = Duration.ofHours(6);

  private final OutboundTlsTrust tlsTrust;
  private final ClientCache<S3Client> clients;

  public S3ClientFactory() {
    this(OutboundTlsTrust.defaults());
  }

  @Autowired
  public S3ClientFactory(OutboundTlsTrust tlsTrust) {
    this(tlsTrust, null, CLOSE_GRACE);
  }

  /** Test seam: a custom client builder and close grace (zero closes unleased clients synchronously). */
  S3ClientFactory(
      OutboundTlsTrust tlsTrust, Function<S3BlobStoreConfig, S3Client> clientBuilder, Duration closeGrace) {
    this.tlsTrust = tlsTrust;
    this.clients = new ClientCache<>(
        "s3-clients", "S3", log,
        clientBuilder == null ? this::build : clientBuilder,
        S3ClientFactory::closeQuietly, IDLE_EXPIRY, closeGrace);
  }

  /** The client for this exact configuration, built on first use. */
  public S3Client client(S3BlobStoreConfig config) {
    return clients.client(config);
  }

  /**
   * A reference that always resolves the live client for this configuration. Hold this, not the
   * client itself, in anything that outlives a single request.
   */
  public ClientHandle<S3Client> handle(S3BlobStoreConfig config) {
    return clients.handle(config);
  }

  /** Drops every client of this store (deleted or reconfigured); the next use builds a fresh one. */
  public void invalidate(long id) {
    clients.invalidate(id);
  }

  Set<Long> cachedStoreIds() {
    return clients.cachedStoreIds();
  }

  @PreDestroy
  void shutdown() {
    clients.shutdown();
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
}
