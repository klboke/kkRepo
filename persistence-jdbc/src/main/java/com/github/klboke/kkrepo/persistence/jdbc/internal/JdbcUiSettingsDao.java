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
  private final JdbcTemplate jdbcTemplate;

  public JdbcUiSettingsDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UiSettingsRecord read() {
    List<UiSettingsRecord> rows = jdbcTemplate.query("""
        SELECT default_language, default_theme, updated_at
        FROM ui_settings
        WHERE id = 1
        """, (rs, rowNum) -> new UiSettingsRecord(
            rs.getString("default_language"),
            rs.getString("default_theme"),
            JdbcRows.nullableInstant(rs, "updated_at")));
    return rows.isEmpty()
        ? new UiSettingsRecord(DEFAULT_LANGUAGE, DEFAULT_THEME, null)
        : rows.get(0);
  }

  @Transactional
  public UiSettingsRecord save(String defaultLanguage, String defaultTheme) {
    String normalizedLanguage = normalizeDefaultLanguage(defaultLanguage);
    String normalizedTheme = normalizeDefaultTheme(defaultTheme);
    JdbcUpserts.updateThenInsert(
        jdbcTemplate,
        """
        UPDATE ui_settings
        SET default_language = ?, default_theme = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = 1
        """,
        new Object[]{normalizedLanguage, normalizedTheme},
        """
        INSERT INTO ui_settings (id, default_language, default_theme, updated_at)
        VALUES (1, ?, ?, CURRENT_TIMESTAMP)
        """,
        new Object[]{normalizedLanguage, normalizedTheme});
    return read();
  }

  public static String normalizeDefaultLanguage(String defaultLanguage) {
    return com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao
        .normalizeDefaultLanguage(defaultLanguage);
  }

  public static String normalizeDefaultTheme(String defaultTheme) {
    return com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao
        .normalizeDefaultTheme(defaultTheme);
  }
}
