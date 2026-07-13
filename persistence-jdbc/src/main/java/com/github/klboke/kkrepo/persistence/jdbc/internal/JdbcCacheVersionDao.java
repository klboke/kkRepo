package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCacheVersionDao implements com.github.klboke.kkrepo.persistence.jdbc.api.CacheVersionDao {
  private final JdbcTemplate jdbcTemplate;
  private final CoordinationPersistenceDialect coordinationDialect;

  public JdbcCacheVersionDao(JdbcTemplate jdbcTemplate, DatabaseDialect databaseDialect) {
    this.jdbcTemplate = jdbcTemplate;
    this.coordinationDialect = databaseDialect.coordination();
  }

  @Transactional
  public long bump(String name) {
    String normalized = normalize(name);
    return coordinationDialect.bumpCacheVersion(jdbcTemplate, normalized);
  }

  public long current(String name) {
    String normalized = normalize(name);
    List<Long> values = jdbcTemplate.query("""
        SELECT version
        FROM cache_version
        WHERE name = ?
        """, (rs, rowNum) -> rs.getLong("version"), normalized);
    return values.isEmpty() ? 0 : values.get(0);
  }

  public Map<String, Long> selectAll() {
    return jdbcTemplate.query("""
        SELECT name, version
        FROM cache_version
        ORDER BY name
        """, rs -> {
          Map<String, Long> versions = new LinkedHashMap<>();
          while (rs.next()) {
            versions.put(rs.getString("name"), rs.getLong("version"));
          }
          return versions;
        });
  }

  private static String normalize(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Cache version name is required");
    }
    String normalized = name.trim();
    if (normalized.length() > 128) {
      throw new IllegalArgumentException("Cache version name is too long: " + normalized);
    }
    return normalized;
  }
}
