package com.github.klboke.kkrepo.server.maven;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MavenSnapshotDao;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Bounded, resumable projection activated by the first snapshot policy. New writes are indexed synchronously on every replica. */
@Component
public class MavenSnapshotBackfillWorker {
  public static final String CURSOR = "maven_snapshot_backfill_v1";
  public static final String ACTIVATED = "maven_snapshot_activated_v1";
  public static final String READY = "maven_snapshot_ready_v1";
  private final MaintenanceCursorDao cursors;
  private final MavenSnapshotDao snapshots;
  private final AssetDao assets;
  private final MavenSnapshotAssetObserver observer;
  private final TransactionTemplate transactions;

  public MavenSnapshotBackfillWorker(MaintenanceCursorDao cursors, MavenSnapshotDao snapshots,
      AssetDao assets, MavenSnapshotAssetObserver observer, PlatformTransactionManager transactions) {
    this.cursors = cursors; this.snapshots = snapshots; this.assets = assets; this.observer = observer;
    this.transactions = new TransactionTemplate(transactions);
  }

  /** Start only after the administrator has upgraded all replicas and creates a snapshot policy.
   * Starting during the rolling deployment could miss later writes from an old replica. */
  public void activate() {
    transactions.executeWithoutResult(status -> {
      cursors.ensureCursor(ACTIVATED);
      cursors.updateLastSeenId(ACTIVATED, 1);
    });
  }

  @Scheduled(fixedDelayString = "${kkrepo.maven.snapshot-backfill.delay-ms:1000}",
      initialDelayString = "${kkrepo.maven.snapshot-backfill.initial-delay-ms:1000}")
  public void runBatch() {
    if (cursors.minimumLastSeenId(ACTIVATED).orElse(0) != 1) return;
    cursors.ensureCursor(CURSOR);
    cursors.ensureCursor(READY);
    if (cursors.lastSeenId(READY) == 1) return;
    transactions.executeWithoutResult(status -> {
      var cursor = cursors.tryLockLastSeenId(CURSOR);
      if (cursor.isEmpty() || cursors.lastSeenId(READY) == 1) return;
      var ids = snapshots.backfillPage(cursor.getAsLong(), 256);
      var rows = assets.findAssetsByIds(ids);
      for (long id : ids) {
        var prior = rows.get(id);
        if (prior == null || !observer.supports(prior)) continue;
        // Same family -> asset lock order as live mutations, followed by a fresh row read.
        observer.beforeMutation(prior);
        assets.findAssetByIdForUpdate(id).ifPresent(asset -> observer.index(asset, true));
      }
      if (!ids.isEmpty()) cursors.updateLastSeenId(CURSOR, ids.getLast());
      if (ids.size() < 256) cursors.updateLastSeenId(READY, 1);
    });
  }
}
