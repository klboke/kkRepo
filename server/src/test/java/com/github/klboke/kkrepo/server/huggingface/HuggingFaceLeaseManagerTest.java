package com.github.klboke.kkrepo.server.huggingface;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.HuggingFaceRegistryDao.FetchLease;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HuggingFaceLeaseManagerTest {
  @Test
  void checkpointsUseTheRenewalThreadInsteadOfSynchronousDatabaseHeartbeats() {
    HuggingFaceRegistryDao registry = mock(HuggingFaceRegistryDao.class);
    when(registry.tryAcquireLease(eq(7L), eq("file"), any(), any()))
        .thenAnswer(invocation -> Optional.of(new FetchLease(
            7L, "file", invocation.getArgument(2), 11L, Instant.now().plusSeconds(300),
            Instant.now())));

    HuggingFaceLeaseManager.Lease lease = new HuggingFaceLeaseManager(registry)
        .acquireUnlessCompleted(7L, "file", () -> false)
        .orElseThrow();
    lease.assertHeld();
    lease.assertHeld();

    verify(registry, never()).renewLease(eq(7L), eq("file"), any(), eq(11L), any());
    lease.close();
    verify(registry).releaseLease(eq(7L), eq("file"), any(), eq(11L));
  }
}
