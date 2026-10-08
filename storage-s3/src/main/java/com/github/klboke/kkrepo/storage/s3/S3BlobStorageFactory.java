package com.github.klboke.kkrepo.storage.s3;

import com.github.klboke.kkrepo.core.BlobStorage;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class S3BlobStorageFactory {
  private final S3ClientFactory clientFactory;
  private final OssClientFactory ossClientFactory;

  public S3BlobStorageFactory(
      S3ClientFactory clientFactory,
      OssClientFactory ossClientFactory) {
    this.clientFactory = clientFactory;
    this.ossClientFactory = ossClientFactory;
  }

  public BlobStorage forStore(S3BlobStoreConfig config) {
    // Exhaustive over Engine: adding an engine forces a compile error here until it's handled.
    return switch (config.engineType()) {
      case OSS_NATIVE -> new OssNativeBlobStorage(ossClientFactory.client(config), config);
      case AWS_S3 -> new S3BlobStorage(clientFactory.client(config), config);
    };
  }

  /** Clients are keyed by store ID even when no BlobStorage wrapper is cached. */
  public Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeIds) {
    Set<Long> missing = new HashSet<>(clientFactory.cachedStoreIds());
    missing.addAll(ossClientFactory.cachedStoreIds());
    missing.removeAll(activeIds);
    return missing;
  }

  public void invalidate(long id) {
    clientFactory.invalidate(id);
    ossClientFactory.invalidate(id);
  }
}
