package com.github.klboke.kkrepo.storage.file;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileBlobStoreProviderTest {

  @Test
  void aNodeWithoutTheFileFactoryRefusesToBuildStorage() {
    FileBlobStoreProvider provider = new FileBlobStoreProvider(null);
    BlobStoreDescriptor descriptor = new BlobStoreDescriptor(
        1, "file-store", "file", "", "", "", "", Map.of("path", "/var/lib/kkrepo/blobs"));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> provider.create(descriptor));

    assertTrue(error.getMessage().contains("File blob storage"), error.getMessage());
  }
}
