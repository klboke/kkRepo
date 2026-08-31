package com.github.klboke.kkrepo.server.support;

import com.github.klboke.kkrepo.server.cache.VersionWatermark;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVersionWatermark implements VersionWatermark {
  private final Map<String, Long> versions = new ConcurrentHashMap<>();

  @Override
  public long bump(String name) {
    return versions.merge(name, 1L, Long::sum);
  }

  @Override
  public long current(String name) {
    return versions.getOrDefault(name, 0L);
  }

  @Override
  public long currentDurable(String name) {
    return current(name);
  }

  @Override
  public Map<String, Long> currentAll() {
    return new LinkedHashMap<>(versions);
  }
}
