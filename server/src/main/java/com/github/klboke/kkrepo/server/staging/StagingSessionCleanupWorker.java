package com.github.klboke.kkrepo.server.staging;

import com.github.klboke.kkrepo.persistence.jdbc.api.StagingDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.StagingSessionRecord;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels expired staging sessions on every replica.
 *
 * <p>The database row lock and revision fence make concurrent sweeps safe: only one replica wins a
 * session transition, and the others observe the terminal state.
 */
@Component
public class StagingSessionCleanupWorker {
  private static final Logger log = LoggerFactory.getLogger(StagingSessionCleanupWorker.class);
  private static final int BATCH_SIZE = 100;

  private final StagingDao staging;
  private final StagingService service;

  public StagingSessionCleanupWorker(StagingDao staging, StagingService service) {
    this.staging = staging;
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.staging.expiry-sweep-ms:60000}",
      initialDelayString = "${kkrepo.staging.expiry-initial-delay-ms:30000}")
  public void sweep() {
    List<StagingSessionRecord> expired;
    try {
      expired = staging.listExpiredActive(Instant.now(), BATCH_SIZE);
    } catch (RuntimeException error) {
      log.warn("Failed to load expired staging sessions", error);
      return;
    }
    for (StagingSessionRecord session : expired) {
      try {
        if (service.expire(session.id())) {
          log.info("Expired staging session {} ({})", session.id(), session.name());
        }
      } catch (RuntimeException error) {
        log.warn("Failed to expire staging session {}", session.id(), error);
      }
    }
  }
}
