package com.github.klboke.kkrepo.persistence.mysql.dao;

import com.github.klboke.kkrepo.persistence.mysql.model.UiSettingsRecord;
import com.github.klboke.kkrepo.persistence.mysql.support.JdbcRows;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UiSettingsDao {
  public static final String LANGUAGE_BROWSER = "browser";
  public static final String LANGUAGE_ZH_CN = "zh-CN";
  public static final String LANGUAGE_EN = "en";
  public static final String DEFAULT_LANGUAGE = LANGUAGE_EN;

  private final JdbcTemplate jdbcTemplate;

  public UiSettingsDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UiSettingsRecord read() {
    List<UiSettingsRecord> rows = jdbcTemplate.query("""
        SELECT default_language, banner_enabled, banner_level, banner_message, banner_dismissible,
               product_name, product_subtitle, logo_text, logo_url, favicon_url, updated_at
        FROM ui_settings
        WHERE id = 1
        """, (rs, rowNum) -> new UiSettingsRecord(
            rs.getString("default_language"),
            rs.getBoolean("banner_enabled"),
            rs.getString("banner_level"),
            rs.getString("banner_message"),
            rs.getBoolean("banner_dismissible"),
            rs.getString("product_name"),
            rs.getString("product_subtitle"),
            rs.getString("logo_text"),
            rs.getString("logo_url"),
            rs.getString("favicon_url"),
            JdbcRows.nullableInstant(rs, "updated_at")));
    return rows.isEmpty() ? defaultRecord() : rows.get(0);
  }

  private static UiSettingsRecord defaultRecord() {
    return new UiSettingsRecord(DEFAULT_LANGUAGE, false, "info", null, true,
        "kkrepo", "Repository Manager", "KL", null, null, null);
  }

  @Transactional
  public UiSettingsRecord save(UiSettingsCommand command) {
    UiSettingsRecord current = read();
    String normalized = normalizeDefaultLanguage(
        command.defaultLanguage() != null ? command.defaultLanguage() : current.defaultLanguage());
    String normalizedLevel = normalizeBannerLevel(
        command.bannerLevel() != null ? command.bannerLevel() : current.bannerLevel());
    String productName = command.productName() != null ? command.productName() : current.productName();
    String productSubtitle = command.productSubtitle() != null ? command.productSubtitle() : current.productSubtitle();
    String logoText = command.logoText() != null ? command.logoText() : current.logoText();
    String logoUrl = command.logoUrl() != null ? command.logoUrl() : current.logoUrl();
    String faviconUrl = command.faviconUrl() != null ? command.faviconUrl() : current.faviconUrl();
    boolean bannerEnabled = command.bannerEnabled() != null ? command.bannerEnabled() : current.bannerEnabled();
    boolean bannerDismissible = command.bannerDismissible() != null ? command.bannerDismissible() : current.bannerDismissible();
    String bannerMessage = command.bannerMessage() != null ? command.bannerMessage() : current.bannerMessage();
    jdbcTemplate.update("""
        INSERT INTO ui_settings (id, default_language, banner_enabled, banner_level, banner_message,
          banner_dismissible, product_name, product_subtitle, logo_text, logo_url, favicon_url, updated_at)
        VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))
        ON DUPLICATE KEY UPDATE
          default_language = VALUES(default_language),
          banner_enabled = VALUES(banner_enabled),
          banner_level = VALUES(banner_level),
          banner_message = VALUES(banner_message),
          banner_dismissible = VALUES(banner_dismissible),
          product_name = VALUES(product_name),
          product_subtitle = VALUES(product_subtitle),
          logo_text = VALUES(logo_text),
          logo_url = VALUES(logo_url),
          favicon_url = VALUES(favicon_url),
          updated_at = NOW(3)
        """, normalized,
        bannerEnabled,
        normalizedLevel,
        bannerMessage,
        bannerDismissible,
        productName,
        productSubtitle,
        logoText,
        logoUrl,
        faviconUrl);
    return read();
  }

  @Transactional
  public UiSettingsRecord saveDefaultLanguage(String defaultLanguage) {
    return save(new UiSettingsCommand(defaultLanguage, false, "info", null, true,
        null, null, null, null, null));
  }

  public static String normalizeBannerLevel(String level) {
    if (level == null || level.isBlank()) return "info";
    String normalized = level.trim().toLowerCase();
    return switch (normalized) {
      case "info", "success", "warning", "danger" -> normalized;
      default -> "info";
    };
  }

  public record UiSettingsCommand(
      String defaultLanguage,
      Boolean bannerEnabled,
      String bannerLevel,
      String bannerMessage,
      Boolean bannerDismissible,
      String productName,
      String productSubtitle,
      String logoText,
      String logoUrl,
      String faviconUrl) {
  }

  public static String normalizeDefaultLanguage(String defaultLanguage) {
    if (defaultLanguage == null || defaultLanguage.isBlank()) {
      return DEFAULT_LANGUAGE;
    }
    String normalized = defaultLanguage.trim();
    if (LANGUAGE_BROWSER.equalsIgnoreCase(normalized)) {
      return LANGUAGE_BROWSER;
    }
    if ("zh".equalsIgnoreCase(normalized)
        || "zh-cn".equalsIgnoreCase(normalized)
        || "zh_CN".equalsIgnoreCase(normalized)) {
      return LANGUAGE_ZH_CN;
    }
    if ("en".equalsIgnoreCase(normalized) || "en-US".equalsIgnoreCase(normalized)) {
      return LANGUAGE_EN;
    }
    throw new IllegalArgumentException("Unsupported UI default language: " + defaultLanguage);
  }
}
