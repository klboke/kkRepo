package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S3BlobStoreProviderTest {

  @Test
  void aNodeWithoutTheS3FactoryRefusesToBuildStorage() {
    S3BlobStoreProvider provider = new S3BlobStoreProvider(null, new S3StorageProperties());

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> provider.create(descriptor(Map.of())));

    assertTrue(error.getMessage().contains("S3"), error.getMessage());
  }

  @Test
  void rowValuesOverrideTheNodeDefaults() {
    S3StorageProperties defaults = new S3StorageProperties();
    defaults.setRegion("us-east-1");
    S3BlobStoreProvider provider = new S3BlobStoreProvider(null, defaults);

    S3BlobStoreConfig config = provider.configFor(descriptor(Map.of("multipartConcurrency", "6")));

    assertEquals("eu-central-1", config.region());
    assertEquals("bucket", config.bucket());
    assertEquals(6, config.multipartConcurrency());
  }

  private static BlobStoreDescriptor descriptor(Map<String, Object> attributes) {
    return new BlobStoreDescriptor(
        1, "s3-store", "s3", "http://127.0.0.1:9000", "eu-central-1", "bucket", "", attributes);
  }
}
