package com.github.klboke.kkrepo.storage.azure;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Builds {@link AzureBlobStorage} wrappers over clients cached by {@link AzureClientFactory}. */
@Component
public class AzureBlobStorageFactory {
  private final AzureClientFactory clientFactory;

  public AzureBlobStorageFactory(AzureClientFactory clientFactory) {
    this.clientFactory = clientFactory;
  }

  public BlobStorage forStore(AzureBlobStoreConfig config) {
    return new AzureBlobStorage(clientFactory.client(config), config);
  }

  /** Clients are keyed by store ID even when no wrapper is cached for them. */
  public Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeIds) {
    Set<Long> missing = new HashSet<>(clientFactory.cachedStoreIds());
    missing.removeAll(activeIds);
    return missing;
  }

  public void invalidate(long id) {
    clientFactory.invalidate(id);
  }
}
