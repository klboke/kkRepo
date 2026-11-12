package com.github.klboke.kkrepo.storage.azure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Node-wide defaults for Azure blob stores. Individual {@code blob_store} rows override these; a
 * blank value in a row inherits the default here. The key prefix is deliberately not inherited: a
 * blank prefix is a valid per-store choice, and inheriting one would silently move where an
 * existing store writes new blobs whenever the node default changed.
 *
 * <p>{@code credentialType} defaults to {@code default}, which resolves workload identity and
 * managed identity from the environment. That keeps secretless deployments working with no
 * configuration beyond the account and container.
 */
@Component
@ConfigurationProperties(prefix = "kkrepo.storage.azure")
public class AzureStorageProperties {
  private String endpoint = "";
  private String accountName = "";
  private String container = "kkrepo";
  private String credentialType = "default";
  private String accountKey = "";
  private String sasToken = "";
  private String clientId = "";
  private String tenantId = "";
  private String tokenFilePath = "";
  private String authorityHost = "";
  private int maxRetries = 3;
  private int connectionTimeoutMs = 5000;
  private int readTimeoutMs = 120000;
  private long uploadThresholdBytes = 64L * 1024 * 1024;
  private long blockSizeBytes = 16L * 1024 * 1024;
  private int uploadConcurrency = 4;

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public String getContainer() {
    return container;
  }

  public void setContainer(String container) {
    this.container = container;
  }

  public String getCredentialType() {
    return credentialType;
  }

  public void setCredentialType(String credentialType) {
    this.credentialType = credentialType;
  }

  public String getAccountKey() {
    return accountKey;
  }

  public void setAccountKey(String accountKey) {
    this.accountKey = accountKey;
  }

  public String getSasToken() {
    return sasToken;
  }

  public void setSasToken(String sasToken) {
    this.sasToken = sasToken;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getTokenFilePath() {
    return tokenFilePath;
  }

  public void setTokenFilePath(String tokenFilePath) {
    this.tokenFilePath = tokenFilePath;
  }

  public String getAuthorityHost() {
    return authorityHost;
  }

  public void setAuthorityHost(String authorityHost) {
    this.authorityHost = authorityHost;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public int getConnectionTimeoutMs() {
    return connectionTimeoutMs;
  }

  public void setConnectionTimeoutMs(int connectionTimeoutMs) {
    this.connectionTimeoutMs = connectionTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  public long getUploadThresholdBytes() {
    return uploadThresholdBytes;
  }

  public void setUploadThresholdBytes(long uploadThresholdBytes) {
    this.uploadThresholdBytes = uploadThresholdBytes;
  }

  public long getBlockSizeBytes() {
    return blockSizeBytes;
  }

  public void setBlockSizeBytes(long blockSizeBytes) {
    this.blockSizeBytes = blockSizeBytes;
  }

  public int getUploadConcurrency() {
    return uploadConcurrency;
  }

  public void setUploadConcurrency(int uploadConcurrency) {
    this.uploadConcurrency = uploadConcurrency;
  }
}
