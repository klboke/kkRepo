package com.github.klboke.kkrepo.server.statistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao.BlobStoreUsage;
import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao.RepositoryUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StorageStatisticsServiceTest {
  @Test
  void filtersEveryRequestFillsEmptyEntitiesAndKeepsIndependentSnapshots() {
    StorageStatisticsDao dao = mock(StorageStatisticsDao.class);
    when(dao.blobStoreUsage()).thenReturn(Map.of(1L, new BlobStoreUsage(3, 100, 1, 20)));
    when(dao.repositoryUsage()).thenReturn(Map.of(10L, new RepositoryUsage(7, 200, 0),
        11L, new RepositoryUsage(9, 400, 0)));
    var service = new StorageStatisticsService(dao);
    assertTrue(service.assets(List.of()).usage().isEmpty());
    assertTrue(service.blobs(List.of()).usage().isEmpty());
    verifyNoInteractions(dao);
    var first = service.assets(List.of(10L, 12L));
    assertEquals(Map.of(10L, new RepositoryUsage(7, 200, 0), 12L, RepositoryUsage.EMPTY), first.usage());
    var changedPermission = service.assets(List.of(11L));
    assertEquals(Map.of(11L, new RepositoryUsage(9, 400, 0)), changedPermission.usage());
    assertEquals(first.calculatedAt(), changedPermission.calculatedAt());
    assertEquals(30, first.maxAgeSeconds());
    assertEquals(Map.of(1L, new BlobStoreUsage(3, 100, 1, 20), 2L, BlobStoreUsage.EMPTY),
        service.blobs(List.of(1L, 2L)).usage());
    verify(dao, times(1)).repositoryUsage();
    verify(dao, times(1)).blobStoreUsage();
  }

  @Test
  void expiryAndNewReplicaRebuildFromDatabaseAndFailedLoadsRetry() {
    StorageStatisticsDao dao = mock(StorageStatisticsDao.class);
    when(dao.blobStoreUsage()).thenReturn(Map.of(1L, new BlobStoreUsage(1, 10, 0, 0)))
        .thenReturn(Map.of(1L, new BlobStoreUsage(2, 20, 0, 0)));
    var expired = new StorageStatisticsService(dao, Duration.ofNanos(1));
    assertEquals(1, expired.blobs(List.of(1L)).usage().get(1L).blobCount());
    assertEquals(2, expired.blobs(List.of(1L)).usage().get(1L).blobCount());
    assertEquals(2, new StorageStatisticsService(dao).blobs(List.of(1L)).usage().get(1L).blobCount());
    when(dao.repositoryUsage()).thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(Map.of());
    var service = new StorageStatisticsService(dao);
    assertThrows(IllegalStateException.class, () -> service.assets(List.of(1L)));
    assertEquals(RepositoryUsage.EMPTY, service.assets(List.of(1L)).usage().get(1L));
    verify(dao, times(2)).repositoryUsage();
  }

  @Test
  void concurrentColdRequestsShareOneAggregate() throws Exception {
    StorageStatisticsDao dao = mock(StorageStatisticsDao.class);
    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(dao.repositoryUsage()).thenAnswer(call -> {
      loading.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return Map.of(1L, new RepositoryUsage(1_000_000, 1024_000_000L, 0));
    });
    var service = new StorageStatisticsService(dao);
    try (var pool = Executors.newFixedThreadPool(16)) {
      var requests = java.util.stream.IntStream.range(0, 16)
          .mapToObj(i -> pool.submit(() -> service.assets(List.of(1L)))).toList();
      assertTrue(loading.await(5, TimeUnit.SECONDS));
      release.countDown();
      for (var request : requests) assertEquals(1_000_000,
          request.get(5, TimeUnit.SECONDS).usage().get(1L).assetCount());
    } finally {
      release.countDown();
    }
    verify(dao, times(1)).repositoryUsage();
  }
}
