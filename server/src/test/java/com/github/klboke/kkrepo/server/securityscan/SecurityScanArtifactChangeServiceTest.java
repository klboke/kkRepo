package com.github.klboke.kkrepo.server.securityscan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao.ArtifactChange;
import com.github.klboke.kkrepo.persistence.jdbc.api.ArtifactChangeDao.ChangeKind;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SecurityScanArtifactChangeServiceTest {
  @Test
  void skipsWhenAnotherReplicaOwnsTheCursorLock() {
    ArtifactChangeDao changes = mock(ArtifactChangeDao.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    when(cursors.tryLockLastSeenId(SecurityScanArtifactChangeService.CURSOR_NAME))
        .thenReturn(OptionalLong.empty());

    SecurityScanArtifactChangeService service =
        new SecurityScanArtifactChangeService(
            changes, cursors, scans, new SecurityScanningProperties());

    assertEquals(0, service.processBatch());
    verifyNoInteractions(changes, scans);
  }

  @Test
  void foldsEachChangedAssetOnceAndAdvancesTheDurableCursor() {
    ArtifactChangeDao changes = mock(ArtifactChangeDao.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setArtifactChangeBatchSize(7);
    Instant now = Instant.parse("2026-07-25T01:00:00Z");
    List<ArtifactChange> events = List.of(
        new ArtifactChange(11L, 1L, 101L, null, 201L, ChangeKind.CONTENT_CREATED, now),
        new ArtifactChange(12L, 1L, 101L, 201L, 202L, ChangeKind.CONTENT_REPLACED, now),
        new ArtifactChange(13L, 1L, 102L, null, 203L, ChangeKind.CONTENT_CREATED, now));
    when(cursors.tryLockLastSeenId(SecurityScanArtifactChangeService.CURSOR_NAME))
        .thenReturn(OptionalLong.of(10L));
    when(changes.listAfter(10L, 7)).thenReturn(events);
    when(cursors.updateLastSeenId(SecurityScanArtifactChangeService.CURSOR_NAME, 13L))
        .thenReturn(1);
    when(cursors.minimumLastSeenId(SecurityScanArtifactChangeService.CURSOR_PREFIX))
        .thenReturn(OptionalLong.of(13L));

    SecurityScanArtifactChangeService service =
        new SecurityScanArtifactChangeService(changes, cursors, scans, properties);

    assertEquals(3, service.processBatch());
    verify(scans).recordArtifactContentChange(101L);
    verify(scans).recordArtifactContentChange(102L);
    verify(cursors).updateLastSeenId(SecurityScanArtifactChangeService.CURSOR_NAME, 13L);
    verify(changes).deleteThrough(13L, 5000);
  }

  @Test
  void failsTheBatchWhenTheLockedCursorCannotAdvance() {
    ArtifactChangeDao changes = mock(ArtifactChangeDao.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    SecurityScanDao scans = mock(SecurityScanDao.class);
    SecurityScanningProperties properties = new SecurityScanningProperties();
    properties.getWorker().setArtifactChangeBatchSize(4);
    ArtifactChange event = new ArtifactChange(
        11L, 1L, 101L, null, 201L, ChangeKind.CONTENT_CREATED, Instant.EPOCH);
    when(cursors.tryLockLastSeenId(SecurityScanArtifactChangeService.CURSOR_NAME))
        .thenReturn(OptionalLong.of(10L));
    when(changes.listAfter(10L, 4)).thenReturn(List.of(event));
    when(cursors.updateLastSeenId(SecurityScanArtifactChangeService.CURSOR_NAME, 11L))
        .thenReturn(0);

    SecurityScanArtifactChangeService service =
        new SecurityScanArtifactChangeService(
            changes, cursors, scans, properties);

    assertThrows(IllegalStateException.class, service::processBatch);
  }
}
