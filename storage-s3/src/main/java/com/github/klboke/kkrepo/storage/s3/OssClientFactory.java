package com.github.klboke.kkrepo.storage.s3;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.transport.HttpClientOptions;
import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClient;
import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClientBuilder;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;
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
  private final ClientCache<OSSClient> clients;

  public OssClientFactory() {
    this(OutboundTlsTrust.defaults());
  }

  @Autowired
  public OssClientFactory(OutboundTlsTrust tlsTrust) {
    this(tlsTrust, null, S3ClientFactory.CLOSE_GRACE);
  }

  /** Test seam: a custom client builder and close grace (zero closes unleased clients synchronously). */
  OssClientFactory(
      OutboundTlsTrust tlsTrust, Function<S3BlobStoreConfig, OSSClient> clientBuilder, Duration closeGrace) {
    this.tlsTrust = tlsTrust;
    this.clients = new ClientCache<>(
        "oss-clients", "OSS", log,
        clientBuilder == null ? this::build : clientBuilder,
        OssClientFactory::closeQuietly, S3ClientFactory.IDLE_EXPIRY, closeGrace);
  }

  /** The client for this exact configuration, built on first use. */
  public OSSClient client(S3BlobStoreConfig config) {
    return clients.client(config);
  }

  /** A reference that always resolves the live client; see {@link S3ClientFactory#handle}. */
  public ClientHandle<OSSClient> handle(S3BlobStoreConfig config) {
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
}
