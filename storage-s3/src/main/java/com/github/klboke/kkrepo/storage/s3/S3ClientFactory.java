package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Component
public class S3ClientFactory {
  private final LocalCache<Long, CachedClient> cache = LocalCacheFactory.standard()
      .<Long, CachedClient>builder("s3-clients")
      .build();

  public S3Client client(S3BlobStoreConfig config) {
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

  private static S3Client build(S3BlobStoreConfig config) {
    return S3Client.builder()
        .endpointOverride(URI.create(config.endpoint()))
        .region(Region.of(config.region()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
        .httpClientBuilder(ApacheHttpClient.builder()
            .maxConnections(config.maxConnections())
            .connectionTimeout(Duration.ofMillis(config.connectionTimeoutMs()))
            .socketTimeout(Duration.ofMillis(config.socketTimeoutMs()))
            .connectionAcquisitionTimeout(Duration.ofMillis(config.connectionAcquisitionTimeoutMs()))
            .tcpKeepAlive(config.tcpKeepAlive()))
        .serviceConfiguration(S3Configuration.builder()
            // Disable aws-chunked streaming upload encoding: S3-compatible stores like Aliyun OSS
            // reject it ("aws-chunked encoding is not supported with the specified
            // x-amz-content-sha256 value", HTTP 400). With it off the SDK sends a plain body with a
            // precomputed content length, which AWS S3, MinIO, OSS, COS, OBS all accept.
            .chunkedEncodingEnabled(false)
            .pathStyleAccessEnabled(config.pathStyleAccess())
            .build())
        .build();
  }

  private static void closeQuietly(S3Client client) {
    try {
      client.close();
    } catch (RuntimeException ignored) {
    }
  }

  private record CachedClient(String signature, S3Client client) {
  }
}
