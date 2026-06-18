package com.github.klboke.nexusplus.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aliyun.sdk.service.oss2.transport.apache5client.Apache5HttpClient;
import java.lang.reflect.Field;
import java.util.Map;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;

class OssClientFactoryTest {

  @Test
  void apacheTransportUsesConfiguredPoolLimitsAndLeaseTimeout() throws Exception {
    S3BlobStoreConfig config = S3BlobStoreConfig.of(
        1,
        "oss",
        "https://oss-cn-hangzhou.aliyuncs.com",
        "cn-hangzhou",
        "bucket",
        "",
        Map.of(
            "engine", S3BlobStoreConfig.ENGINE_OSS_NATIVE,
            "maxConnections", 17,
            "connectionAcquisitionTimeoutMs", 2345));

    try (Apache5HttpClient client = OssClientFactory.buildHttpClient(config)) {
      PoolingHttpClientConnectionManager manager =
          (PoolingHttpClientConnectionManager) client.getConnectionManager();
      RequestConfig requestConfig = requestConfig(client);

      assertEquals(17, manager.getMaxTotal());
      assertEquals(17, manager.getDefaultMaxPerRoute());
      assertEquals(2345, requestConfig.getConnectionRequestTimeout().toMilliseconds());
    }
  }

  private static RequestConfig requestConfig(Apache5HttpClient client) throws Exception {
    Field field = Apache5HttpClient.class.getDeclaredField("requestConfig");
    field.setAccessible(true);
    return (RequestConfig) field.get(client);
  }
}
