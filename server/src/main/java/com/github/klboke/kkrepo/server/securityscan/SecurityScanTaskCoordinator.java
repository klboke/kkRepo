package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transaction used before any blob or scanner I/O. */
@Service
public class SecurityScanTaskCoordinator {
  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;

  public SecurityScanTaskCoordinator(
      SecurityScanDao scans, SecurityScanningProperties properties) {
    this.scans = scans;
    this.properties = properties;
  }

  @Transactional
  public List<ScanTask> claim(String workerId) {
    Instant now = Instant.now();
    return scans.claimTasks(
        workerId,
        now,
        now.plusSeconds(properties.getWorker().getLeaseSeconds()),
        properties.getWorker().getBatchSize());
  }
}
