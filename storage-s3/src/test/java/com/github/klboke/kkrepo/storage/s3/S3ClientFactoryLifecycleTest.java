package com.github.klboke.kkrepo.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.github.klboke.kkrepo.core.http.OutboundTlsTrust;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import com.github.klboke.kkrepo.core.BlobReference;

/** Client lifetime rules of {@link S3ClientFactory}, observed through mock clients. */
class S3ClientFactoryLifecycleTest {
  private final List<S3Client> built = new CopyOnWriteArrayList<>();

  private S3ClientFactory factory(Duration closeGrace) {
    return new S3ClientFactory(OutboundTlsTrust.defaults(), config -> {
      S3Client client = mock(S3Client.class);
      built.add(client);
      return client;
    }, closeGrace);
  }

  @Test
  void configurationsOfOneStoreWithDifferentSignaturesGetSeparateClientsAndNeitherIsClosed() {
    S3ClientFactory factory = factory(Duration.ofHours(1));
    try {
      S3Client runtime = factory.client(config(1, "bucket-a"));
      S3Client admin = factory.client(config(1, "bucket-b"));

      assertNotSame(runtime, admin);
      assertSame(runtime, factory.client(config(1, "bucket-a")));
      assertSame(admin, factory.client(config(1, "bucket-b")));
      verify(runtime, after(200).never()).close();
      verify(admin, never()).close();
    } finally {
      factory.shutdown();
    }
  }

  @Test
  void invalidatingAStoreClosesAllItsClientsAfterTheGraceAndOnlyThoseOfThatStore() {
    S3ClientFactory factory = factory(Duration.ZERO);
    try {
      S3Client store1a = factory.client(config(1, "bucket-a"));
      S3Client store1b = factory.client(config(1, "bucket-b"));
      S3Client store2 = factory.client(config(2, "bucket-a"));

      factory.invalidate(1);

      verify(store1a, timeout(2_000)).close();
      verify(store1b, timeout(2_000)).close();
      verify(store2, after(200).never()).close();
      assertEquals(Set.of(2L), factory.cachedStoreIds());
    } finally {
      factory.shutdown();
    }
  }

  @Test
  void aRemovedClientStaysOpenForTheGracePeriod() {
    S3ClientFactory factory = factory(Duration.ofHours(1));
    try {
      S3Client client = factory.client(config(1, "bucket-a"));

      factory.invalidate(1);

      verify(client, after(300).never()).close();
    } finally {
      factory.shutdown();
    }
  }

  @Test
  void aHandleRebuildsTheClientAfterInvalidationInsteadOfServingAClosedOne() {
    S3ClientFactory factory = factory(Duration.ZERO);
    try {
      ClientHandle<S3Client> handle = factory.handle(config(1, "bucket-a"));
      S3Client first = handle.get();
      assertSame(first, handle.get());

      factory.invalidate(1);
      verify(first, timeout(2_000)).close();

      S3Client second = handle.get();
      assertNotSame(first, second);
      assertSame(second, handle.get());
    } finally {
      factory.shutdown();
    }
  }

  @Test
  void aLeaseKeepsAnInvalidatedClientOpenUntilItIsReleased() {
    S3ClientFactory factory = factory(Duration.ZERO);
    try {
      ClientHandle<S3Client> handle = factory.handle(config(1, "bucket-a"));
      ClientHandle.Lease<S3Client> lease = handle.lease();
      S3Client leased = lease.client();

      factory.invalidate(1);

      // A response stream that is still being read must not lose its client.
      verify(leased, after(300).never()).close();
      assertNotSame(leased, handle.get());

      lease.close();
      verify(leased, timeout(2_000)).close();
    } finally {
      factory.shutdown();
    }
  }

  @Test
  void shutdownClosesEveryClientImmediately() {
    S3ClientFactory factory = factory(Duration.ofHours(1));
    S3Client one = factory.client(config(1, "bucket-a"));
    S3Client two = factory.client(config(2, "bucket-a"));

    factory.shutdown();

    verify(one).close();
    verify(two).close();
  }

  @Test
  void aStorageWrapperUsesTheCurrentClientOnEveryOperation() {
    S3Client first = mock(S3Client.class);
    S3Client second = mock(S3Client.class);
    AtomicReference<S3Client> current = new AtomicReference<>(first);
    S3BlobStorage storage = new S3BlobStorage(current::get, config(1, "bucket-a"));
    var reference = new BlobReference("bucket-a", "object", "sha", 1);

    storage.delete(reference);
    current.set(second);
    storage.delete(reference);

    verify(first).deleteObject(org.mockito.ArgumentMatchers.any(DeleteObjectRequest.class));
    verify(second).deleteObject(org.mockito.ArgumentMatchers.any(DeleteObjectRequest.class));
  }

  private static S3BlobStoreConfig config(long id, String bucket) {
    return S3BlobStoreConfig.of(id, "store-" + id, "http://localhost:9000", "us-east-1", bucket, "", Map.of());
  }
}
