package com.github.klboke.kkrepo.server.settings;

import com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.UiSettingsRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private static final List<String> SUPPORTED_DEFAULT_THEMES = List.of(
      UiSettingsDao.THEME_DEFAULT,
      UiSettingsDao.THEME_INDIGO);

  private final UiSettingsDao uiSettingsDao;

  public UiSettingsController(UiSettingsDao uiSettingsDao) {
    this.uiSettingsDao = uiSettingsDao;
  }

  @GetMapping
  public UiSettingsView read() {
    return toView(uiSettingsDao.read());
  }

  @PutMapping
  public UiSettingsView update(@RequestBody UiSettingsCommand command) {
    try {
      UiSettingsRecord current = uiSettingsDao.read();
      String defaultLanguage = command == null || command.defaultLanguage() == null
          ? current.defaultLanguage()
          : command.defaultLanguage();
      String defaultTheme = command == null || command.defaultTheme() == null
          ? current.defaultTheme()
          : command.defaultTheme();
      String normalizedTheme = UiSettingsDao.normalizeDefaultTheme(defaultTheme);
      if (!SUPPORTED_DEFAULT_THEMES.contains(normalizedTheme)) {
        throw new IllegalArgumentException("Unsupported UI default theme: " + defaultTheme);
      }
      return toView(uiSettingsDao.save(defaultLanguage, normalizedTheme));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private static UiSettingsView toView(UiSettingsRecord record) {
    String defaultTheme = SUPPORTED_DEFAULT_THEMES.contains(record.defaultTheme())
        ? record.defaultTheme()
        : UiSettingsDao.DEFAULT_THEME;
    return new UiSettingsView(
        record.defaultLanguage(),
        SUPPORTED_DEFAULT_LANGUAGES,
        defaultTheme,
        SUPPORTED_DEFAULT_THEMES,
        record.updatedAt());
  }

  public record UiSettingsCommand(String defaultLanguage, String defaultTheme) {
  }

  public record UiSettingsView(
      String defaultLanguage,
      List<String> supportedDefaultLanguages,
      String defaultTheme,
      List<String> supportedDefaultThemes,
      Instant updatedAt) {
  }
}
