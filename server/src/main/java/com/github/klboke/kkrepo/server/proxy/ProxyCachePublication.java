package com.github.klboke.kkrepo.server.proxy;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Request-scoped publication precondition, not a cache or a distributed lock. The repository row
 * and asset unique constraint are the shared authority. No network I/O runs under this row lock.
 */
public final class ProxyCachePublication implements AutoCloseable {
  private static final ThreadLocal<ProxyCachePublication> CURRENT = new ThreadLocal<>();
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final RepositoryRecord snapshot;
  private final java.time.Instant revision;
  private final String path;
  private final String clientIp;

  ProxyCachePublication(RepositoryDao repositories, AssetDao assets, RepositoryRecord snapshot,
      java.time.Instant revision, String path, String clientIp) {
    this.repositories = repositories;
    this.assets = assets;
    this.snapshot = snapshot;
    this.revision = revision;
    this.path = path;
    this.clientIp = clientIp;
    if (CURRENT.get() != null) throw new IllegalStateException("Nested proxy cache publication");
    CURRENT.set(this);
  }

  public static String clientIp() {
    return CURRENT.get() == null ? null : CURRENT.get().clientIp;
  }

  /** Called inside the existing protocol writer's metadata transaction, after Blob upload. */
  public static void check(RepositoryRuntime runtime, String path) {
    ProxyCachePublication current = CURRENT.get();
    if (current == null) return;
    if (runtime.id() != current.snapshot.id() || !current.path.equals(path)) {
      throw new IllegalStateException("Unexpected progressive cache publication target");
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Progressive cache publication requires a transaction");
    }
    RepositoryRecord live = current.repositories.lockByName(runtime.name()).orElse(null);
    if (live == null || !Objects.equals(live.id(), current.snapshot.id())
        || !Objects.equals(current.repositories.findUpdatedAt(runtime.id()).orElse(null), current.revision)
        || !Objects.equals(live.attributes(), current.snapshot.attributes())
        || !Objects.equals(live.blobStoreId(), current.snapshot.blobStoreId())
        || !Objects.equals(live.proxyRemoteUrl(), current.snapshot.proxyRemoteUrl())
        || !live.online() || current.assets.findAssetByPath(runtime.id(), path).isPresent()) {
      throw new Superseded();
    }
  }

  @Override public void close() { if (CURRENT.get() == this) CURRENT.remove(); }

  static final class Superseded extends RuntimeException {
    Superseded() { super("Proxy cache fill superseded by shared repository state"); }
  }
}
