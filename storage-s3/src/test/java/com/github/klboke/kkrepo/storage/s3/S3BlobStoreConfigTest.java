package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class S3BlobStoreConfigTest {
  @Test
  void attributesAreParsedNormalizedAndClamped() {
    S3BlobStoreConfig config = S3BlobStoreConfig.of(
        7,
        "store",
        "https://example.test",
        "us-east-1",
        "bucket",
        null,
        Map.of(
            "engine", "oss",
            "pathStyleAccess", "false",
            "maxConnections", "0",
            "connectionTimeoutMs", "bad",
            "socketTimeoutMs", 0,
            "connectionAcquisitionTimeoutMs", -1,
            "tcpKeepAlive", false,
            "multipartThresholdBytes", -10,
            "multipartPartSizeBytes", 1,
            "multipartConcurrency", 0));

    assertEquals(S3BlobStoreConfig.ENGINE_OSS_NATIVE, config.engine());
    assertTrue(config.usesOssNative());
    assertFalse(config.pathStyleAccess());
    assertEquals(1, config.maxConnections());
    assertEquals(5000, config.connectionTimeoutMs());
    assertEquals(1, config.socketTimeoutMs());
    assertEquals(1, config.connectionAcquisitionTimeoutMs());
    assertEquals(0, config.multipartThresholdBytes());
    assertEquals(5L * 1024 * 1024, config.multipartPartSizeBytes());
    assertEquals(1, config.multipartConcurrency());
    assertTrue(config.signature().contains("oss-native|https://example.test"));
  }

  @Test
  void equalityIncludesCredentialsAndOperationalSettings() {
    S3BlobStoreConfig first = config("secret-one", 4);
    S3BlobStoreConfig same = config("secret-one", 4);
    S3BlobStoreConfig changedSecret = config("secret-two", 4);
    S3BlobStoreConfig changedConcurrency = config("secret-one", 8);

    assertEquals(first, same);
    assertEquals(first.hashCode(), same.hashCode());
    assertNotEquals(first, changedSecret);
    assertNotEquals(first, changedConcurrency);
    assertNotEquals(first.signature(), changedSecret.signature());
  }

  private static S3BlobStoreConfig config(String secret, int concurrency) {
    return S3BlobStoreConfig.of(
        1,
        "store",
        "https://s3.example.test",
        "us-east-1",
        "bucket",
        "prefix",
        Map.of(
            "accessKey", "access",
            "secretKey", secret,
            "multipartConcurrency", concurrency));
  }
}
