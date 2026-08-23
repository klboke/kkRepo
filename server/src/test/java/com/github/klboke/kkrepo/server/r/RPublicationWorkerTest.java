package com.github.klboke.kkrepo.server.r;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RRegistryDao;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RPublicationWorkerTest {
  private final RRegistryDao registry = mock(RRegistryDao.class);
  private final RepositoryRuntimeRegistry runtimes = mock(RepositoryRuntimeRegistry.class);
  private final RService service = mock(RService.class);

  @Test
  void disabledWorkerDoesNotReadQueue() {
    worker(false).publishPending();
    verify(registry, never()).listPendingSuites(any(), any(), any(), anyInt());
  }

  @Test
  void publishesEligibleRepositoriesAndSkipsUnavailableOnes() {
    when(registry.listPendingSuites(any(), any(), any(), eq(16))).thenReturn(List.of(
        suite(1L, "hosted"), suite(2L, "offline"),
        suite(3L, "wrong-format"), suite(4L, "missing")));
    RepositoryRuntime hosted = runtime(1L, RepositoryFormat.R, true);
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(hosted));
    when(runtimes.resolveById(2L)).thenReturn(Optional.of(
        runtime(2L, RepositoryFormat.R, false)));
    when(runtimes.resolveById(3L)).thenReturn(Optional.of(
        runtime(3L, RepositoryFormat.RAW, true)));
    when(runtimes.resolveById(4L)).thenReturn(Optional.empty());

    worker(true).publishPending();

    verify(service).publishPendingIfAvailable(hosted, "hosted");
    verify(service, never()).publishPendingIfAvailable(
        org.mockito.ArgumentMatchers.argThat(runtime -> runtime.id() != 1L), any());
  }

  @Test
  void containsPublicationFailureForTheNextDurableRetry() {
    RRegistryDao.SuiteState failing = suite(1L, "src/contrib");
    RepositoryRuntime runtime = runtime(1L, RepositoryFormat.R, true);
    when(registry.listPendingSuites(any(), any(), any(), anyInt()))
        .thenReturn(List.of(failing));
    when(runtimes.resolveById(1L)).thenReturn(Optional.of(runtime));
    doThrow(new IllegalStateException("publish failed"))
        .when(service).publishPendingIfAvailable(runtime, "src/contrib");

    worker(true).publishPending();

    verify(service).publishPendingIfAvailable(runtime, "src/contrib");
  }

  private RPublicationWorker worker(boolean enabled) {
    return new RPublicationWorker(registry, runtimes, service, enabled, 16, -1, 10, -1);
  }

  private static RRegistryDao.SuiteState suite(long repositoryId, String namespace) {
    return new RRegistryDao.SuiteState(
        repositoryId, namespace, 2L, Instant.EPOCH, 1L, 1,
        null, null, null, Instant.EPOCH);
  }

  private static RepositoryRuntime runtime(
      long id, RepositoryFormat format, boolean online) {
    return new RepositoryRuntime(
        id, "r-" + id, format, RepositoryType.HOSTED,
        format == RepositoryFormat.R ? "r-hosted" : "raw-hosted", online,
        1L, "ALLOW", null, null, true, null, 60, 60, true, null, List.of());
  }
}
