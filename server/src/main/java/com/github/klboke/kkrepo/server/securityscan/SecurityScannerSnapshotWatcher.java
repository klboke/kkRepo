package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Observes the shared scanner snapshot and delegates bounded rematches to a row-locked durable
 * cursor service; no JVM-local observation is correctness-critical.
 */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScannerSnapshotWatcher {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScannerSnapshotWatcher.class);

  private final SecurityScanDao scans;
  private final SecurityScannerSnapshotService snapshots;
  private final SecurityScannerSnapshotRematchService rematches;
  private final SecurityScanAuditService audit;

  public SecurityScannerSnapshotWatcher(
      SecurityScanDao scans,
      SecurityScannerSnapshotService snapshots,
      SecurityScannerSnapshotRematchService rematches,
      SecurityScanAuditService audit) {
    this.scans = scans;
    this.snapshots = snapshots;
    this.rematches = rematches;
    this.audit = audit;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.snapshot-watch-delay:60s}",
      initialDelayString = "${kkrepo.security-scanning.snapshot-watch-initial-delay:15s}")
  public void reconcile() {
    ScannerSnapshot previous = scans.latestReadyScannerSnapshot(
        SecurityScannerStatus.maximumProvenanceTimestamp(Instant.now())).orElse(null);
    ScannerSnapshot current;
    try {
      current = snapshots.readySnapshot();
    } catch (ScannerAdapterException e) {
      log.warn("Security scanner snapshot observation failed: {}", e.code());
      return;
    } catch (RuntimeException e) {
      log.warn("Security scanner snapshot observation failed", e);
      return;
    }
    int scheduled = 0;
    for (ScanProfile profile : scans.listProfiles()) {
      if (profile.enabled() && matches(profile, current)) {
        scheduled += rematches.reconcileProfile(profile, current);
      }
    }
    if (previous != null
        && !previous.snapshotFingerprint().equals(current.snapshotFingerprint())) {
      audit.recordSystem(
          "SCANNER_SNAPSHOT_CHANGED",
          null,
          Map.of(
              "previousSnapshotId", previous.id(),
              "snapshotId", current.id(),
              "scheduledMatchTasks", scheduled));
    }
  }

  private static boolean matches(ScanProfile profile, ScannerSnapshot snapshot) {
    return profile.matcherEngine() != null
        && snapshot.engineName() != null
        && profile.matcherEngine().equalsIgnoreCase(snapshot.engineName());
  }

}
