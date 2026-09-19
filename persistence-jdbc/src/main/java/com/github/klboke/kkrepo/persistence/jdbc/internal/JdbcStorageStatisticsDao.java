package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.StorageStatisticsDao;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStorageStatisticsDao implements StorageStatisticsDao {
  private final JdbcTemplate jdbc;

  public JdbcStorageStatisticsDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Map<Long, BlobStoreUsage> blobStoreUsage() {
    // Count inventory directly: joining asset would double-count shared blobs and omit
    // non-asset owners and objects awaiting GC. Existing blob_store_id indexes cover this.
    return jdbc.query("""
        SELECT blob_store_id, COUNT(*), COALESCE(SUM(size), 0),
               SUM(CASE WHEN deleted_at IS NOT NULL THEN 1 ELSE 0 END),
               SUM(CASE WHEN deleted_at IS NOT NULL THEN size ELSE 0 END)
        FROM asset_blob
        GROUP BY blob_store_id
        """, rs -> {
      Map<Long, BlobStoreUsage> result = new LinkedHashMap<>();
      while (rs.next()) result.put(rs.getLong(1),
          new BlobStoreUsage(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
      return Map.copyOf(result);
    });
  }

  @Override
  public Map<Long, RepositoryUsage> repositoryUsage() {
    // One aggregate for the grid, not one round trip per repository. Covered by
    // the repository usage covering index on both supported databases.
    return jdbc.query("""
        SELECT repository_id, COUNT(*), COALESCE(SUM(size), 0), COUNT(*) - COUNT(size)
        FROM asset
        GROUP BY repository_id
        """, rs -> {
      Map<Long, RepositoryUsage> result = new LinkedHashMap<>();
      while (rs.next()) result.put(rs.getLong(1),
          new RepositoryUsage(rs.getLong(2), rs.getLong(3), rs.getLong(4)));
      return Map.copyOf(result);
    });
  }
}
