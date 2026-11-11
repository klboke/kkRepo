package com.github.klboke.kkrepo.server.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.*;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MavenSnapshotBackfillWorkerTest {
  final MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
  final MavenSnapshotDao snapshots = mock(MavenSnapshotDao.class);
  final AssetDao assets = mock(AssetDao.class);
  final MavenSnapshotAssetObserver observer = mock(MavenSnapshotAssetObserver.class);
  final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  final MavenSnapshotBackfillWorker worker = new MavenSnapshotBackfillWorker(cursors, snapshots, assets, observer, transactions);

  @org.junit.jupiter.api.BeforeEach void activated() {
    when(cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.ACTIVATED)).thenReturn(OptionalLong.of(1));
  }
  @Test void upgradeDoesNotStartBackfillBeforeTheFirstSnapshotPolicy() {
    when(cursors.minimumLastSeenId(MavenSnapshotBackfillWorker.ACTIVATED)).thenReturn(OptionalLong.empty());
    worker.runBatch();
    verifyNoInteractions(snapshots, assets, observer, transactions);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    worker.activate();
    verify(cursors).ensureCursor(MavenSnapshotBackfillWorker.ACTIVATED);
    verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.ACTIVATED, 1);
    verify(transactions).commit(any());
  }
  @Test void pagesMixedHistoricalAssetsInBulkAndLocksOnlySnapshotCandidates() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(cursors.tryLockLastSeenId(MavenSnapshotBackfillWorker.CURSOR)).thenReturn(OptionalLong.of(0));
    var ids = LongStream.rangeClosed(1, 256).boxed().toList();
    when(snapshots.backfillPage(0, 256)).thenReturn(ids);
    var snapshot = MavenSnapshotAssetObserverTest.asset(RepositoryFormat.MAVEN2, "com/example/demo/1-SNAPSHOT/demo-1-20260101.120000-1.jar");
    var other = MavenSnapshotAssetObserverTest.asset(RepositoryFormat.RAW, "file.zip");
    when(assets.findAssetsByIds(ids)).thenReturn(Map.of(1L, snapshot, 2L, other));
    when(observer.supports(snapshot)).thenReturn(true);
    when(assets.findAssetByIdForUpdate(1)).thenReturn(Optional.of(snapshot));
    worker.runBatch();
    var order = inOrder(observer, assets, cursors, transactions);
    order.verify(assets).findAssetsByIds(ids);
    order.verify(observer).supports(snapshot);
    order.verify(observer).beforeMutation(snapshot);
    order.verify(assets).findAssetByIdForUpdate(1);
    order.verify(observer).index(snapshot, true);
    order.verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.CURSOR, 256);
    order.verify(transactions).commit(any());
    verify(assets, never()).findAssetById(anyLong());
    verify(assets, never()).findAssetByIdForUpdate(2);
    verify(cursors, never()).updateLastSeenId(MavenSnapshotBackfillWorker.READY, 1);
  }
  @Test void lastAssetPageWaitsForReconciliationBeforeMarkingReady() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(cursors.tryLockLastSeenId(MavenSnapshotBackfillWorker.CURSOR)).thenReturn(OptionalLong.of(256));
    worker.runBatch();
    verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.INDEXED, 1);
    verify(cursors, never()).updateLastSeenId(MavenSnapshotBackfillWorker.READY, 1);
    when(cursors.lastSeenId(MavenSnapshotBackfillWorker.INDEXED)).thenReturn(1L);
    when(snapshots.reconciliationPage(0, 64)).thenReturn(List.of(7L, 8L));
    worker.runBatch();
    var order = inOrder(snapshots, cursors);
    order.verify(snapshots).reconcile(7);
    order.verify(snapshots).reconcile(8);
    order.verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.RECONCILE, 8);
    order.verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.READY, 1);
    verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.READY, 1);
    clearInvocations(snapshots, transactions);
    when(cursors.lastSeenId(MavenSnapshotBackfillWorker.READY)).thenReturn(1L);
    worker.runBatch();
    verifyNoInteractions(snapshots, transactions);
  }
  @Test void fullReconciliationPageResumesWithoutRescanningAssetsOrMarkingReady() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(cursors.tryLockLastSeenId(MavenSnapshotBackfillWorker.CURSOR)).thenReturn(OptionalLong.of(256));
    when(cursors.lastSeenId(MavenSnapshotBackfillWorker.INDEXED)).thenReturn(1L);
    when(cursors.lastSeenId(MavenSnapshotBackfillWorker.RECONCILE)).thenReturn(100L);
    var ids = LongStream.rangeClosed(101, 164).boxed().toList();
    when(snapshots.reconciliationPage(100, 64)).thenReturn(ids);
    worker.runBatch();
    verify(snapshots, times(64)).reconcile(anyLong());
    verify(cursors).updateLastSeenId(MavenSnapshotBackfillWorker.RECONCILE, 164);
    verify(cursors, never()).updateLastSeenId(MavenSnapshotBackfillWorker.READY, 1);
    verify(snapshots, never()).backfillPage(anyLong(), anyInt());
    verifyNoInteractions(assets, observer);
  }
  @Test void anotherReplicaOwningTheCursorPreventsDuplicateBackfill() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(cursors.tryLockLastSeenId(MavenSnapshotBackfillWorker.CURSOR)).thenReturn(OptionalLong.empty());
    worker.runBatch();
    verifyNoInteractions(snapshots, assets, observer);
  }
}
