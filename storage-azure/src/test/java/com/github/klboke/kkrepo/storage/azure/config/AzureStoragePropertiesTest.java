package com.github.klboke.kkrepo.storage.azure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AzureStoragePropertiesTest {

  @Test
  void bindsEveryDocumentedNodeDefault() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("kkrepo.storage.azure.endpoint", "https://kkrepo.blob.core.chinacloudapi.cn");
    values.put("kkrepo.storage.azure.account-name", "kkrepo");
    values.put("kkrepo.storage.azure.container", "artifacts");
    values.put("kkrepo.storage.azure.credential-type", "account-key");
    values.put("kkrepo.storage.azure.account-key", "a2V5");
    values.put("kkrepo.storage.azure.sas-token", "sv=2024-01-01&sig=x");
    values.put("kkrepo.storage.azure.client-id", "11111111-1111-1111-1111-111111111111");
    values.put("kkrepo.storage.azure.tenant-id", "22222222-2222-2222-2222-222222222222");
    values.put("kkrepo.storage.azure.token-file-path", "/var/run/secrets/token");
    values.put("kkrepo.storage.azure.authority-host", "https://login.chinacloudapi.cn/");
    values.put("kkrepo.storage.azure.max-retries", "5");
    values.put("kkrepo.storage.azure.connection-timeout-ms", "1000");
    values.put("kkrepo.storage.azure.read-timeout-ms", "2000");
    values.put("kkrepo.storage.azure.upload-threshold-bytes", "1048576");
    values.put("kkrepo.storage.azure.block-size-bytes", "4194304");
    values.put("kkrepo.storage.azure.upload-concurrency", "8");

    AzureStorageProperties properties = new Binder(new MapConfigurationPropertySource(values))
        .bind("kkrepo.storage.azure", AzureStorageProperties.class)
        .get();

    assertEquals("https://kkrepo.blob.core.chinacloudapi.cn", properties.getEndpoint());
    assertEquals("kkrepo", properties.getAccountName());
    assertEquals("artifacts", properties.getContainer());
    assertEquals("account-key", properties.getCredentialType());
    assertEquals("a2V5", properties.getAccountKey());
    assertEquals("sv=2024-01-01&sig=x", properties.getSasToken());
    assertEquals("11111111-1111-1111-1111-111111111111", properties.getClientId());
    assertEquals("22222222-2222-2222-2222-222222222222", properties.getTenantId());
    assertEquals("/var/run/secrets/token", properties.getTokenFilePath());
    assertEquals("https://login.chinacloudapi.cn/", properties.getAuthorityHost());
    assertEquals(5, properties.getMaxRetries());
    assertEquals(1000, properties.getConnectionTimeoutMs());
    assertEquals(2000, properties.getReadTimeoutMs());
    assertEquals(1048576L, properties.getUploadThresholdBytes());
    assertEquals(4194304L, properties.getBlockSizeBytes());
    assertEquals(8, properties.getUploadConcurrency());
  }

  @Test
  void defaultsMatchTheDocumentation() {
    AzureStorageProperties properties = new AzureStorageProperties();

    assertEquals("", properties.getEndpoint());
    assertEquals("kkrepo", properties.getContainer());
    assertEquals("default", properties.getCredentialType());
    assertEquals(64L * 1024 * 1024, properties.getUploadThresholdBytes());
    assertEquals(16L * 1024 * 1024, properties.getBlockSizeBytes());
    assertEquals(4, properties.getUploadConcurrency());
    assertEquals(5000, properties.getConnectionTimeoutMs());
    assertEquals(120000, properties.getReadTimeoutMs());
    assertEquals(3, properties.getMaxRetries());
  }
}
