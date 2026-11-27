package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.DeleteObjectResult;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.github.klboke.kkrepo.core.BlobReference;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A native OSS response stream keeps its client open (a lease) until it is closed, on every path. */
class OssNativeBlobStorageStreamLeaseTest {
  private final OSSClient client = mock(OSSClient.class);
  private final AtomicInteger openLeases = new AtomicInteger();
  private final BlobReference reference = new BlobReference("bucket", "object", "sha", 3);
  private final OssNativeBlobStorage storage = new OssNativeBlobStorage(countingHandle(), config());

  @Test
  void aStreamHoldsItsLeaseUntilItIsClosed() throws Exception {
    GetObjectResult result = result("abc");
    when(client.getObject(any(GetObjectRequest.class))).thenReturn(result);

    InputStream stream = storage.get(reference).orElseThrow();
    assertEquals(1, openLeases.get());

    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
    assertEquals(1, openLeases.get(), "reading must not release the client; only close() does");

    stream.close();
    assertEquals(0, openLeases.get());
    verify(result).close();
  }

  @Test
  void aRangeStreamHoldsItsLeaseToo() throws Exception {
    GetObjectResult result = result("b");
    when(client.getObject(any(GetObjectRequest.class))).thenReturn(result);

    InputStream stream = storage.getRange(reference, 1, 1).orElseThrow();
    assertEquals(1, openLeases.get());

    stream.close();
    assertEquals(0, openLeases.get());
  }

  @Test
  void aMissingObjectOrAFailureOpeningTheStreamReleasesTheLease() {
    ServiceException missing = ServiceException.newBuilder().statusCode(404).build();
    when(client.getObject(any(GetObjectRequest.class))).thenThrow(new IllegalStateException(missing));
    assertTrue(storage.get(reference).isEmpty());
    assertEquals(0, openLeases.get());

    when(client.getObject(any(GetObjectRequest.class))).thenThrow(new IllegalStateException("boom"));
    assertThrows(IllegalStateException.class, () -> storage.get(reference));
    assertEquals(0, openLeases.get());
  }

  @Test
  void aDeleteHoldsItsClientForTheWholeSdkCallAndReleasesItEvenOnFailure() {
    AtomicInteger leasesDuringCall = new AtomicInteger();
    when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(call -> {
      leasesDuringCall.addAndGet(openLeases.get());
      return mock(DeleteObjectResult.class);
    });

    storage.delete(reference);

    assertEquals(1, leasesDuringCall.get());
    assertEquals(0, openLeases.get());

    when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new IllegalStateException("boom"));
    assertThrows(IllegalStateException.class, () -> storage.delete(reference));
    assertEquals(0, openLeases.get());
  }

  private ClientHandle<OSSClient> countingHandle() {
    return new ClientHandle<>() {
      @Override
      public OSSClient get() {
        return client;
      }

      @Override
      public Lease<OSSClient> lease() {
        openLeases.incrementAndGet();
        AtomicBoolean released = new AtomicBoolean();
        return new Lease<>() {
          @Override
          public OSSClient client() {
            return client;
          }

          @Override
          public void close() {
            if (released.compareAndSet(false, true)) {
              openLeases.decrementAndGet();
            }
          }
        };
      }
    };
  }

  private static GetObjectResult result(String body) {
    GetObjectResult result = mock(GetObjectResult.class);
    when(result.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return result;
  }

  private static S3BlobStoreConfig config() {
    return S3BlobStoreConfig.of(1, "oss-test", "https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou",
        "bucket", "prefix",
        Map.of("engine", S3BlobStoreConfig.ENGINE_OSS_NATIVE, "accessKey", "ak", "secretKey", "sk"));
  }
}
