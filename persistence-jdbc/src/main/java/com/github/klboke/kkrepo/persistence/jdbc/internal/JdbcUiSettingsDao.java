package com.github.klboke.kkrepo.persistence.jdbc.internal;

import com.github.klboke.kkrepo.persistence.jdbc.api.model.UiSettingsRecord;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcRows;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JdbcUpserts;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUiSettingsDao implements com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao {
  public static final String LANGUAGE_BROWSER = "browser";
  public static final String LANGUAGE_ZH_CN = "zh-CN";
  public static final String LANGUAGE_EN = "en";
  public static final String DEFAULT_LANGUAGE = LANGUAGE_EN;

  private final JdbcTemplate jdbcTemplate;

  public JdbcUiSettingsDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UiSettingsRecord read() {
    List<UiSettingsRecord> rows = jdbcTemplate.query("""
        SELECT default_language, updated_at
        FROM ui_settings
        WHERE id = 1
        """, (rs, rowNum) -> new UiSettingsRecord(
            rs.getString("default_language"),
            JdbcRows.nullableInstant(rs, "updated_at")));
    return rows.isEmpty() ? new UiSettingsRecord(DEFAULT_LANGUAGE, null) : rows.get(0);
  }

  @Transactional
  public UiSettingsRecord saveDefaultLanguage(String defaultLanguage) {
    String normalized = normalizeDefaultLanguage(defaultLanguage);
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE ui_settings
        SET default_language = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = 1
        """,
        new Object[]{normalized},
        """
        INSERT INTO ui_settings (id, default_language, updated_at)
        VALUES (1, ?, CURRENT_TIMESTAMP)
        """,
        new Object[]{normalized});
    return read();
  }

  public static String normalizeDefaultLanguage(String defaultLanguage) {
    return com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao
        .normalizeDefaultLanguage(defaultLanguage);
  }
}
