package com.github.klboke.kkrepo.server.ansible;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnsibleGalaxyImportWorkerTest {

  @Test
  void recoversEveryClaimableTaskThroughTheFencedServiceLifecycle() {
    AnsibleGalaxyRegistryDao registry = mock(AnsibleGalaxyRegistryDao.class);
    AnsibleGalaxyService service = mock(AnsibleGalaxyService.class);
    AnsibleGalaxyRegistryDao.ImportTask first = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    AnsibleGalaxyRegistryDao.ImportTask second = mock(AnsibleGalaxyRegistryDao.ImportTask.class);
    when(registry.listClaimableTasks(any(Instant.class), eq(16)))
        .thenReturn(List.of(first, second));

    new AnsibleGalaxyImportWorker(registry, service).recover();

    verify(service).recoverTask(first);
    verify(service).recoverTask(second);
  }
}
