package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobReference;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** A response stream keeps its client open (a lease) until it is closed, on every path. */
class S3BlobStorageStreamLeaseTest {
  private final S3Client client = mock(S3Client.class);
  private final AtomicInteger openLeases = new AtomicInteger();
  private final BlobReference reference = new BlobReference("bucket", "object", "sha", 3);
  private final S3BlobStorage storage = new S3BlobStorage(countingHandle(), config());

  @Test
  void aStreamHoldsItsLeaseUntilItIsClosedEvenAfterBeingReadToTheEnd() throws Exception {
    when(client.getObject(any(GetObjectRequest.class))).thenReturn(response("abc"));

    InputStream stream = storage.get(reference).orElseThrow();
    assertEquals(1, openLeases.get());

    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
    assertEquals(1, openLeases.get(), "reading must not release the client; only close() does");

    stream.close();
    assertEquals(0, openLeases.get());
    stream.close();
    assertEquals(0, openLeases.get(), "closing twice must not release twice");
  }

  @Test
  void aRangeStreamHoldsItsLeaseToo() throws Exception {
    when(client.getObject(any(GetObjectRequest.class))).thenReturn(response("b"));

    InputStream stream = storage.getRange(reference, 1, 1).orElseThrow();
    assertEquals(1, openLeases.get());

    stream.close();
    assertEquals(0, openLeases.get());
  }

  @Test
  void aMissingObjectOrAFailureOpeningTheStreamReleasesTheLease() {
    when(client.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
    assertTrue(storage.get(reference).isEmpty());
    assertEquals(0, openLeases.get());

    when(client.getObject(any(GetObjectRequest.class))).thenThrow(new IllegalStateException("boom"));
    assertThrows(IllegalStateException.class, () -> storage.get(reference));
    assertEquals(0, openLeases.get());
  }

  private ClientHandle<S3Client> countingHandle() {
    return new ClientHandle<>() {
      @Override
      public S3Client get() {
        return client;
      }

      @Override
      public Lease<S3Client> lease() {
        openLeases.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        return new Lease<>() {
          @Override
          public S3Client client() {
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

  private static ResponseInputStream<GetObjectResponse> response(String body) {
    return new ResponseInputStream<>(
        GetObjectResponse.builder().build(),
        AbortableInputStream.create(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
  }

  private static S3BlobStoreConfig config() {
    return S3BlobStoreConfig.of(1, "test", "http://localhost:9000", "us-east-1", "bucket", "", Map.of());
  }
}
