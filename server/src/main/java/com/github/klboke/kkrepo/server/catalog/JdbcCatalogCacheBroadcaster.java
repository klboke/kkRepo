package com.github.klboke.kkrepo.server.catalog;

import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${kkrepo.catalog-cache.broadcast-backend:jdbc}' == 'jdbc' "
    + "|| '${kkrepo.catalog-cache.broadcast-backend:jdbc}' == 'mysql'")
class JdbcCatalogCacheBroadcaster implements CatalogCacheBroadcaster, SchedulingConfigurer {
  private static final Logger log = LoggerFactory.getLogger(JdbcCatalogCacheBroadcaster.class);
  private static final String NAME_PREFIX = "catalog:";

  private final VersionWatermark watermark;
  private final Duration pollDelay;
  private final Duration initialDelay;
  private final Map<String, List<Runnable>> listeners = new ConcurrentHashMap<>();
  private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

  @Autowired
  JdbcCatalogCacheBroadcaster(
      VersionWatermark watermark,
      @Value("${kkrepo.catalog-cache.jdbc.poll-delay-ms:500}") long pollDelayMs,
      @Value("${kkrepo.catalog-cache.jdbc.initial-delay-ms:500}") long initialDelayMs,
      @Value("${kkrepo.catalog-cache.jdbc.initial-jitter-ms:100}") long initialJitterMs) {
    this.watermark = watermark;
    this.pollDelay = Duration.ofMillis(Math.max(1, pollDelayMs));
    this.initialDelay = Duration.ofMillis(jitteredInitialDelay(initialDelayMs, initialJitterMs));
  }

  JdbcCatalogCacheBroadcaster(VersionWatermark watermark) {
    this(watermark, 500, 500, 100);
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
    taskRegistrar.addFixedDelayTask(new FixedDelayTask(this::poll, pollDelay, initialDelay));
  }

  @Override
  public void subscribe(String catalogName, Runnable refreshListener) {
    String name = versionName(catalogName);
    if (refreshListener == null) {
      return;
    }
    listeners.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(refreshListener);
    lastSeen.putIfAbsent(name, watermark.current(name));
    log.debug("Subscribed {} catalog-cache refresh listener to JDBC watermark", catalogName);
  }

  @Override
  public void publishRefresh(String catalogName) {
    String name = versionName(catalogName);
    long version = watermark.bump(name);
    lastSeen.put(name, version);
  }

  public void poll() {
    if (listeners.isEmpty()) {
      return;
    }
    Map<String, Long> versions;
    try {
      versions = watermark.currentAll();
    } catch (RuntimeException e) {
      log.warn("Failed polling catalog cache JDBC watermarks", e);
      return;
    }
    versions.forEach(this::refreshIfChanged);
  }

  private void refreshIfChanged(String name, long version) {
    if (!name.startsWith(NAME_PREFIX)) {
      return;
    }
    List<Runnable> catalogListeners = listeners.getOrDefault(name, List.of());
    if (catalogListeners.isEmpty()) {
      return;
    }
    long seen = lastSeen.getOrDefault(name, version);
    if (version <= seen) {
      return;
    }
    try {
      for (Runnable listener : new ArrayList<>(catalogListeners)) {
        listener.run();
      }
      lastSeen.put(name, version);
    } catch (RuntimeException e) {
      log.warn("Failed refreshing {} catalog after JDBC watermark advanced to {}; will retry",
          name.substring(NAME_PREFIX.length()), version, e);
    }
  }

  private static String versionName(String catalogName) {
    if (catalogName == null || catalogName.isBlank()) {
      throw new IllegalArgumentException("Catalog name is required");
    }
    return NAME_PREFIX + catalogName.trim();
  }

  private static long jitteredInitialDelay(long initialDelayMs, long initialJitterMs) {
    long base = Math.max(0, initialDelayMs);
    long jitterLimit = Math.max(0, initialJitterMs);
    long jitterBound = jitterLimit == Long.MAX_VALUE ? Long.MAX_VALUE : jitterLimit + 1;
    long jitter = jitterBound > 1 ? ThreadLocalRandom.current().nextLong(jitterBound) : 0;
    return base > Long.MAX_VALUE - jitter ? Long.MAX_VALUE : base + jitter;
  }
}
