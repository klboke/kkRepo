package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AzureBlobStoreConfigTest {

  @Test
  void derivesTheBlobServiceEndpointFromTheAccountName() {
    assertEquals("https://kkrepo.blob.core.windows.net",
        config(b -> b.accountName = "kkrepo").serviceEndpoint());
  }

  @Test
  void keepsAnExplicitEndpointForSovereignCloudsAndEmulators() {
    AzureBlobStoreConfig config = config(b -> {
      b.endpoint = "http://127.0.0.1:10000/devstoreaccount1/";
      b.accountName = "devstoreaccount1";
    });

    assertEquals("http://127.0.0.1:10000/devstoreaccount1", config.serviceEndpoint());
  }

  @Test
  void infersTheAccountNameFromAStandardEndpointHost() {
    assertEquals("kkrepo",
        config(b -> b.endpoint = "https://kkrepo.blob.core.windows.net").resolvedAccountName());
  }

  @Test
  void doesNotInferAnAccountNameFromAPathStyleEmulatorEndpoint() {
    assertEquals("",
        config(b -> b.endpoint = "http://127.0.0.1:10000/devstoreaccount1").resolvedAccountName());
  }

  @Test
  void appliesThePrefixToObjectKeys() {
    assertEquals("team/a/b", config(b -> b.prefix = "/team/").objectKey("a/b"));
    assertEquals("a/b", config(b -> b.prefix = "").objectKey("a/b"));
  }

  @Test
  void clampsBlockSizeIntoTheRangeAzureAccepts() {
    assertEquals(AzureBlobStoreConfig.MIN_BLOCK_BYTES, config(b -> b.blockSizeBytes = 1).blockSizeBytes());
    assertEquals(AzureBlobStoreConfig.MAX_BLOCK_BYTES,
        config(b -> b.blockSizeBytes = Long.MAX_VALUE).blockSizeBytes());
  }

  @Test
  void defaultCredentialTypeIsTheSecretlessPlatformChain() {
    AzureBlobStoreConfig config = config(b -> b.accountName = "kkrepo");

    assertEquals(AzureCredentialType.DEFAULT, config.credentialType());
    assertTrue(config.usesPlatformIdentity());
  }

  @Test
  void managedAndWorkloadIdentityValidateWithoutAnySecret() {
    config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.MANAGED_IDENTITY;
    }).validate();
    config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.WORKLOAD_IDENTITY;
    }).validate();
  }

  @Test
  void rejectsStoredSecretsWhenAPlatformIdentityIsSelected() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.WORKLOAD_IDENTITY;
      b.accountKey = "c2VjcmV0";
    }).validate());

    assertTrue(error.getMessage().contains("workload-identity"), error.getMessage());
  }

  @Test
  void requiresTheMatchingSecretForSecretBasedCredentials() {
    assertThrows(IllegalArgumentException.class, () -> config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.ACCOUNT_KEY;
    }).validate());
    assertThrows(IllegalArgumentException.class, () -> config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.SAS;
    }).validate());
  }

  @Test
  void accountKeyCredentialsNeedAResolvableAccountName() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> config(b -> {
      b.endpoint = "http://127.0.0.1:10000/devstoreaccount1";
      b.accountName = "";
      b.credentialType = AzureCredentialType.ACCOUNT_KEY;
      b.accountKey = "c2VjcmV0";
    }).validate());

    assertTrue(error.getMessage().contains("accountName"), error.getMessage());
  }

  @Test
  void requiresAContainerAndSomeWayToAddressTheAccount() {
    assertThrows(IllegalArgumentException.class, () -> config(b -> {
      b.accountName = "kkrepo";
      b.container = "";
    }).validate());
    assertThrows(IllegalArgumentException.class, () -> config(b -> {
      b.accountName = "";
      b.endpoint = "";
    }).validate());
  }

  @Test
  void signatureChangesWithEverySettingThatInvalidatesAClient() {
    AzureBlobStoreConfig base = config(b -> b.accountName = "kkrepo");

    assertEquals(base.signature(), config(b -> b.accountName = "kkrepo").signature());
    assertNotEquals(base.signature(), config(b -> {
      b.accountName = "kkrepo";
      b.container = "other";
    }).signature());
    assertNotEquals(base.signature(), config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.MANAGED_IDENTITY;
    }).signature());
    assertNotEquals(base.signature(), config(b -> {
      b.accountName = "kkrepo";
      b.clientId = "11111111-1111-1111-1111-111111111111";
    }).signature());
  }

  @Test
  void signatureNeverLeaksSecretMaterial() {
    AzureBlobStoreConfig config = config(b -> {
      b.accountName = "kkrepo";
      b.credentialType = AzureCredentialType.ACCOUNT_KEY;
      b.accountKey = "super-secret-account-key";
    });

    assertFalse(config.signature().contains("super-secret-account-key"), config.signature());
  }

  private static AzureBlobStoreConfig config(java.util.function.Consumer<Builder> customizer) {
    Builder builder = new Builder();
    customizer.accept(builder);
    return builder.build();
  }

  /** Mutable test builder; production code constructs the record directly. */
  private static final class Builder {
    private String endpoint = "";
    private String accountName = "";
    private String container = "artifacts";
    private String prefix = "";
    private AzureCredentialType credentialType = AzureCredentialType.DEFAULT;
    private String accountKey = "";
    private String sasToken = "";
    private String clientId = "";
    private long blockSizeBytes = 16L * 1024 * 1024;

    private AzureBlobStoreConfig build() {
      return new AzureBlobStoreConfig(
          1, "azure-store", endpoint, accountName, container, prefix, credentialType,
          accountKey, sasToken, clientId, "", "", "", 3, 5000, 120000,
          64L * 1024 * 1024, blockSizeBytes, 4);
    }
  }
}
