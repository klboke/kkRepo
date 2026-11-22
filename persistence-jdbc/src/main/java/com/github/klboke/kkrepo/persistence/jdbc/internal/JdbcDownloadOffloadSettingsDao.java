package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.DownloadOffloadSettingsDao;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDownloadOffloadSettingsDao implements DownloadOffloadSettingsDao {
  private final JdbcTemplate jdbc;

  public JdbcDownloadOffloadSettingsDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Settings read() {
    return jdbc.queryForObject("""
        SELECT revision, enabled, method, custom_header, custom_target, blob_store_ids
        FROM download_offload_settings WHERE id = 1
        """, (rs, row) -> {
          String ids = rs.getString("blob_store_ids");
          List<Long> stores = ids.isEmpty() ? List.of() : Arrays.stream(ids.split(",")).map(Long::valueOf).toList();
          return new Settings(rs.getLong("revision"), rs.getBoolean("enabled"), rs.getString("method"),
              rs.getString("custom_header"), rs.getString("custom_target"), stores);
        });
  }

  @Override
  public boolean replace(Settings settings) {
    return jdbc.update("""
        UPDATE download_offload_settings
        SET revision = revision + 1, enabled = ?, method = ?, custom_header = ?, custom_target = ?, blob_store_ids = ?
        WHERE id = 1 AND revision = ?
        """, settings.enabled(), settings.method(), settings.customHeader(), settings.customTarget(),
        settings.storeIds().stream().map(String::valueOf).collect(Collectors.joining(",")), settings.revision()) == 1;
  }
}
