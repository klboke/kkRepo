package com.github.klboke.kkrepo.server.catalog;

import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "kkrepo.catalog-cache",
    name = "broadcast-backend",
    havingValue = "mysql",
    matchIfMissing = true)
class JdbcCatalogCacheBroadcaster implements CatalogCacheBroadcaster {
  private static final Logger log = LoggerFactory.getLogger(JdbcCatalogCacheBroadcaster.class);
  private static final String NAME_PREFIX = "catalog:";

  private final VersionWatermark watermark;
  private final Map<String, List<Runnable>> listeners = new ConcurrentHashMap<>();
  private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

  JdbcCatalogCacheBroadcaster(VersionWatermark watermark) {
    this.watermark = watermark;
  }

  @Override
  public void subscribe(String catalogName, Runnable refreshListener) {
    String name = versionName(catalogName);
    if (refreshListener == null) {
      return;
    }
    listeners.computeIfAbsent(name, ignored -> new CopyOnWriteArrayList<>()).add(refreshListener);
    lastSeen.putIfAbsent(name, watermark.current(name));
    log.debug("Subscribed {} catalog-cache refresh listener to MySQL watermark", catalogName);
  }

  @Override
  public void publishRefresh(String catalogName) {
    String name = versionName(catalogName);
    long version = watermark.bump(name);
    lastSeen.put(name, version);
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.catalog-cache.mysql.poll-delay-ms:500}",
      initialDelayString =
          "#{${kkrepo.catalog-cache.mysql.initial-delay-ms:500} "
              + "+ T(java.util.concurrent.ThreadLocalRandom).current().nextLong("
              + "T(java.lang.Math).max(0, ${kkrepo.catalog-cache.mysql.initial-jitter-ms:100}) + 1)}")
  public void poll() {
    if (listeners.isEmpty()) {
      return;
    }
    Map<String, Long> versions;
    try {
      versions = watermark.currentAll();
    } catch (RuntimeException e) {
      log.warn("Failed polling catalog cache MySQL watermarks", e);
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
      log.warn("Failed refreshing {} catalog after MySQL watermark advanced to {}; will retry",
          name.substring(NAME_PREFIX.length()), version, e);
    }
  }

  private static String versionName(String catalogName) {
    if (catalogName == null || catalogName.isBlank()) {
      throw new IllegalArgumentException("Catalog name is required");
    }
    return NAME_PREFIX + catalogName.trim();
  }
}
