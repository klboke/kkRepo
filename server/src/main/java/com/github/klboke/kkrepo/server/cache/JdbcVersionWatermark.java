package com.github.klboke.kkrepo.server.cache;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
    localVersions.put(name, version);
    return version;
  }

  @Override
  public long current(String name) {
    return localVersions.get(name, dao::current);
  }

  @Override
  public long currentDurable(String name) {
    return dao.current(name);
  }

  @Override
  public Map<String, Long> currentAll() {
    Map<String, Long> versions = dao.selectAll();
    versions.forEach(localVersions::put);
    return versions;
  }
}
