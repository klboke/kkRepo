package com.github.klboke.kkrepo.storage.azure;

import java.util.Locale;

/**
 * How an Azure blob store authenticates to the storage account.
 *
 * <p>The token-based modes are preferred in shared deployments: they issue short-lived OAuth
 * tokens from the platform instead of persisting a long-lived secret in {@code blob_store}, and
 * every replica obtains its own token independently.
 */
public enum AzureCredentialType {
  /**
   * {@code DefaultAzureCredential} chain. Picks up workload identity, managed identity, and
   * environment credentials automatically, so one configuration works across AKS, VMs, and local
   * developer machines.
   */
  DEFAULT("default"),

  /** Explicit IMDS managed identity; set {@code clientId} for a user-assigned identity. */
  MANAGED_IDENTITY("managed-identity"),

  /** Explicit AKS/Kubernetes federated workload identity backed by a projected token file. */
  WORKLOAD_IDENTITY("workload-identity"),

  /** Storage account shared key. */
  ACCOUNT_KEY("account-key"),

  /** Pre-issued shared access signature. */
  SAS("sas");

  private final String id;

  AzureCredentialType(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  /** True when the platform supplies short-lived tokens and no secret is stored by kkRepo. */
  public boolean isTokenBased() {
    return this == DEFAULT || this == MANAGED_IDENTITY || this == WORKLOAD_IDENTITY;
  }

  /** Whether this mode reads {@code clientId} to select a user-assigned or federated identity. */
  public boolean usesClientId() {
    return isTokenBased();
  }

  /** Whether this mode reads {@code tenantId} and {@code authorityHost}. */
  public boolean usesTenant() {
    return this == DEFAULT || this == WORKLOAD_IDENTITY;
  }

  /**
   * Whether this mode reads {@code tokenFilePath}. {@code DefaultAzureCredential} only reads the
   * federated token file from {@code AZURE_FEDERATED_TOKEN_FILE}, so only the explicit workload
   * identity mode honours a per-store path.
   */
  public boolean usesTokenFilePath() {
    return this == WORKLOAD_IDENTITY;
  }

  /**
   * Resolves the persisted id plus common aliases. Unknown or blank values fall back to
   * {@link #DEFAULT}, which keeps secretless deployments working without explicit configuration.
   * Use {@link #parse(String)} for operator input, where a typo must be rejected instead.
   */
  public static AzureCredentialType fromValue(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    return lookup(value).orElse(DEFAULT);
  }

  /**
   * Strict variant of {@link #fromValue(String)} for API input: a blank value means
   * {@link #DEFAULT}, but an unrecognised value is rejected so a misspelt mode cannot silently
   * switch a store to the default chain and discard the secret supplied with it.
   */
  public static AzureCredentialType parse(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    return lookup(value).orElseThrow(() -> new IllegalArgumentException(
        "credentialType must be one of default, managed-identity, workload-identity, account-key or sas"));
  }

  private static java.util.Optional<AzureCredentialType> lookup(String value) {
    return java.util.Optional.ofNullable(switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "default", "default-chain", "defaultazurecredential" -> DEFAULT;
      case "account-key", "accountkey", "shared-key", "sharedkey", "key" -> ACCOUNT_KEY;
      case "sas", "sas-token", "sastoken" -> SAS;
      case "managed-identity", "managedidentity", "msi", "managed" -> MANAGED_IDENTITY;
      case "workload-identity", "workloadidentity", "workload", "federated" -> WORKLOAD_IDENTITY;
      default -> null;
    });
  }
}
