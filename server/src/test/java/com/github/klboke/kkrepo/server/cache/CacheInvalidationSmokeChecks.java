package com.github.klboke.kkrepo.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import com.github.klboke.kkrepo.cache.InMemorySharedCache;
import com.github.klboke.kkrepo.server.transaction.TransientTransactionRetry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real lock waits and a one-connection pool, run on both database engines by the runtime smoke. */
public final class CacheInvalidationSmokeChecks {
  private static final long REPOSITORY = 9900414L;
  private static final String VERSION = "asset-metadata:repo:" + REPOSITORY;

  private CacheInvalidationSmokeChecks() {}

  public record Persistence(
      CacheVersionDao versions, PlatformTransactionManager transactions, Consumer<String> execute) {}

  public static void verify(HikariDataSource source,
      Function<DataSource, Persistence> persistenceFactory, boolean mysql) throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(source.getJdbcUrl());
    config.setUsername(source.getUsername());
    config.setPassword(source.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(1500);
    config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    // A commit callback must not rely on setAutoCommit(true) accidentally committing its SQL.
    config.setAutoCommit(false);
    try (HikariDataSource pool = new HikariDataSource(config);
         Connection observer = DriverManager.getConnection(
             source.getJdbcUrl(), source.getUsername(), source.getPassword())) {
      try (var statement = observer.createStatement()) {
        statement.execute("CREATE TABLE cache_invalidation_probe (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
        statement.executeUpdate("INSERT INTO cache_invalidation_probe VALUES (1, 0)");
        statement.executeUpdate("INSERT INTO cache_version (name, version, updated_at) VALUES ('" + VERSION + "', 0, CURRENT_TIMESTAMP)");
      }
      try {
        verify(pool, observer, persistenceFactory.apply(pool), mysql);
      } finally {
        if (!observer.getAutoCommit()) observer.rollback();
        observer.setAutoCommit(true);
        try (var statement = observer.createStatement()) {
          statement.execute("DROP TABLE cache_invalidation_probe");
          statement.executeUpdate("DELETE FROM cache_version WHERE name = '" + VERSION + "'");
        }
      }
    }
  }

  private static void verify(HikariDataSource pool, Connection observer, Persistence persistence, boolean mysql)
      throws Exception {
    CacheVersionDao dao = persistence.versions();
    JdbcVersionWatermark watermark = new JdbcVersionWatermark(dao, 60);
    var cache = cache(watermark);
    var retry = new TransientTransactionRetry(persistence.transactions(), 3, 0);

    retry.execute("atomic cache generation", () -> {
      persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
      cache.evictAfterCommit(REPOSITORY, "simple/a/");
      cache.evictAfterCommit(REPOSITORY, "simple/b/");
      cache.evictAfterCommit(REPOSITORY, "simple/a/");
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() {
          assertEquals(1, read(observer, "SELECT value FROM cache_invalidation_probe"));
          assertEquals(1, read(observer, "SELECT version FROM cache_version WHERE name = '" + VERSION + "'"),
              "another replica must see the generation before connection cleanup");
          assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
        }
      });
      return null;
    });
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    assertEquals(1, watermark.current(VERSION));

    // Force an actual database timeout on the first attempt, then release the blocking replica.
    var release = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    var failed = new AtomicInteger();
    VersionWatermark contended = new VersionWatermark() {
      @Override public long bump(String name) {
        attempts.incrementAndGet();
        setLockTimeout(persistence, mysql);
        try {
          return watermark.bump(name);
        } catch (DataAccessException failure) {
          failed.incrementAndGet();
          assertEquals(1, read(observer, "SELECT value FROM cache_invalidation_probe"),
              "the failed attempt must not publish its business write");
          release.countDown();
          await(released);
          throw failure;
        }
      }
      @Override public long current(String name) { return watermark.current(name); }
      @Override public long currentDurable(String name) { return watermark.currentDurable(name); }
      @Override public Map<String, Long> currentAll() { return watermark.currentAll(); }
    };
    observer.setAutoCommit(false);
    try (var statement = observer.createStatement()) {
      statement.executeUpdate("UPDATE cache_version SET version = version + 1 WHERE name = '" + VERSION + "'");
    }
    try (var executor = Executors.newSingleThreadExecutor()) {
      var blocker = executor.submit(() -> {
        await(release);
        observer.rollback();
        observer.setAutoCommit(true);
        released.countDown();
        return null;
      });
      try {
        retry.execute("retry invalidation lock timeout", () -> {
          persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
          cache(contended).evictAfterCommit(REPOSITORY, "simple/retry/");
          return null;
        });
        blocker.get(10, TimeUnit.SECONDS);
      } finally {
        release.countDown();
      }
    }
    assertEquals(1, failed.get());
    assertEquals(2, attempts.get(), "the complete transaction must retry after a real lock timeout");
    assertEquals(2, read(observer, "SELECT value FROM cache_invalidation_probe"));
    assertEquals(2, dao.current(VERSION));
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());

    // Exhausting the retry budget rolls back both data and version, and releases the connection.
    observer.setAutoCommit(false);
    try (var statement = observer.createStatement()) {
      statement.executeUpdate("UPDATE cache_version SET version = version + 1 WHERE name = '" + VERSION + "'");
    }
    try {
      assertThrows(DataAccessException.class, () -> retry.execute("exhausted invalidation", () -> {
        setLockTimeout(persistence, mysql);
        persistence.execute().accept("UPDATE cache_invalidation_probe SET value = value + 1 WHERE id = 1");
        cache.evictAfterCommit(REPOSITORY, "simple/exhausted/");
        return null;
      }));
    } finally {
      observer.rollback();
      observer.setAutoCommit(true);
    }
    assertEquals(2, read(observer, "SELECT value FROM cache_invalidation_probe"));
    assertEquals(2, watermark.current(VERSION));
    assertEquals(2, dao.current(VERSION));
    assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  private static AssetMetadataCache cache(VersionWatermark watermark) {
    return new AssetMetadataCache(new InMemorySharedCache(new ObjectMapper(), 100, null),
        watermark, true, 60, 5);
  }

  private static void setLockTimeout(Persistence persistence, boolean mysql) {
    persistence.execute().accept(mysql
        ? "SET SESSION innodb_lock_wait_timeout = 1" : "SET LOCAL lock_timeout = '200ms'");
  }

  private static long read(Connection connection, String sql) {
    try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    } catch (java.sql.SQLException e) {
      throw new AssertionError(e);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out waiting for the blocking replica");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
