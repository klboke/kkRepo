package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScannerSnapshot;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles durable asset state with the shared scanner snapshot. Duplicate work across replicas
 * is collapsed by the task dedupe key; no JVM-local observation is correctness-critical.
 */
@Component
@ConditionalOnProperty(
    prefix = "kkrepo.security-scanning", name = "enabled", havingValue = "true")
public class SecurityScannerSnapshotWatcher {
  private static final Logger log =
      LoggerFactory.getLogger(SecurityScannerSnapshotWatcher.class);

  private final SecurityScanDao scans;
  private final AssetDao assets;
  private final SecurityScannerSnapshotService snapshots;
  private final SecurityScanningProperties properties;
  private final SecurityScanAuditService audit;

  public SecurityScannerSnapshotWatcher(
      SecurityScanDao scans,
      AssetDao assets,
      SecurityScannerSnapshotService snapshots,
      SecurityScanningProperties properties,
      SecurityScanAuditService audit) {
    this.scans = scans;
    this.assets = assets;
    this.snapshots = snapshots;
    this.properties = properties;
    this.audit = audit;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.security-scanning.snapshot-watch-delay:60s}",
      initialDelayString = "${kkrepo.security-scanning.snapshot-watch-initial-delay:15s}")
  public void reconcile() {
    ScannerSnapshot previous = scans.latestScannerSnapshot().orElse(null);
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
        scheduled += reconcileProfile(profile, current);
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

  private int reconcileProfile(ScanProfile profile, ScannerSnapshot snapshot) {
    int batchSize = properties.getWorker().getSnapshotRematchBatchSize();
    int maxBatches = properties.getWorker().getSnapshotRematchMaxBatches();
    long cursor = 0;
    int scheduled = 0;
    for (int batch = 0; batch < maxBatches; batch++) {
      List<AssetSecurityState> states = scans.listAssetStatesNeedingSnapshot(
          profile.id(), snapshot.id(), cursor, batchSize);
      if (states.isEmpty()) break;
      for (AssetSecurityState state : states) {
        cursor = state.assetId();
        if (schedule(state, profile, snapshot)) scheduled++;
      }
      if (states.size() < batchSize) break;
    }
    return scheduled;
  }

  private boolean schedule(
      AssetSecurityState state, ScanProfile profile, ScannerSnapshot snapshot) {
    if (state.latestScanRunId() == null) return false;
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(state.assetId()).orElse(null);
    if (content == null || content.blob() == null || content.blob().sha256() == null) {
      return false;
    }
    var candidate = scans.findCandidate(state.assetId()).orElse(null);
    if (candidate == null || candidate.contentGeneration() != state.contentGeneration()
        || !java.util.Objects.equals(candidate.assetBlobId(), content.blob().id())) {
      return false;
    }
    Instant now = Instant.now();
    scans.markAssetStateStale(
        state.assetId(), state.profileId(), state.latestScanRunId(), now);
    String requestUuid = snapshotRequestUuid(state, profile, snapshot);
    scans.createTask(new TaskDraft(
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
        25,
        properties.getWorker().getMaxAttempts(),
        "security-scan-worker",
        requestUuid,
        "snapshot:" + requestUuid,
        now));
    return true;
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

  private static boolean matches(ScanProfile profile, ScannerSnapshot snapshot) {
    return profile.matcherEngine() != null
        && snapshot.engineName() != null
        && profile.matcherEngine().equalsIgnoreCase(snapshot.engineName());
  }

  private static SubjectKind subjectKind(RepositoryFormat format, String kind) {
    return format == RepositoryFormat.DOCKER
        && kind != null
        && kind.toLowerCase(java.util.Locale.ROOT).contains("manifest")
        ? SubjectKind.OCI_MANIFEST : SubjectKind.ASSET_BLOB;
  }
}
