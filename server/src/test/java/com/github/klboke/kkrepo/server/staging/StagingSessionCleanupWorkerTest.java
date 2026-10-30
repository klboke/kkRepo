package com.github.klboke.kkrepo.server.staging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StagingSessionCleanupWorkerTest {
  private final StagingDao staging = mock(StagingDao.class);
  private final StagingService service = mock(StagingService.class);
  private final StagingSessionCleanupWorker worker =
      new StagingSessionCleanupWorker(staging, service);

  @Test
  void expiresEveryDueSessionAndContinuesAfterFailures() {
    StagingSessionRecord first = session(1L);
    StagingSessionRecord second = session(2L);
    when(staging.listExpiredActive(any(), anyInt())).thenReturn(List.of(first, second));
    when(service.expire(1L)).thenThrow(new IllegalStateException("database hiccup"));
    when(service.expire(2L)).thenReturn(true);

    worker.sweep();

    verify(service).expire(1L);
    verify(service).expire(2L);
  }

  @Test
  void ignoresExpiryLoadFailures() {
    when(staging.listExpiredActive(any(), anyInt()))
        .thenThrow(new IllegalStateException("database down"));

    worker.sweep();

    verify(service, never()).expire(anyLong());
  }

  private static StagingSessionRecord session(long id) {
    Instant now = Instant.now();
    return new StagingSessionRecord(
        id, "session-" + id, StagingDao.SESSION_OPEN, 0, "Local", "alice",
        now, now, null, null, now.minusSeconds(60), null, Map.of());
  }
}
