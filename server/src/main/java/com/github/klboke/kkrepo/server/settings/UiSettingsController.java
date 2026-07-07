package com.github.klboke.kkrepo.server.settings;

import com.github.klboke.kkrepo.persistence.mysql.dao.UiSettingsDao;
import com.github.klboke.kkrepo.persistence.mysql.model.UiSettingsRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/ui-settings")
public class UiSettingsController {
  private static final List<String> SUPPORTED_DEFAULT_LANGUAGES = List.of(
      UiSettingsDao.LANGUAGE_BROWSER,
      UiSettingsDao.LANGUAGE_ZH_CN,
      UiSettingsDao.LANGUAGE_EN);
  private static final List<String> SUPPORTED_BANNER_LEVELS = List.of(
      "info", "success", "warning", "danger");

  private final UiSettingsDao uiSettingsDao;

  public UiSettingsController(UiSettingsDao uiSettingsDao) {
    this.uiSettingsDao = uiSettingsDao;
  }

  @GetMapping
  public UiSettingsView read() {
    return toView(uiSettingsDao.read());
  }

  @GetMapping("/branding")
  public BrandingView branding() {
    UiSettingsRecord record = uiSettingsDao.read();
    return new BrandingView(
        record.productName(),
        record.productSubtitle(),
        record.logoText(),
        record.logoUrl(),
        record.faviconUrl());
  }

  @PutMapping
  public UiSettingsView update(@RequestBody UiSettingsDao.UiSettingsCommand command) {
    try {
      return toView(uiSettingsDao.save(command));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private static UiSettingsView toView(UiSettingsRecord record) {
    return new UiSettingsView(
        record.defaultLanguage(),
        SUPPORTED_DEFAULT_LANGUAGES,
        record.bannerEnabled(),
        record.bannerLevel(),
        record.bannerMessage(),
        record.bannerDismissible(),
        SUPPORTED_BANNER_LEVELS,
        record.productName(),
        record.productSubtitle(),
        record.logoText(),
        record.logoUrl(),
        record.faviconUrl(),
        record.updatedAt());
  }

  public record UiSettingsView(
      String defaultLanguage,
      List<String> supportedDefaultLanguages,
      boolean bannerEnabled,
      String bannerLevel,
      String bannerMessage,
      boolean bannerDismissible,
      List<String> supportedBannerLevels,
      String productName,
      String productSubtitle,
      String logoText,
      String logoUrl,
      String faviconUrl,
      Instant updatedAt) {
  }

  public record BrandingView(
      String productName,
      String productSubtitle,
      String logoText,
      String logoUrl,
      String faviconUrl) {
  }
}
