package com.github.klboke.kkrepo.storage.file;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreTypes;
import org.springframework.stereotype.Component;

/**
 * Exposes the development/test filesystem adapter through the generic provider SPI. File stores
 * hold no cached client, so client invalidation is a no-op.
 */
@Component
public class FileBlobStoreProvider implements BlobStoreProvider {
  private final FileBlobStorageFactory factory;

  public FileBlobStoreProvider(FileBlobStorageFactory factory) {
    this.factory = factory;
  }

  @Override
  public String type() {
    return BlobStoreTypes.FILE;
  }

  @Override
  public boolean supports(BlobStoreDescriptor descriptor) {
    return FileBlobStoreConfig.isFileStore(descriptor.type(), descriptor.attributes());
  }

  @Override
  public String signature(BlobStoreDescriptor descriptor) {
    return configFor(descriptor).signature();
  }

  @Override
  public BlobStorage create(BlobStoreDescriptor descriptor) {
    return factory.forStore(configFor(descriptor));
  }

  public FileBlobStoreConfig configFor(BlobStoreDescriptor descriptor) {
    if (factory == null) {
      throw new IllegalStateException("File blob storage is not available on this node");
    }
    return factory.configFor(
        descriptor.id(),
        descriptor.name(),
        descriptor.stringAttr(FileBlobStoreConfig.ATTR_PATH, ""));
  }
}
