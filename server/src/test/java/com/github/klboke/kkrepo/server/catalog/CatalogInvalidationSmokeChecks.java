package com.github.klboke.kkrepo.server.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.cache.InMemorySharedCache;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.server.cache.CacheInvalidationSmokeChecks.Persistence;
import com.github.klboke.kkrepo.server.cache.JdbcVersionWatermark;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache.AuthorizationSnapshot;
import com.github.klboke.kkrepo.server.security.SecurityCatalogCache;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the other shared-watermark callers with the same one-connection, non-autocommit pool. */
public final class CatalogInvalidationSmokeChecks {
  private CatalogInvalidationSmokeChecks() {}

  public static void verify(Persistence persistence, HikariDataSource pool, Connection observer)
      throws InterruptedException {
    var watermark = new JdbcVersionWatermark(persistence.versions(), 1);
    var broadcaster = new JdbcCatalogCacheBroadcaster(watermark);
    var provider = new StaticListableBeanFactory(Map.of("broadcaster", broadcaster))
        .getBeanProvider(CatalogCacheBroadcaster.class);
    var repositories = new RepositoryCatalogCache(mock(RepositoryDao.class), mock(BlobStoreDao.class),
        true, provider);
    var security = new SecurityCatalogCache(mock(SecurityDao.class), true, provider);
    var authorization = new SecurityAuthorizationCache(
        new InMemorySharedCache(new ObjectMapper(), 100, null), watermark, true, 10, 1);
    var sibling = new SecurityAuthorizationCache(new InMemorySharedCache(new ObjectMapper(), 100, null),
        new JdbcVersionWatermark(persistence.versions(), 1), true, 10, 1);
    var subject = new PermissionSubject("Local", "revoked-user", Set.of(), null);
    var oldGrants = new AuthorizationSnapshot(Set.of("reader"), List.of(), Map.of());
    var revoked = new AuthorizationSnapshot(Set.of(), List.of(), Map.of());
    assertEquals(oldGrants, sibling.getOrLoad(subject, () -> oldGrants));
    repositories.warmUp();
    security.warmUp();

    Map<String, Long> initial = new LinkedHashMap<>();
    for (String name : List.of("authz:global", "catalog:repository", "catalog:security")) {
      initial.put(name, persistence.versions().current(name));
    }
    var tx = new TransactionTemplate(persistence.transactions());
    tx.executeWithoutResult(status -> {
      authorization.invalidateAllAfterCommit();
      authorization.invalidateAllAfterCommit();
      repositories.refreshAfterCommit();
      repositories.refreshAfterCommit();
      security.refreshAfterCommit();
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
          initial.forEach((name, version) -> assertEquals(version + 1, readVersion(observer, name),
              "security/catalog generations must be committed before connection cleanup: " + name));
        }
      });
    });
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
    while (!revoked.equals(sibling.getOrLoad(subject, () -> revoked)) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(revoked, sibling.getOrLoad(subject, () -> revoked));

    // Fail after all before-commit bumps have executed. Neither durable nor observed generations
    // may advance, otherwise a later real revocation at that generation could be missed.
    var refreshes = new AtomicInteger();
    broadcaster.subscribe("security", refreshes::incrementAndGet);
    assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
      authorization.invalidateAllAfterCommit();
      repositories.refreshAfterCommit();
      security.refreshAfterCommit();
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void beforeCommit(boolean readOnly) {
          throw new IllegalStateException("roll back the already-written generations");
        }
      });
    }));
    initial.forEach((name, version) -> assertEquals(version + 1, readVersion(observer, name)));
    tx.executeWithoutResult(status -> watermark.bump("catalog:security"));
    broadcaster.poll();
    assertEquals(1, refreshes.get(), "rollback must not publish a phantom last-seen generation");
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
  }

  private static long readVersion(Connection observer, String name) {
    try (var statement = observer.prepareStatement("SELECT version FROM cache_version WHERE name = ?")) {
      statement.setString(1, name);
      try (var result = statement.executeQuery()) {
        return result.next() ? result.getLong(1) : 0;
      }
    } catch (java.sql.SQLException failure) {
      throw new AssertionError(failure);
    }
  }
}
