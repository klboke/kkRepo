package com.github.klboke.kkrepo.server.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction-scoped invalidations, not a cache or an asynchronous work queue.
 *
 * <p>Durable invalidations join the asset transaction. Cascading group changes run first, then
 * repository watermarks are bumped once in a stable order immediately before commit. Failures
 * reach the transaction owner, which can roll back and retry the complete write. Only local
 * cache eviction runs after commit; no additional pooled connection is needed for invalidation.
 */
public final class CacheInvalidation {
  private static final Logger log = LoggerFactory.getLogger(CacheInvalidation.class);
  private static final Object RESOURCE = CacheInvalidation.class.getName();

  private CacheInvalidation() {}

  public static void beforeCommit(Object key, Runnable mutation) {
    Pending pending = pending();
    if (pending == null) {
      mutation.run();
    } else {
      pending.requireBeforeCommit();
      pending.mutations.putIfAbsent(key, mutation);
    }
  }

  public static void watermarkBeforeCommit(String name, Runnable bump) {
    Pending pending = pending();
    if (pending == null) {
      bump.run();
    } else {
      pending.requireBeforeCommit();
      pending.watermarks.putIfAbsent(name, bump);
    }
  }

  public static void afterCommit(Object key, Runnable eviction) {
    Pending pending = pending();
    if (pending == null) {
      eviction.run();
    } else {
      pending.evictions.putIfAbsent(key, eviction);
    }
  }

  private static Pending pending() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return null;
    Pending pending = (Pending) TransactionSynchronizationManager.getResource(RESOURCE);
    if (pending == null) {
      pending = new Pending();
      TransactionSynchronizationManager.bindResource(RESOURCE, pending);
      TransactionSynchronizationManager.registerSynchronization(pending);
    }
    return pending;
  }

  private static final class Pending implements TransactionSynchronization {
    private final Map<Object, Runnable> mutations = new LinkedHashMap<>();
    private final Map<String, Runnable> watermarks = new TreeMap<>();
    private final Map<Object, Runnable> evictions = new LinkedHashMap<>();
    private boolean committed;

    private void requireBeforeCommit() {
      if (committed) {
        throw new IllegalStateException("Durable cache invalidation must be scheduled before commit");
      }
    }

    @Override
    public void suspend() {
      TransactionSynchronizationManager.unbindResource(RESOURCE);
    }

    @Override
    public void resume() {
      TransactionSynchronizationManager.bindResource(RESOURCE, this);
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      // Cascades can enqueue more group mutations and watermarks. Drain them in this same
      // callback rather than registering a synchronization too late for Spring's snapshot.
      drain(mutations);
      drain(watermarks);
    }

    @Override
    public void afterCommit() {
      committed = true;
      while (!evictions.isEmpty()) {
        try {
          takeFirst(evictions).run();
        } catch (RuntimeException e) {
          // The database has committed. A local-cache failure must never replay that write.
          log.warn("Failed evicting local cache after commit; TTL remains the fallback", e);
        }
      }
    }

    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE);
    }

    private static void drain(Map<?, Runnable> actions) {
      while (!actions.isEmpty()) takeFirst(actions).run();
    }

    private static Runnable takeFirst(Map<?, Runnable> actions) {
      var iterator = actions.values().iterator();
      Runnable action = iterator.next();
      iterator.remove();
      return action;
    }
  }
}
