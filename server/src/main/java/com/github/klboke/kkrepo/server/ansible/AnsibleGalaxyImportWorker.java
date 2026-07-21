package com.github.klboke.kkrepo.server.ansible;

import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every replica may recover expired import tasks; database claim/fencing chooses one owner. */
@Component
final class AnsibleGalaxyImportWorker {
  private final AnsibleGalaxyRegistryDao registry;
  private final AnsibleGalaxyService service;

  AnsibleGalaxyImportWorker(
      AnsibleGalaxyRegistryDao registry, AnsibleGalaxyService service) {
    this.registry = registry;
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${kkrepo.ansible.import-recovery-delay-ms:5000}")
  void recover() {
    for (AnsibleGalaxyRegistryDao.ImportTask task
        : registry.listClaimableTasks(Instant.now(), 16)) {
      service.recoverTask(task);
    }
  }
}
