package com.github.klboke.kkrepo.storage.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreTypes;
import com.github.klboke.kkrepo.storage.azure.config.AzureStorageProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AzureBlobStoreProviderTest {

  @ParameterizedTest
  @ValueSource(strings = {"azure", "azure-blob", "azureblob", "azure-storage", "abs", "AZURE"})
  void claimsRowsTypedAsAzure(String type) {
    assertTrue(provider().supports(descriptor(type, Map.of())));
  }

  @Test
  void claimsLegacyRowsThatCarryAzureInTheEngineAttribute() {
    assertTrue(provider().supports(descriptor("", Map.of("engine", "azure"))));
  }

  @Test
  void leavesS3AndFileRowsAlone() {
    assertFalse(provider().supports(descriptor("s3", Map.of())));
    assertFalse(provider().supports(descriptor("file", Map.of("engine", "file"))));
    assertFalse(provider().supports(descriptor("", Map.of())));
  }

  @Test
  void readsTheContainerFromTheBucketColumn() {
    AzureBlobStoreConfig config = provider().configFor(descriptor("azure", Map.of()));

    assertEquals("artifacts", config.container());
    assertEquals(BlobStoreTypes.AZURE, provider().type());
  }

  @Test
  void acceptsAContainerAttributeWhenTheBucketColumnIsEmpty() {
    BlobStoreDescriptor descriptor = new BlobStoreDescriptor(
        1, "azure-store", "azure", "", "", "", "", Map.of("container", "from-attribute"));

    assertEquals("from-attribute", provider().configFor(descriptor).container());
  }

  @Test
  void inheritsNodeDefaultsForValuesTheRowLeavesBlank() {
    AzureStorageProperties defaults = new AzureStorageProperties();
    defaults.setAccountName("default-account");
    defaults.setUploadConcurrency(9);
    AzureBlobStoreProvider provider =
        new AzureBlobStoreProvider(new AzureBlobStorageFactory(new AzureClientFactory()), defaults);

    AzureBlobStoreConfig config = provider.configFor(descriptor("azure", Map.of()));

    assertEquals("default-account", config.accountName());
    assertEquals(9, config.uploadConcurrency());
  }

  @Test
  void mapsCredentialAttributesOntoTheConfig() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("credentialType", "workload-identity");
    attributes.put("clientId", "11111111-1111-1111-1111-111111111111");
    attributes.put("tenantId", "22222222-2222-2222-2222-222222222222");
    attributes.put("tokenFilePath", "/var/run/secrets/token");

    AzureBlobStoreConfig config = provider().configFor(descriptor("azure", attributes));

    assertEquals(AzureCredentialType.WORKLOAD_IDENTITY, config.credentialType());
    assertEquals("11111111-1111-1111-1111-111111111111", config.clientId());
    assertEquals("22222222-2222-2222-2222-222222222222", config.tenantId());
    assertEquals("/var/run/secrets/token", config.tokenFilePath());
  }

  @Test
  void theMetricsEngineLabelDistinguishesCredentialModes() {
    assertEquals("azure-default", provider().engine(descriptor("azure", Map.of())));
    assertEquals("azure-managed-identity",
        provider().engine(descriptor("azure", Map.of("credentialType", "managed-identity"))));
  }

  @Test
  void theSignatureTracksTheStoreConfiguration() {
    AzureBlobStoreProvider provider = provider();

    assertEquals(
        provider.signature(descriptor("azure", Map.of())),
        provider.signature(descriptor("azure", Map.of())));
    assertFalse(provider.signature(descriptor("azure", Map.of()))
        .equals(provider.signature(descriptor("azure", Map.of("credentialType", "managed-identity")))));
  }

  private static AzureBlobStoreProvider provider() {
    return new AzureBlobStoreProvider(
        new AzureBlobStorageFactory(new AzureClientFactory()), new AzureStorageProperties());
  }

  private static BlobStoreDescriptor descriptor(String type, Map<String, Object> attributes) {
    return new BlobStoreDescriptor(
        1, "azure-store", type, "https://kkrepo.blob.core.windows.net", "", "artifacts", "", attributes);
  }
}
