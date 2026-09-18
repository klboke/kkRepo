package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        Map.ofEntries(
            Map.entry("engine", "oss"),
            Map.entry("accessKey", "ak"),
            Map.entry("secretKey", "sk"),
            Map.entry("pathStyleAccess", "false"),
            Map.entry("maxConnections", "0"),
            Map.entry("connectionTimeoutMs", "bad"),
            Map.entry("socketTimeoutMs", 0),
            Map.entry("connectionAcquisitionTimeoutMs", -1),
            Map.entry("tcpKeepAlive", false),
            Map.entry("multipartThresholdBytes", -10),
            Map.entry("multipartPartSizeBytes", 1),
            Map.entry("multipartConcurrency", 0)));

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

  @Test
  void emptyCredentialsSelectDefaultChainButPartialCredentialsAndEmptyOssKeysAreRejected() {
    for (Map<String, Object> attributes : java.util.List.<Map<String, Object>>of(
        Map.of(), Map.of("accessKey", "", "secretKey", "  "))) {
      S3BlobStoreConfig config = S3BlobStoreConfig.of(1, "aws", "http://localhost", "us-east-1",
          "bucket", "", attributes);
      assertTrue(config.usesDefaultCredentials());
      assertEquals("", config.accessKey());
      assertEquals("", config.secretKey());
    }
    for (Map<String, Object> attributes : java.util.List.<Map<String, Object>>of(
        Map.of("accessKey", "ak"), Map.of("secretKey", "sk"), Map.of("engine", "oss-native"))) {
      assertThrows(IllegalArgumentException.class, () -> S3BlobStoreConfig.of(
          1, "invalid", "http://localhost", "us-east-1", "bucket", "", attributes));
    }
    assertFalse(config("secret", 4).usesDefaultCredentials());
  }

  @Test
  void explicitEmptyOrNullCredentialsDoNotInheritGlobalKeys() {
    assertEquals("global", S3BlobStoreConfig.credentialAttribute(Map.of(), "accessKey", "global"));
    assertEquals("", S3BlobStoreConfig.credentialAttribute(Map.of("accessKey", " "), "accessKey", "global"));
    Map<String, Object> nullAttribute = new java.util.HashMap<>();
    nullAttribute.put("accessKey", null);
    assertEquals("", S3BlobStoreConfig.credentialAttribute(nullAttribute, "accessKey", "global"));
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
