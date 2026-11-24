package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Same client lifetime rules as {@link S3ClientFactory}, for the native OSS client. */
class OssClientFactoryLifecycleTest {
  private static OssClientFactory factory(Duration closeGrace) {
    return new OssClientFactory(OutboundTlsTrust.defaults(), config -> mock(OSSClient.class), closeGrace);
  }

  @Test
  void configurationsOfOneStoreWithDifferentSignaturesGetSeparateClientsAndNeitherIsClosed() {
    OssClientFactory factory = factory(Duration.ofHours(1));
    try {
      OSSClient runtime = factory.client(config(1, "bucket-a"));
      OSSClient admin = factory.client(config(1, "bucket-b"));

      assertNotSame(runtime, admin);
      assertSame(runtime, factory.client(config(1, "bucket-a")));
      try {
        verify(runtime, after(200).never()).close();
      } catch (Exception impossible) {
        throw new AssertionError(impossible);
      }
    } finally {
      factory.shutdown();
    }
  }

  @Test
  void aHandleRebuildsTheClientAfterInvalidationInsteadOfServingAClosedOne() throws Exception {
    OssClientFactory factory = factory(Duration.ZERO);
    try {
      Supplier<OSSClient> handle = factory.handle(config(1, "bucket-a"));
      OSSClient first = handle.get();

      factory.invalidate(1);
      verify(first, timeout(2_000)).close();

      OSSClient second = handle.get();
      assertNotSame(first, second);
      assertSame(second, handle.get());
    } finally {
      factory.shutdown();
    }
  }

  private static S3BlobStoreConfig config(long id, String bucket) {
    return S3BlobStoreConfig.of(id, "store-" + id, "http://localhost:9000", "us-east-1", bucket, "",
        Map.of("engine", "oss-native", "accessKey", "key", "secretKey", "secret"));
  }
}
