package com.github.klboke.kkrepo.server.swift;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SwiftPublishLeaseManagerTest {

  @Test
  void duplicatePublisherStopsWaitingAsSoonAsTheWinningReleaseIsVisible() {
    SwiftRegistryDao registry = mock(SwiftRegistryDao.class);
    when(registry.tryAcquireLease(anyString(), anyString(), any()))
        .thenReturn(Optional.empty());
    SwiftPublishLeaseManager leases = new SwiftPublishLeaseManager(registry);

    assertThrows(
        SwiftExceptions.Conflict.class,
        () -> leases.acquire("swift:1:acme:demo:1.0.0", () -> true));

    verify(registry).tryAcquireLease(anyString(), anyString(), any());
  }
}
