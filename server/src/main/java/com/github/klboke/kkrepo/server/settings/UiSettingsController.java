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
      return toView(uiSettingsDao.saveDefaultLanguage(command == null ? null : command.defaultLanguage()));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private static UiSettingsView toView(UiSettingsRecord record) {
    return new UiSettingsView(
        record.defaultLanguage(),
        SUPPORTED_DEFAULT_LANGUAGES,
        record.updatedAt());
  }

  public record UiSettingsCommand(String defaultLanguage) {
  }

  public record UiSettingsView(
      String defaultLanguage,
      List<String> supportedDefaultLanguages,
      Instant updatedAt) {
  }
}
