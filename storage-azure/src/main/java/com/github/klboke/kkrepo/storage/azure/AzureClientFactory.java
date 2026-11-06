package com.github.klboke.kkrepo.storage.azure;

import com.azure.core.credential.AzureSasCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.util.HttpClientOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds and caches one {@link BlobServiceClient} per Azure blob store.
 *
 * <p>The cache is node-local and keyed by store ID plus configuration signature, so a changed
 * store rebuilds its client on each replica independently. Token credentials are never persisted:
 * every replica resolves and refreshes its own tokens from the platform, which keeps managed and
 * workload identity correct under multi-replica deployments.
 */
@Component
public class AzureClientFactory {
  private static final Logger log = LoggerFactory.getLogger(AzureClientFactory.class);

  private final OutboundTlsTrust tlsTrust;
  private final LocalCache<Long, CachedClient> cache = LocalCacheFactory.standard()
      .<Long, CachedClient>builder("azure-clients")
      .build();

  public AzureClientFactory() {
    this(OutboundTlsTrust.defaults());
  }

  @Autowired
  public AzureClientFactory(OutboundTlsTrust tlsTrust) {
    this.tlsTrust = tlsTrust == null ? OutboundTlsTrust.defaults() : tlsTrust;
  }

  public BlobServiceClient client(AzureBlobStoreConfig config) {
    String signature = config.signature();
    CachedClient cached = cache.getIfPresent(config.id());
    if (cached != null && cached.signature.equals(signature)) {
      return cached.client;
    }
    return cache.asMap().compute(config.id(), (id, current) -> {
      if (current != null && current.signature.equals(signature)) {
        return current;
      }
      return new CachedClient(signature, build(config));
    }).client;
  }

  public void invalidate(long id) {
    cache.asMap().remove(id);
  }

  Set<Long> cachedStoreIds() {
    return Set.copyOf(cache.asMap().keySet());
  }

  @PreDestroy
  void shutdown() {
    cache.invalidateAll();
  }

  private BlobServiceClient build(AzureBlobStoreConfig config) {
    config.validate();
    BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
        .endpoint(config.serviceEndpoint())
        .retryOptions(retryOptions(config));
    HttpClient httpClient = trustedHttpClient(config);
    if (httpClient != null) {
      // A supplied client owns its own timeouts; clientOptions only configures one the SDK builds.
      builder.httpClient(httpClient);
    } else {
      builder.clientOptions(new HttpClientOptions()
          .setConnectTimeout(Duration.ofMillis(config.connectionTimeoutMs()))
          .setReadTimeout(Duration.ofMillis(config.readTimeoutMs())));
    }
    applyCredential(builder, config, httpClient);
    return builder.buildClient();
  }

  /**
   * Exponential retry policy honouring {@code maxRetries}. Azure counts the first attempt as a try,
   * so {@code maxRetries = 0} means a single attempt. Delays and the per-try timeout keep the SDK
   * defaults.
   */
  static RequestRetryOptions retryOptions(AzureBlobStoreConfig config) {
    return new RequestRetryOptions(
        RetryPolicyType.EXPONENTIAL, config.maxRetries() + 1, (Integer) null, (Long) null, (Long) null, null);
  }

  /**
   * HTTP client trusting the operator's PEM bundle, or {@code null} when no bundle is configured
   * and the SDK default (JVM truststore) applies. Needed when a TLS-intercepting proxy sits
   * between kkRepo and Azure.
   */
  HttpClient trustedHttpClient(AzureBlobStoreConfig config) {
    if (!tlsTrust.configured()) {
      return null;
    }
    X509TrustManager[] trustManagers = tlsTrust.trustManagers();
    if (trustManagers == null || trustManagers.length == 0) {
      return null;
    }
    SslContext sslContext;
    try {
      sslContext = SslContextBuilder.forClient().trustManager(trustManagers[0]).build();
    } catch (SSLException e) {
      throw new IllegalStateException(
          "Cannot apply kkrepo.tls.ca-certificates to Azure blob store '" + config.name() + "'", e);
    }
    log.info("Azure blob store '{}' validates TLS with the kkrepo.tls.ca-certificates bundle", config.name());
    return new NettyAsyncHttpClientBuilder(
        reactor.netty.http.client.HttpClient.create().secure(spec -> spec.sslContext(sslContext)))
        .connectTimeout(Duration.ofMillis(config.connectionTimeoutMs()))
        .readTimeout(Duration.ofMillis(config.readTimeoutMs()))
        .build();
  }
  private void applyCredential(
      BlobServiceClientBuilder builder, AzureBlobStoreConfig config, HttpClient httpClient) {
    switch (config.credentialType()) {
      case ACCOUNT_KEY -> builder.credential(
          new StorageSharedKeyCredential(config.resolvedAccountName(), config.accountKey()));
      case SAS -> builder.credential(new AzureSasCredential(config.sasToken()));
      case MANAGED_IDENTITY, WORKLOAD_IDENTITY, DEFAULT ->
          builder.credential(tokenCredential(config, httpClient));
    }
  }

