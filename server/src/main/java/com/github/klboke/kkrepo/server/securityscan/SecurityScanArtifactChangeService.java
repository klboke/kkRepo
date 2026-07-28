package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds the feature-neutral artifact change stream into the scan candidate projection.
 *
 * <p>The shared cursor row is locked for the complete transaction. Concurrent replicas therefore
 * either process the next batch atomically or skip it without owning in-memory coordination state.
 */
@Service
public class SecurityScanArtifactChangeService {
  static final String CURSOR_PREFIX = "artifact_change:";
  static final String CURSOR_NAME = CURSOR_PREFIX + "security_scan";

  private final ArtifactChangeDao artifactChanges;
  private final MaintenanceCursorDao cursors;
  private final SecurityScanDao scans;
  private final SecurityScanningProperties properties;

  public SecurityScanArtifactChangeService(
      ArtifactChangeDao artifactChanges,
      MaintenanceCursorDao cursors,
      SecurityScanDao scans,
      SecurityScanningProperties properties) {
    this.artifactChanges = artifactChanges;
    this.cursors = cursors;
    this.scans = scans;
    this.properties = properties;
  }

  @Transactional
  public int processBatch() {
    cursors.ensureCursor(CURSOR_NAME);
    OptionalLong lastSeen = cursors.tryLockLastSeenId(CURSOR_NAME);
    if (lastSeen.isEmpty()) {
      return 0;
    }
    List<ArtifactChangeDao.ArtifactChange> events = artifactChanges.listAfter(
        lastSeen.getAsLong(), properties.getWorker().getArtifactChangeBatchSize());
    if (events.isEmpty()) {
      cleanupConsumedEvents();
      return 0;
    }

    Set<Long> changedAssets = new LinkedHashSet<>();
    for (ArtifactChangeDao.ArtifactChange event : events) {
      changedAssets.add(event.assetId());
    }
    for (long assetId : changedAssets) {
      scans.recordArtifactContentChange(assetId);
    }

    long consumedThrough = events.getLast().id();
    if (cursors.updateLastSeenId(CURSOR_NAME, consumedThrough) != 1) {
      throw new IllegalStateException("Artifact change cursor disappeared while it was locked");
    }
    cleanupConsumedEvents();
    return events.size();
  }

  private void cleanupConsumedEvents() {
    cursors.minimumLastSeenId(CURSOR_PREFIX).ifPresent(watermark ->
        artifactChanges.deleteThrough(
            watermark, properties.getWorker().getArtifactChangeCleanupBatchSize()));
  }
}
