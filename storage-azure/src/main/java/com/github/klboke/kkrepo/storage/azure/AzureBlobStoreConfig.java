package com.github.klboke.kkrepo.storage.azure;

import java.util.Locale;
import java.util.Objects;

/**
 * Effective configuration of a single Azure Blob Storage blob store.
 *
 * <p>Terminology maps onto the generic blob model as follows: the Azure <em>container</em> is the
 * {@code bucket}, and {@code prefix} is a virtual directory inside it. {@code endpoint} is the blob
 * service URL; when blank it is derived from {@code accountName} and {@code endpointSuffix} so
 * sovereign clouds and Azurite can both be addressed.
 */
public record AzureBlobStoreConfig(
    long id,
    String name,
    String endpoint,
    String accountName,
    String container,
    String prefix,
    AzureCredentialType credentialType,
    String accountKey,
    String sasToken,
    String clientId,
    String tenantId,
    String tokenFilePath,
    String authorityHost,
    int maxRetries,
    int connectionTimeoutMs,
    int readTimeoutMs,
    long uploadThresholdBytes,
    long blockSizeBytes,
    int uploadConcurrency) {

  public static final String DEFAULT_ENDPOINT_SUFFIX = "core.windows.net";
  /** Azure rejects blobs made of more than 50,000 blocks. */
  public static final int MAX_BLOCKS = 50_000;
  public static final long MIN_BLOCK_BYTES = 256L * 1024;
  public static final long MAX_BLOCK_BYTES = 4000L * 1024 * 1024;

  public AzureBlobStoreConfig {
    name = name == null ? "" : name.trim();
    endpoint = endpoint == null ? "" : endpoint.trim();
    accountName = accountName == null ? "" : accountName.trim();
    container = container == null ? "" : container.trim();
    prefix = trimSlashes(prefix);
    credentialType = credentialType == null ? AzureCredentialType.DEFAULT : credentialType;
    accountKey = normalizeSecret(accountKey);
    sasToken = normalizeSecret(sasToken);
    clientId = normalizeSecret(clientId);
    tenantId = normalizeSecret(tenantId);
    tokenFilePath = normalizeSecret(tokenFilePath);
    authorityHost = normalizeSecret(authorityHost);
    maxRetries = Math.max(0, maxRetries);
    connectionTimeoutMs = Math.max(1, connectionTimeoutMs);
    readTimeoutMs = Math.max(1, readTimeoutMs);
    uploadThresholdBytes = Math.max(0, uploadThresholdBytes);
    blockSizeBytes = Math.clamp(blockSizeBytes, MIN_BLOCK_BYTES, MAX_BLOCK_BYTES);
    uploadConcurrency = Math.max(1, uploadConcurrency);
  }

  /** Blob service URL, derived from the account name when not configured explicitly. */
  public String serviceEndpoint() {
    if (!endpoint.isEmpty()) {
      return stripTrailingSlash(endpoint);
    }
    if (accountName.isEmpty()) {
      throw new IllegalArgumentException(
          "Azure blob store '" + name + "' requires either an endpoint or an accountName");
    }
    return "https://" + accountName + ".blob." + DEFAULT_ENDPOINT_SUFFIX;
  }

  /**
   * Account name for shared-key signing, inferred from a standard {@code <account>.blob.*} host
   * when it was not configured explicitly.
   */
  public String resolvedAccountName() {
    if (!accountName.isEmpty()) {
      return accountName;
    }
    return accountNameFromEndpoint(endpoint);
  }

  /** Full object key, applying the store prefix. */
  public String objectKey(String key) {
    String cleanKey = key == null ? "" : key;
    return prefix.isEmpty() ? cleanKey : prefix + "/" + cleanKey;
  }

  public boolean usesPlatformIdentity() {
    return credentialType.isTokenBased();
  }

  /**
   * Fingerprint used to detect configuration changes that must rebuild the cached client. Secrets
   * are reduced to a hash so the signature never carries credential material into logs or caches.
   */
  public String signature() {
    return String.join("|",
        "azure",
        serviceEndpointQuietly(),
        resolvedAccountNameQuietly(),
        container,
        prefix,
        credentialType.id(),
        secretFingerprint(accountKey),
        secretFingerprint(sasToken),
        clientId,
        tenantId,
        tokenFilePath,
        authorityHost,
        Integer.toString(maxRetries),
        Integer.toString(connectionTimeoutMs),
        Integer.toString(readTimeoutMs),
        Long.toString(uploadThresholdBytes),
        Long.toString(blockSizeBytes),
        Integer.toString(uploadConcurrency));
  }

  /**
   * Fails fast on combinations Azure cannot authenticate, so an unusable store is rejected by the
   * admin API instead of failing on the first artifact upload.
   */
  public void validate() {
    if (container.isEmpty()) {
      throw new IllegalArgumentException("Azure blob stores require a container name");
    }
    if (endpoint.isEmpty() && accountName.isEmpty()) {
      throw new IllegalArgumentException("Azure blob stores require an endpoint or an accountName");
    }
    switch (credentialType) {
      case ACCOUNT_KEY -> {
        if (accountKey.isEmpty()) {
          throw new IllegalArgumentException("accountKey is required when credentialType is account-key");
        }
        if (resolvedAccountName().isEmpty()) {
          throw new IllegalArgumentException(
              "accountName is required when credentialType is account-key and the endpoint is non-standard");
        }
      }
      case SAS -> {
        if (sasToken.isEmpty()) {
          throw new IllegalArgumentException("sasToken is required when credentialType is sas");
        }
      }
      // Token-based modes read their inputs from the platform, so nothing else is mandatory here.
      case DEFAULT, MANAGED_IDENTITY, WORKLOAD_IDENTITY -> {
        if (!accountKey.isEmpty() || !sasToken.isEmpty()) {
          throw new IllegalArgumentException(
              "accountKey and sasToken must be empty when credentialType is " + credentialType.id());
        }
      }
    }
  }

  static String accountNameFromEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      return "";
    }
    try {
      String host = java.net.URI.create(endpoint.trim()).getHost();
      if (host == null) {
        return "";
      }
      int dot = host.indexOf('.');
      if (dot <= 0) {
        return "";
      }
      // Only <account>.blob.<suffix> encodes the account in the host; Azurite puts it in the path.
      return host.regionMatches(true, dot, ".blob.", 0, 6) ? host.substring(0, dot) : "";
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private String serviceEndpointQuietly() {
    return endpoint.isEmpty() && accountName.isEmpty() ? "" : serviceEndpoint();
  }

  private String resolvedAccountNameQuietly() {
    return resolvedAccountName();
  }

  private static String secretFingerprint(String secret) {
    return secret.isEmpty() ? "" : Integer.toHexString(Objects.hash(secret));
  }

  private static String normalizeSecret(String value) {
    return value == null || value.isBlank() ? "" : value.trim();
  }

  private static String stripTrailingSlash(String value) {
    String trimmed = value.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String trimSlashes(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.trim().replaceAll("^/+", "").replaceAll("/+$", "");
  }

  public static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
