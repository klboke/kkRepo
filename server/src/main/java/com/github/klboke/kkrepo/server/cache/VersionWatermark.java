package com.github.klboke.kkrepo.server.cache;

import java.util.Map;

public interface VersionWatermark {
  long bump(String name);

  long current(String name);

  /** Reads the committed database value without consulting a node-local cache. */
  long currentDurable(String name);

  Map<String, Long> currentAll();
}
