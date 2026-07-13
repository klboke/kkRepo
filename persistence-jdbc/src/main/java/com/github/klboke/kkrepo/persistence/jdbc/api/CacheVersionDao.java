package com.github.klboke.kkrepo.persistence.jdbc.api;

import java.util.Map;

public interface CacheVersionDao {
  long bump(String name);

  long current(String name);

  Map<String, Long> selectAll();
}
