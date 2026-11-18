package com.github.klboke.kkrepo.server.ohpm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OhpmFetchCoordinatorTest {
  @Test
  void fencesPublicationAndReleasesOnlyItsOwnLeaseAfterFailure() {
    var dao = mock(OhpmRepositoryDao.class);
    when(dao.acquire(anyString(), anyString(), any(), any())).thenAnswer(call -> Optional.of(
        new OhpmRepositoryDao.Lease(call.getArgument(0), call.getArgument(1), 7, call.getArgument(3))));
    when(dao.renew(anyString(), anyString(), eq(7L), any(), any())).thenReturn(true, false);
    var coordinator = new OhpmFetchCoordinator(dao);
    String owner;
    try (var lease = coordinator.acquire("key")) {
      owner = lease.owner();
      lease.assertHeld();
      assertEquals(503, assertThrows(OhpmException.class, lease::assertHeld).status());
    }
    verify(dao).release("key", owner, 7);
  }

  @Test
  void interruptedWaiterFailsWithoutFetchingOrReleasingAnotherOwnersLease() {
    var dao = mock(OhpmRepositoryDao.class);
    var coordinator = new OhpmFetchCoordinator(dao);
    Thread.currentThread().interrupt();
    try {
      assertEquals(503, assertThrows(OhpmException.class, () -> coordinator.acquire("busy")).status());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    verify(dao, never()).release(anyString(), anyString(), anyLong());
  }
}
