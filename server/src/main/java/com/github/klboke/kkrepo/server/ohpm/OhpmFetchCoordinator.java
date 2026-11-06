package com.github.klboke.kkrepo.server.ohpm;

import com.github.klboke.kkrepo.persistence.jdbc.api.OhpmRepositoryDao;
import com.github.klboke.kkrepo.protocol.ohpm.OhpmException;
import com.github.klboke.kkrepo.server.coordination.FencedLeaseManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OhpmFetchCoordinator {
  private final FencedLeaseManager leases;

  public OhpmFetchCoordinator(OhpmRepositoryDao dao) {
    leases = new FencedLeaseManager(new FencedLeaseManager.Store() {
      @Override
      public Optional<FencedLeaseManager.Row> tryAcquireLease(String key, String owner,
          Instant now, Instant expiresAt) {
        return dao.acquire(key, owner, now, expiresAt)
            .map(row -> new FencedLeaseManager.Row(row.key(), row.owner(), row.fence(), row.expiresAt()));
      }

      @Override
      public boolean renewLease(String key, String owner, long fence, Instant now, Instant expiresAt) {
        return dao.renew(key, owner, fence, now, expiresAt);
      }

      @Override
      public void releaseLease(String key, String owner, long fence) {
        dao.release(key, owner, fence);
      }
    }, Duration.ofSeconds(30), Duration.ofSeconds(60), "ohpm-fetch-renewal",
        key -> new OhpmException(503, "OHPM file fetch is busy or its lease expired; retry"));
  }

  public FencedLeaseManager.Lease acquire(String key) {
    return leases.acquire(key);
  }
}
