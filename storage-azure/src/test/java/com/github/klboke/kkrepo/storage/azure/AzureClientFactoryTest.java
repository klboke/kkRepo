package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.WorkloadIdentityCredential;
import com.azure.storage.blob.BlobServiceClient;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AzureClientFactoryTest {
  private static final String ACCOUNT_KEY = "a2tyZXBvLWFjY291bnQta2V5";

  @TempDir
  Path directory;

  @Test
  void defaultCredentialTypeBuildsThePlatformChain() {
    AzureClientFactory factory = new AzureClientFactory();

    assertInstanceOf(DefaultAzureCredential.class,
        factory.tokenCredential(config(AzureCredentialType.DEFAULT, "", "")));
  }

  @Test
  void managedIdentityIsSelectedExplicitly() {
    AzureClientFactory factory = new AzureClientFactory();

    assertInstanceOf(ManagedIdentityCredential.class,
        factory.tokenCredential(config(AzureCredentialType.MANAGED_IDENTITY, "", "")));
  }

  @Test
  void userAssignedManagedIdentityAcceptsAClientId() {
    AzureClientFactory factory = new AzureClientFactory();

    assertInstanceOf(ManagedIdentityCredential.class, factory.tokenCredential(
        config(AzureCredentialType.MANAGED_IDENTITY, "11111111-1111-1111-1111-111111111111", "")));
  }

  @Test
  void workloadIdentityIsSelectedExplicitly() {
    AzureClientFactory factory = new AzureClientFactory();

    assertInstanceOf(WorkloadIdentityCredential.class,
        factory.tokenCredential(workloadIdentityConfig("/var/run/secrets/azure/tokens/azure-identity-token")));
  }

  @Test
  void incompleteWorkloadIdentityReportsWhatIsMissing() {
    // Only meaningful when the webhook has not already projected a token file into the environment.
    assumeTrue(System.getenv("AZURE_FEDERATED_TOKEN_FILE") == null);
    AzureClientFactory factory = new AzureClientFactory();

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> factory.tokenCredential(workloadIdentityConfig("")));

    assertTrue(error.getMessage().contains("AZURE_FEDERATED_TOKEN_FILE"), error.getMessage());
    assertTrue(error.getMessage().contains("azure-store"), error.getMessage());
  }

  private static AzureBlobStoreConfig workloadIdentityConfig(String tokenFilePath) {
    return new AzureBlobStoreConfig(
        7, "azure-store", "", "kkrepo", "artifacts", "", AzureCredentialType.WORKLOAD_IDENTITY,
        "", "", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
        tokenFilePath, "", 3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);
  }

  @Test
  void clientsAreCachedPerStoreWhileTheConfigurationIsUnchanged() {
    AzureClientFactory factory = new AzureClientFactory();
    AzureBlobStoreConfig config = accountKeyConfig("artifacts");

    BlobServiceClient first = factory.client(config);

    assertSame(first, factory.client(accountKeyConfig("artifacts")));
  }

  @Test
  void maxRetriesConfiguresTheStorageRetryPolicy() {
    AzureBlobStoreConfig noRetries = new AzureBlobStoreConfig(
        7, "azure-store", "", "kkrepo", "artifacts", "", AzureCredentialType.ACCOUNT_KEY,
        ACCOUNT_KEY, "", "", "", "", "", 0, 5000, 120000,
        64L * 1024 * 1024, 16L * 1024 * 1024, 4);

    // The first attempt counts as a try, so N retries means N + 1 tries.
    assertEquals(4, AzureClientFactory.retryOptions(accountKeyConfig("artifacts")).getMaxTries());
    assertEquals(1, AzureClientFactory.retryOptions(noRetries).getMaxTries());
  }

  @Test
  void aChangedConfigurationRebuildsTheClient() {
    AzureClientFactory factory = new AzureClientFactory();
    BlobServiceClient first = factory.client(accountKeyConfig("artifacts"));

    assertNotSame(first, factory.client(accountKeyConfig("other-container")));
  }

  @Test
  void invalidateDropsTheNodeLocalClient() {
    AzureClientFactory factory = new AzureClientFactory();
    BlobServiceClient first = factory.client(accountKeyConfig("artifacts"));
    assertEquals(Set.of(7L), factory.cachedStoreIds());

    factory.invalidate(7);

    assertTrue(factory.cachedStoreIds().isEmpty());
    assertNotSame(first, factory.client(accountKeyConfig("artifacts")));
  }

  @Test
  void shutdownReleasesEveryCachedClient() {
    AzureClientFactory factory = new AzureClientFactory();
    factory.client(accountKeyConfig("artifacts"));

    factory.shutdown();

    assertTrue(factory.cachedStoreIds().isEmpty());
  }

  @Test
  void withoutATrustBundleTheSdkDefaultHttpClientIsUsed() {
    AzureClientFactory factory = new AzureClientFactory();

    assertNull(factory.trustedHttpClient(accountKeyConfig("artifacts")));
  }

  @Test
  void aConfiguredTrustBundleIsAppliedToBlobTraffic() throws Exception {
    AzureClientFactory factory = new AzureClientFactory(OutboundTlsTrust.fromPemBundle(pemBundle()));

    assertNotNull(factory.trustedHttpClient(accountKeyConfig("artifacts")),
        "kkrepo.tls.ca-certificates must reach the Azure HTTP client");
  }

  @Test
  void aConfiguredTrustBundleAlsoCoversTokenAcquisition() throws Exception {
    AzureClientFactory factory = new AzureClientFactory(OutboundTlsTrust.fromPemBundle(pemBundle()));
    AzureBlobStoreConfig config = config(AzureCredentialType.MANAGED_IDENTITY, "", "");

    // Entra ID token calls traverse the same intercepting proxy as blob traffic.
    assertInstanceOf(ManagedIdentityCredential.class,
        factory.tokenCredential(config, factory.trustedHttpClient(config)));
  }

  @Test
  void aTrustedClientStillBuildsAUsableServiceClient() throws Exception {
    AzureClientFactory factory = new AzureClientFactory(OutboundTlsTrust.fromPemBundle(pemBundle()));

    assertNotNull(factory.client(accountKeyConfig("artifacts")));
  }

  @Test
  void sasAndTokenCredentialsBuildServiceClients() {
    AzureClientFactory factory = new AzureClientFactory();

    // Building does not contact Azure; the credential is only used on the first request.
    assertNotNull(factory.client(new AzureBlobStoreConfig(
        8, "sas-store", "", "kkrepo", "artifacts", "", AzureCredentialType.SAS,
        "", "sv=2024-01-01&sig=x", "", "", "", "", 3, 5000, 120000,
        64L * 1024 * 1024, 16L * 1024 * 1024, 4)));
    assertNotNull(factory.client(new AzureBlobStoreConfig(
        9, "identity-store", "", "kkrepo", "artifacts", "", AzureCredentialType.MANAGED_IDENTITY,
        "", "", "", "", "", "", 3, 5000, 120000,
        64L * 1024 * 1024, 16L * 1024 * 1024, 4)));
  }

  @Test
  void everyTokenModeHonoursExplicitIdentitySettingsAndTheTrustBundle() throws Exception {
    AzureClientFactory factory = new AzureClientFactory(OutboundTlsTrust.fromPemBundle(pemBundle()));
    AzureBlobStoreConfig workload = new AzureBlobStoreConfig(
        7, "azure-store", "", "kkrepo", "artifacts", "", AzureCredentialType.WORKLOAD_IDENTITY,
        "", "", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
        "/var/run/secrets/azure/tokens/azure-identity-token", "https://login.microsoftonline.us/",
        3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);
    AzureBlobStoreConfig chain = new AzureBlobStoreConfig(
        7, "azure-store", "", "kkrepo", "artifacts", "", AzureCredentialType.DEFAULT,
        "", "", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
        "", "https://login.microsoftonline.us/",
        3, 5000, 120000, 64L * 1024 * 1024, 16L * 1024 * 1024, 4);

    assertInstanceOf(WorkloadIdentityCredential.class,
        factory.tokenCredential(workload, factory.trustedHttpClient(workload)));
    assertInstanceOf(DefaultAzureCredential.class,
        factory.tokenCredential(chain, factory.trustedHttpClient(chain)));
  }

  private String pemBundle() throws Exception {
    Path bundle = directory.resolve("ca.pem");
    try (InputStream stream = AzureClientFactoryTest.class.getResourceAsStream("/tls/test-ca.pem")) {
      Files.writeString(bundle, new String(stream.readAllBytes(), StandardCharsets.US_ASCII));
    }
    return bundle.toString();
  }

  private static AzureBlobStoreConfig config(
      AzureCredentialType credentialType, String clientId, String tenantId) {
    return new AzureBlobStoreConfig(
        7, "azure-store", "", "kkrepo", "artifacts", "", credentialType,
        "", "", clientId, tenantId, "", "", 3, 5000, 120000,
        64L * 1024 * 1024, 16L * 1024 * 1024, 4);
  }

  private static AzureBlobStoreConfig accountKeyConfig(String container) {
    return new AzureBlobStoreConfig(
        7, "azure-store", "", "kkrepo", container, "", AzureCredentialType.ACCOUNT_KEY,
        ACCOUNT_KEY, "", "", "", "", "", 3, 5000, 120000,
        64L * 1024 * 1024, 16L * 1024 * 1024, 4);
  }
}
