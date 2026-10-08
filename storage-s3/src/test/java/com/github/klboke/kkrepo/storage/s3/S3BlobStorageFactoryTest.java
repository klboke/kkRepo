package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class S3BlobStorageFactoryTest {
  @Test
  void identifiesAndClosesClientsWhoseStoreIdsAreNoLongerConfigured() {
    S3ClientFactory aws = new S3ClientFactory();
    OssClientFactory oss = new OssClientFactory();
    S3BlobStorageFactory factory = new S3BlobStorageFactory(aws, oss);
    try {
      aws.client(config(1, "aws-s3"));
      oss.client(config(2, "oss-native"));

      assertEquals(Set.of(1L), factory.cachedStoreIdsMissingFrom(Set.of(2L)));
      factory.invalidate(1);
      assertEquals(Set.of(), aws.cachedStoreIds());
      assertEquals(Set.of(), factory.cachedStoreIdsMissingFrom(Set.of(2L)));

      factory.invalidate(2);
      assertEquals(Set.of(), oss.cachedStoreIds());
    } finally {
      aws.shutdown();
      oss.shutdown();
    }
  }

  private S3BlobStoreConfig config(long id, String engine) {
    return S3BlobStoreConfig.of(id, "store-" + id, "http://127.0.0.1:9000",
        "us-east-1", "bucket", "", Map.of(
            "engine", engine, "accessKey", "key", "secretKey", "secret"));
  }
}
