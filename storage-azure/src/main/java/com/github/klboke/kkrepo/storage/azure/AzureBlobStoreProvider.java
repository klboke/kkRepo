package com.github.klboke.kkrepo.storage.azure;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreTypes;
import com.github.klboke.kkrepo.storage.azure.config.AzureStorageProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exposes the Azure Blob Storage adapter through the generic provider SPI.
 *
 * <p>The Azure container is read from the {@code bucket} column so the generic blob model needs no
 * schema change; {@code container} is accepted as an attribute alias for readability.
 */
@Component
public class AzureBlobStoreProvider implements BlobStoreProvider {
  public static final String ATTR_ACCOUNT_NAME = "accountName";
  public static final String ATTR_CONTAINER = "container";
  public static final String ATTR_CREDENTIAL_TYPE = "credentialType";
  public static final String ATTR_ACCOUNT_KEY = "accountKey";
  public static final String ATTR_SAS_TOKEN = "sasToken";
  public static final String ATTR_CLIENT_ID = "clientId";
  public static final String ATTR_TENANT_ID = "tenantId";
  public static final String ATTR_TOKEN_FILE_PATH = "tokenFilePath";
  public static final String ATTR_AUTHORITY_HOST = "authorityHost";

  private static final Set<String> ALIASES =
      Set.of("azure", "azure-blob", "azureblob", "azure-storage", "abs");

  private final AzureBlobStorageFactory factory;
  private final AzureStorageProperties fallback;

  public AzureBlobStoreProvider(AzureBlobStorageFactory factory, AzureStorageProperties fallback) {
    this.factory = factory;
    this.fallback = fallback;
  }

  @Override
  public String type() {
    return BlobStoreTypes.AZURE;
  }

  @Override
  public boolean supports(BlobStoreDescriptor descriptor) {
    return ALIASES.contains(descriptor.normalizedType()) || ALIASES.contains(descriptor.normalizedEngine());
  }

  @Override
  public String signature(BlobStoreDescriptor descriptor) {
    return configFor(descriptor).signature();
  }

  @Override
  public BlobStorage create(BlobStoreDescriptor descriptor) {
    return factory.forStore(configFor(descriptor));
  }

  @Override
  public String engine(BlobStoreDescriptor descriptor) {
    // Distinguishes secretless deployments from shared-key ones in storage metrics.
    return "azure-" + configFor(descriptor).credentialType().id();
  }

  @Override
  public Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeStoreIds) {
    return factory.cachedStoreIdsMissingFrom(activeStoreIds);
  }

  @Override
  public void invalidate(long storeId) {
    factory.invalidate(storeId);
  }

  public AzureBlobStoreConfig configFor(BlobStoreDescriptor descriptor) {
    String container = valueOrDefault(
        descriptor.bucket(), descriptor.stringAttr(ATTR_CONTAINER, fallback.getContainer()));
    return new AzureBlobStoreConfig(
        descriptor.id(),
        descriptor.name(),
        valueOrDefault(descriptor.endpoint(), fallback.getEndpoint()),
        descriptor.stringAttr(ATTR_ACCOUNT_NAME, fallback.getAccountName()),
        container,
        descriptor.prefix(),
        AzureCredentialType.fromValue(
            descriptor.stringAttr(ATTR_CREDENTIAL_TYPE, fallback.getCredentialType())),
        descriptor.presentAttr(ATTR_ACCOUNT_KEY, fallback.getAccountKey()),
        descriptor.presentAttr(ATTR_SAS_TOKEN, fallback.getSasToken()),
        descriptor.stringAttr(ATTR_CLIENT_ID, fallback.getClientId()),
        descriptor.stringAttr(ATTR_TENANT_ID, fallback.getTenantId()),
        descriptor.stringAttr(ATTR_TOKEN_FILE_PATH, fallback.getTokenFilePath()),
        descriptor.stringAttr(ATTR_AUTHORITY_HOST, fallback.getAuthorityHost()),
        descriptor.intAttr("maxRetries", fallback.getMaxRetries()),
        descriptor.intAttr("connectionTimeoutMs", fallback.getConnectionTimeoutMs()),
        descriptor.intAttr("readTimeoutMs", fallback.getReadTimeoutMs()),
        descriptor.longAttr("uploadThresholdBytes", fallback.getUploadThresholdBytes()),
        descriptor.longAttr("blockSizeBytes", fallback.getBlockSizeBytes()),
        descriptor.intAttr("uploadConcurrency", fallback.getUploadConcurrency()));
  }

  private static String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