  /** Visible for tests: resolves the platform credential without constructing an HTTP client. */
  TokenCredential tokenCredential(AzureBlobStoreConfig config) {
    return tokenCredential(config, null);
  }

  TokenCredential tokenCredential(AzureBlobStoreConfig config, HttpClient httpClient) {
    return switch (config.credentialType()) {
      case MANAGED_IDENTITY -> managedIdentity(config, httpClient);
      case WORKLOAD_IDENTITY -> workloadIdentity(config, httpClient);
      // DefaultAzureCredential already probes workload identity, then managed identity, then the
      // environment, so a single setting covers AKS, VMs and developer machines.
      default -> defaultCredential(config, httpClient);
    };
  }

  private TokenCredential managedIdentity(AzureBlobStoreConfig config, HttpClient httpClient) {
    ManagedIdentityCredentialBuilder builder = new ManagedIdentityCredentialBuilder();
    // No client ID selects the system-assigned identity of the host.
    if (!config.clientId().isEmpty()) {
      builder.clientId(config.clientId());
    }
    // Token acquisition goes through the same trust configuration as blob traffic.
    if (httpClient != null) {
      builder.httpClient(httpClient);
    }
    return builder.build();
  }

  private TokenCredential workloadIdentity(AzureBlobStoreConfig config, HttpClient httpClient) {
    WorkloadIdentityCredentialBuilder builder = new WorkloadIdentityCredentialBuilder();
    if (httpClient != null) {
      builder.httpClient(httpClient);
    }
    // Each unset value falls back to the AZURE_* variables projected by the workload identity
    // webhook, so the common AKS deployment needs no explicit configuration at all.
    if (!config.clientId().isEmpty()) {
      builder.clientId(config.clientId());
    }
    if (!config.tenantId().isEmpty()) {
      builder.tenantId(config.tenantId());
    }
    if (!config.tokenFilePath().isEmpty()) {
      builder.tokenFilePath(config.tokenFilePath());
    }
    if (!config.authorityHost().isEmpty()) {
      builder.authorityHost(config.authorityHost());
    }
    try {
      return builder.build();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new IllegalArgumentException(
          "Workload identity is not fully configured for Azure blob store '" + config.name()
              + "'. Set clientId, tenantId and tokenFilePath on the blob store, or run with the "
              + "workload identity webhook so AZURE_CLIENT_ID, AZURE_TENANT_ID and "
              + "AZURE_FEDERATED_TOKEN_FILE are projected into the pod: " + e.getMessage(), e);
    }
  }

  private TokenCredential defaultCredential(AzureBlobStoreConfig config, HttpClient httpClient) {
    DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
    if (httpClient != null) {
      builder.httpClient(httpClient);
    }
    // Selects which user-assigned identity the chain should use for both of its identity steps.
    if (!config.clientId().isEmpty()) {
      builder.managedIdentityClientId(config.clientId());
      builder.workloadIdentityClientId(config.clientId());
    }
    if (!config.tenantId().isEmpty()) {
      builder.tenantId(config.tenantId());
    }
    if (!config.authorityHost().isEmpty()) {
      builder.authorityHost(config.authorityHost());
    }
    return builder.build();
  }

  private record CachedClient(String signature, BlobServiceClient client) {
  }
}
