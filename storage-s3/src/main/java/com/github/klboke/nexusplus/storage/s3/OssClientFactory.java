package com.github.klboke.nexusplus.storage.s3;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.transport.HttpClientOptions;
import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClient;
import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClientBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
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
  private final Cache<Long, CachedClient> cache = Caffeine.newBuilder().build();

  public OSSClient client(S3BlobStoreConfig config) {
    String signature = config.signature();
    CachedClient cached = cache.getIfPresent(config.id());
    if (cached != null && cached.signature.equals(signature)) {
      return cached.client;
    }
    return cache.asMap().compute(config.id(), (id, current) -> {
      if (current != null && current.signature.equals(signature)) {
        return current;
      }
      if (current != null) {
        closeQuietly(current.client);
      }
      return new CachedClient(signature, build(config));
    }).client;
  }

  public void invalidate(long id) {
    CachedClient removed = cache.asMap().remove(id);
    if (removed != null) {
      closeQuietly(removed.client);
    }
  }

  @PreDestroy
  void shutdown() {
    cache.asMap().values().forEach(c -> closeQuietly(c.client));
    cache.invalidateAll();
  }

  private static OSSClient build(S3BlobStoreConfig config) {
    return OSSClient.newBuilder()
        .region(config.region())
        .endpoint(config.endpoint())
        .credentialsProvider(new StaticCredentialsProvider(config.accessKey(), config.secretKey()))
        .usePathStyle(config.pathStyleAccess())
        .retryMaxAttempts(3)
        .httpClient(buildHttpClient(config))
        .build();
  }

  static Apache5HttpClient buildHttpClient(S3BlobStoreConfig config) {
    // Socket/connect timeout tuning lives in HTTP options; pool lease/max settings are builder-only
    // in Aliyun OSS SDK v2 0.4.0's Apache5 transport.
    HttpClientOptions httpOptions = HttpClientOptions.custom()
        .maxConnections(config.maxConnections())
        .connectTimeout(Duration.ofMillis(config.connectionTimeoutMs()))
        .readWriteTimeout(Duration.ofMillis(config.socketTimeoutMs()))
        .keepAliveTimeout(Duration.ofSeconds(30))
        .build();
    return Apache5HttpClientBuilder.create()
        .options(httpOptions)
        .maxConnections(config.maxConnections())
        .connectionRequestTimeout(config.connectionAcquisitionTimeoutMs())
        .build();
  }

  private static void closeQuietly(OSSClient client) {
    try {
      client.close();
    } catch (Exception ignored) {
      // best-effort close on cache eviction / shutdown
    }
  }

  private record CachedClient(String signature, OSSClient client) {
  }
}
