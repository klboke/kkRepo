package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanTaskPriorities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Schedules bounded vulnerability-database rematches and first-scan recovery behind a shared
 * database cursor.
 *
 * <p>The cursor identity includes both profile and scanner snapshot. A new database snapshot
 * starts at the beginning, while repeated passes over one snapshot continue after the last asset
 * even if earlier rematch tasks have not completed yet. The row lock makes the cursor and task
 * writes one multi-replica transaction.
 */
@Service
public class SecurityScannerSnapshotRematchService {
  static final String CURSOR_PREFIX = "security_scan_snapshot:";

  private final SecurityScanDao scans;
  private final AssetDao assets;
  private final MaintenanceCursorDao cursors;
  private final SecurityScanningProperties properties;

  public SecurityScannerSnapshotRematchService(
      SecurityScanDao scans,
      AssetDao assets,
      MaintenanceCursorDao cursors,
      SecurityScanningProperties properties) {
    this.scans = scans;
    this.assets = assets;
    this.cursors = cursors;
    this.properties = properties;
  }

  @Transactional
  public int reconcileProfile(ScanProfile profile, ScannerSnapshot snapshot) {
    String cursorName = cursorName(profile.id(), snapshot.id());
    cursors.ensureCursor(cursorName);
    OptionalLong locked = cursors.tryLockLastSeenId(cursorName);
    if (locked.isEmpty()) {
      return 0;
    }

    int batchSize = properties.getWorker().getSnapshotRematchBatchSize();
    int maxBatches = properties.getWorker().getSnapshotRematchMaxBatches();
    long cursor = locked.getAsLong();
    int scheduled = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      List<AssetSecurityState> states = scans.listAssetStatesNeedingSnapshot(
          profile.id(), snapshot.id(), cursor, batchSize);
      if (states.isEmpty()) {
        // Wrap on the following invocation. This avoids selecting the still-pending first page
        // again in the same bounded pass while ensuring newly eligible lower IDs are revisited.
        updateCursor(cursorName, 0);
        return scheduled;
      }
      for (AssetSecurityState state : states) {
        cursor = state.assetId();
        if (schedule(state, profile, snapshot)) {
          scheduled++;
        }
      }
      if (states.size() < batchSize) {
        break;
      }
    }
    updateCursor(cursorName, cursor);
    return scheduled;
  }

  private void updateCursor(String cursorName, long cursor) {
    if (cursors.updateLastSeenId(cursorName, cursor) != 1) {
      throw new IllegalStateException("Security snapshot rematch cursor disappeared while locked");
    }
  }

  private boolean schedule(
      AssetSecurityState state, ScanProfile profile, ScannerSnapshot snapshot) {
    if (state.latestScanRunId() == null) {
      return scans.requeueCandidateAfterObservationFailure(
          state.assetId(), state.profileId(), state.contentGeneration(), Instant.now());
    }
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(state.assetId()).orElse(null);
    if (content == null || content.blob() == null || content.blob().sha256() == null) {
      return false;
    }
    var candidate = scans.findCandidate(state.assetId()).orElse(null);
    if (candidate == null
        || candidate.contentGeneration() != state.contentGeneration()
        || !java.util.Objects.equals(candidate.assetBlobId(), content.blob().id())) {
      return false;
    }
    Instant now = Instant.now();
    scans.markAssetStateStale(
        state.assetId(), state.profileId(), state.latestScanRunId(), now);
    String requestUuid = snapshotRequestUuid(state, profile, snapshot);
    long taskId = scans.createTask(new TaskDraft(
        content.asset().repositoryId(),
        state.assetId(),
        subjectKind(content.asset().format(), content.asset().kind()),
        "sha256:" + content.blob().sha256(),
        state.contentGeneration(),
        profile.id(),
        profile.revision(),
        snapshot.id(),
        ScanStage.MATCH_ONLY,
        RequestReason.VULNERABILITY_DB_CHANGED,
        ScanTaskPriorities.VULNERABILITY_DATABASE,
        properties.getWorker().getMaxAttempts(),
        "security-scan-worker",
        requestUuid,
        "snapshot:" + requestUuid,
        now));
    // A task can have terminalized while no matching scanner replica was available, or an older
    // build can have succeeded it with another revision. The deterministic dedupe key must not
    // make that terminal row a permanent barrier when this snapshot is still required.
    scans.reactivateSnapshotTask(
        taskId, snapshot.id(), now, "security-scan-worker");
    return true;
  }

  static String cursorName(long profileId, long snapshotId) {
    return CURSOR_PREFIX + profileId + ":" + snapshotId;
  }

  private static String snapshotRequestUuid(
      AssetSecurityState state, ScanProfile profile, ScannerSnapshot snapshot) {
    String identity = String.join(
        "\0",
        "snapshot",
        Long.toString(state.assetId()),
        Long.toString(state.contentGeneration()),
        Long.toString(profile.id()),
        Long.toString(profile.revision()),
        Long.toString(snapshot.id()));
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static SubjectKind subjectKind(RepositoryFormat format, String kind) {
    return format == RepositoryFormat.DOCKER
        && kind != null
        && kind.toLowerCase(java.util.Locale.ROOT).contains("manifest")
        ? SubjectKind.OCI_MANIFEST
        : SubjectKind.ASSET_BLOB;
  }
}
