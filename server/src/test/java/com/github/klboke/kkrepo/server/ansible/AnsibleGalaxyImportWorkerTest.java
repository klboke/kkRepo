package com.github.klboke.kkrepo.server.ansible;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AnsibleGalaxyImportWorkerTest {

  @Test
  void atomicallyClaimsAndProcessesEveryTaskInTheAvailableBatch() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleGalaxyService service = mock(AnsibleGalaxyService.class);
    AnsibleGalaxyRegistryDao.ImportTask first = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    AnsibleGalaxyRegistryDao.ImportTask second = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    when(registry.claimTasks(
        anyString(), any(Instant.class), any(Instant.class), eq(16)))
        .thenReturn(List.of(first, second));

    new AnsibleGalaxyImportWorker(registry, service).recover();

    verify(service).processClaimedTask(first);
    verify(service).processClaimedTask(second);
  }

  @Test
  void doesNotClaimBeyondAvailableCapacity() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleGalaxyService service = mock(AnsibleGalaxyService.class);
    AnsibleGalaxyRegistryDao.ImportTask first = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    AnsibleGalaxyRegistryDao.ImportTask second = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    List<Runnable> queued = new ArrayList<>();
    Executor executor = queued::add;
    when(registry.claimTasks(anyString(), any(), any(), eq(2)))
        .thenReturn(List.of(first, second));
    AnsibleGalaxyImportWorker worker = new AnsibleGalaxyImportWorker(
        registry, service, 2, executor, "worker");

    worker.recover();
    worker.recover();

    Assertions.assertEquals(2, worker.inFlightCount());
    verify(registry).claimTasks(anyString(), any(), any(), eq(2));
    queued.removeFirst().run();
    when(registry.claimTasks(anyString(), any(), any(), eq(1))).thenReturn(List.of());
    worker.recover();
    verify(registry).claimTasks(anyString(), any(), any(), eq(1));
    verify(service).processClaimedTask(first);
    verify(service, never()).processClaimedTask(second);
  }

  @Test
  void rejectedSubmissionRestoresCapacity() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleGalaxyRegistryDao.ImportTask task = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    when(registry.claimTasks(anyString(), any(), any(), eq(1))).thenReturn(List.of(task));
    AnsibleGalaxyImportWorker worker = new AnsibleGalaxyImportWorker(
        registry,
        mock(AnsibleGalaxyService.class),
        1,
        ignored -> {
          throw new RejectedExecutionException("busy");
        },
        "worker");

    Assertions.assertThrows(RejectedExecutionException.class, worker::recover);

    Assertions.assertEquals(0, worker.inFlightCount());
  }

  @Test
  void closesTheExecutorOwnedByTheWorker() {
    var executor = Executors.newSingleThreadExecutor();
    AnsibleGalaxyImportWorker worker = new AnsibleGalaxyImportWorker(
        mock(AnsibleGalaxyRegistryDao.class),
        mock(AnsibleGalaxyService.class),
        1,
        executor,
        "worker");

    worker.close();

    Assertions.assertTrue(executor.isShutdown());
  }

  @Test
  void productionConstructorBoundsInvalidConcurrencyAndOwnsItsExecutor() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    when(registry.claimTasks(anyString(), any(), any(), eq(1))).thenReturn(List.of());
    AnsibleGalaxyImportWorker worker = new AnsibleGalaxyImportWorker(
        registry, mock(AnsibleGalaxyService.class), 0);
    try {
      worker.recover();
      verify(registry).claimTasks(anyString(), any(), any(), eq(1));
    } finally {
      worker.close();
    }
  }

  @Test
  void capsConfiguredConcurrencyAtThePerReplicaSafetyLimit() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    when(registry.claimTasks(anyString(), any(), any(), eq(64))).thenReturn(List.of());
    AnsibleGalaxyImportWorker worker = new AnsibleGalaxyImportWorker(
        registry, mock(AnsibleGalaxyService.class), Integer.MAX_VALUE);
    try {
      worker.recover();
      verify(registry).claimTasks(anyString(), any(), any(), eq(64));
    } finally {
      worker.close();
    }
  }
}
