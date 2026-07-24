package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.BackfillJob;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityScanBackfillCoordinator {
  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;

  public SecurityScanBackfillCoordinator(
      SecurityScanDao scans, SecurityScanningProperties properties) {
    this.scans = scans;
    this.properties = properties;
  }

  @Transactional
  public List<BackfillJob> claim(String workerId) {
    Instant now = Instant.now();
    return scans.claimBackfillJobs(
        workerId,
        now,
        now.plusSeconds(properties.getWorker().getLeaseSeconds()),
        1);
  }
}
