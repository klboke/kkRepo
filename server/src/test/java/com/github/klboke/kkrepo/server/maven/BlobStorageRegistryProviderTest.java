package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreDescriptor;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreProvider;
import com.github.klboke.kkrepo.core.blobstore.BlobStoreTypes;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Dispatch behaviour of the pluggable provider registry, independent of any concrete backend. */
class BlobStorageRegistryProviderTest {

  @Test
  void dispatchesEachRowToTheProviderThatOwnsItsType() {
    RecordingProvider s3 = new RecordingProvider(BlobStoreTypes.S3);
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    RecordingProvider file = new RecordingProvider(BlobStoreTypes.FILE);
    BlobStoreDao dao = mock(BlobStoreDao.class);
    BlobStorageRegistry registry = registry(dao, List.of(s3, azure, file));

    registry.forRecord(record(1, "s3-store", BlobStoreTypes.S3));
    registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE));
    registry.forRecord(record(3, "file-store", BlobStoreTypes.FILE));

    assertEquals(1, s3.created.get());
    assertEquals(1, azure.created.get());
    assertEquals(1, file.created.get());
  }

  @Test
  void reusesTheWrapperWhileTheProviderSignatureIsUnchanged() {
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    BlobStorageRegistry registry = registry(mock(BlobStoreDao.class), List.of(azure));

    BlobStorage first = registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE));

    assertSame(first, registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE)));
    assertEquals(1, azure.created.get());
  }

  @Test
  void aChangedConfigurationRebuildsTheWrapper() {
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    BlobStorageRegistry registry = registry(mock(BlobStoreDao.class), List.of(azure));
    BlobStorage first = registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE));

    azure.signature = "changed";

    assertNotSame(first, registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE)));
  }

  @Test
  void retypingAStoreRebuildsItEvenWhenProviderSignaturesCollide() {
    RecordingProvider s3 = new RecordingProvider(BlobStoreTypes.S3);
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    // Both providers report the same signature, so only the provider type can tell them apart.
    s3.signature = "same";
    azure.signature = "same";
    BlobStorageRegistry registry = registry(mock(BlobStoreDao.class), List.of(s3, azure));

    BlobStorage asS3 = registry.forRecord(record(5, "store", BlobStoreTypes.S3));
    BlobStorage asAzure = registry.forRecord(record(5, "store", BlobStoreTypes.AZURE));

    assertNotSame(asS3, asAzure);
  }

  @Test
  void invalidateReachesEveryRegisteredProvider() {
    RecordingProvider s3 = new RecordingProvider(BlobStoreTypes.S3);
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    BlobStorageRegistry registry = registry(mock(BlobStoreDao.class), List.of(s3, azure));

    registry.invalidate(42);

    assertTrue(s3.invalidated.contains(42L));
    assertTrue(azure.invalidated.contains(42L));
  }

  @Test
  void deletedStoresReleaseClientsHeldByAnyProvider() {
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    azure.stale = Set.of(9L);
    BlobStoreDao dao = mock(BlobStoreDao.class);
    when(dao.list()).thenReturn(List.of());
    when(dao.findById(9L)).thenReturn(Optional.empty());
    BlobStorageRegistry registry = registry(dao, List.of(azure));

    registry.refreshAll();

    assertTrue(azure.invalidated.contains(9L));
  }

  @Test
  void aRowWithNoMatchingProviderFailsWithAUsefulMessage() {
    BlobStorageRegistry registry = registry(mock(BlobStoreDao.class), List.of());

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE)));

    assertTrue(error.getMessage().contains("azure-store"), error.getMessage());
  }

  @Test
  void s3ConfigurationIsUnavailableWithoutTheS3Provider() {
    BlobStoreDao dao = mock(BlobStoreDao.class);
    when(dao.findById(2L)).thenReturn(Optional.of(record(2, "azure-store", BlobStoreTypes.AZURE)));
    BlobStorageRegistry registry = registry(dao, List.of(new RecordingProvider(BlobStoreTypes.AZURE)));

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> registry.configFor(2));

    assertTrue(error.getMessage().contains("S3"), error.getMessage());
  }

  @Test
  void closingTheRegistryClosesEveryCachedWrapper() {
    RecordingProvider azure = new RecordingProvider(BlobStoreTypes.AZURE);
    BlobStorageRegistry registry = registry(mock(BlobStoreDao.class), List.of(azure));
    BlobStorage storage = registry.forRecord(record(2, "azure-store", BlobStoreTypes.AZURE));

    registry.shutdown();

    // The cache removal listener may run asynchronously.
    verify(storage, timeout(5000)).close();
  }

  private static BlobStorageRegistry registry(BlobStoreDao dao, List<BlobStoreProvider> providers) {
    return new BlobStorageRegistry(dao, providers, false, null, null);
  }

  private static BlobStoreRecord record(long id, String name, String type) {
    return new BlobStoreRecord(id, name, type, "https://example.invalid", "", "bucket", "", Map.of());
  }

  private static final class RecordingProvider implements BlobStoreProvider {
    private final String type;
    private final AtomicInteger created = new AtomicInteger();
    private final List<Long> invalidated = new ArrayList<>();
    private String signature = "signature";
    private Set<Long> stale = Set.of();

    private RecordingProvider(String type) {
      this.type = type;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public boolean supports(BlobStoreDescriptor descriptor) {
      return type.equals(descriptor.normalizedType());
    }

    @Override
    public String signature(BlobStoreDescriptor descriptor) {
      return signature;
    }

    @Override
    public BlobStorage create(BlobStoreDescriptor descriptor) {
      created.incrementAndGet();
      return mock(BlobStorage.class);
    }

    @Override
    public Set<Long> cachedStoreIdsMissingFrom(Set<Long> activeStoreIds) {
      return stale;
    }

    @Override
    public void invalidate(long storeId) {
      invalidated.add(storeId);
    }
  }
}
