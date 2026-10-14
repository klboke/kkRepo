package com.github.klboke.kkrepo.server.cache;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class JdbcVersionWatermark implements VersionWatermark {
  private final CacheVersionDao dao;
  private final LocalCache<String, Long> localVersions;

  public JdbcVersionWatermark(
      CacheVersionDao dao,
      @Value("${kkrepo.cache.version.local-ttl-seconds:2}") long localTtlSeconds) {
    this.dao = dao;
    this.localVersions = LocalCacheFactory.standard()
        .<String, Long>builder("jdbc-version-watermarks")
        .expireAfterWrite(Duration.ofSeconds(Math.max(1, localTtlSeconds)))
        .maximumSize(100_000)
        .build();
  }

  @Override
  public long bump(String name) {
    long version = dao.bump(name);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      // Never expose an uncommitted generation to another thread, or retain it after rollback.
      // Invalidate again on completion to discard reads racing the transaction's commit.
      localVersions.invalidate(name);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          localVersions.invalidate(name);
        }
      });
    } else {
      localVersions.put(name, version);
    }
    return version;
  }

  @Override
  public long current(String name) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) return dao.current(name);
    return localVersions.get(name, dao::current);
  }

  @Override
  public long currentDurable(String name) {
    return dao.current(name);
  }

  @Override
  public Map<String, Long> currentAll() {
    Map<String, Long> versions = dao.selectAll();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      versions.forEach(localVersions::put);
    }
    return versions;
  }
}
