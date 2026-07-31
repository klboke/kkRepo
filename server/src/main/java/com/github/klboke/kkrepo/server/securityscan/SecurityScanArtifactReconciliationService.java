package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ReconciliationPage;
import java.time.Instant;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Continuously reconciles the candidate projection with current asset/blob database truth.
 *
 * <p>The outbox is the normal low-cost path. This bounded safety net is deliberately independent
 * of application-version event emission so an older writer in a rolling deployment cannot leave a
 * replaced asset associated with a stale allow decision.
 */
@Service
public class SecurityScanArtifactReconciliationService {
  static final String CURSOR_NAME = "artifact_reconcile:security_scan";

  private final MaintenanceCursorDao cursors;
  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;

  public SecurityScanArtifactReconciliationService(
      MaintenanceCursorDao cursors,
      SecurityScanDao scans,
      SecurityScanningProperties properties) {
    this.cursors = cursors;
    this.scans = scans;
    this.properties = properties;
  }

  @Transactional
  public ReconciliationPage processBatch() {
    cursors.ensureCursor(CURSOR_NAME);
    OptionalLong lastSeen = cursors.tryLockLastSeenId(CURSOR_NAME);
    if (lastSeen.isEmpty()) {
      return new ReconciliationPage(0, 0, 0, 0, false);
    }
    int batchSize = properties.getWorker().getArtifactReconcileBatchSize();
    Instant recentSince =
        Instant.now().minus(properties.getWorker().getArtifactReconcileRecentWindow());
    ReconciliationPage page = scans.reconcileArtifactChanges(
        lastSeen.getAsLong(), recentSince, batchSize, batchSize);
    if (cursors.updateLastSeenId(CURSOR_NAME, page.nextAssetId()) != 1) {
      throw new IllegalStateException(
          "Artifact reconciliation cursor disappeared while it was locked");
    }
    return page;
  }
}
