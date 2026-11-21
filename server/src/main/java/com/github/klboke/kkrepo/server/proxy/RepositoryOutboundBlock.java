package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Reads the operator's switch from durable storage at the point of an outbound attempt. */
@Component
public class RepositoryOutboundBlock {
  private final RepositoryDao repositories;

  public RepositoryOutboundBlock(RepositoryDao repositories) {
    this.repositories = repositories;
  }

  public void check(String repository) throws Blocked {
    if (repository == null) return;
    // A cached group member or a long-running background task can carry an old runtime.
    boolean blocked = repositories.findByName(repository)
        .map(record -> record.type() == RepositoryType.PROXY
            && record.attributes() != null && record.attributes().get("proxy") instanceof Map<?, ?> proxy
            && Boolean.TRUE.equals(proxy.get("blockOutboundConnections")))
        .orElse(true);
    if (blocked) throw new Blocked();
  }

  /** A local policy decision. Callers can use cached content without recording an upstream failure. */
  public static final class Blocked extends IOException {
    public Blocked() {
      super("Outbound connections are blocked for this repository");
    }
  }
}
