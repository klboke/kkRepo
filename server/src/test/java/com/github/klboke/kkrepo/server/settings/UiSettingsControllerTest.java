package com.github.klboke.kkrepo.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.persistence.jdbc.api.UiSettingsDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.UiSettingsRecord;
import com.github.klboke.kkrepo.server.support.dao.UiSettingsDaoAdapter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UiSettingsControllerTest {
  @Test
  void readsCurrentUiDefaults() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("zh-CN", "default", Instant.EPOCH));
    UiSettingsController controller = new UiSettingsController(dao);

    UiSettingsController.UiSettingsView view = controller.read();

    assertEquals("zh-CN", view.defaultLanguage());
    assertEquals("default", view.defaultTheme());
    assertEquals(Instant.EPOCH, view.updatedAt());
    assertEquals(java.util.List.of("browser", "zh-CN", "en"), view.supportedDefaultLanguages());
    assertEquals(java.util.List.of("default", "indigo"), view.supportedDefaultThemes());
  }

  @Test
  void updatesUiDefaults() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("browser", "default", null));
    UiSettingsController controller = new UiSettingsController(dao);

    UiSettingsController.UiSettingsView view =
        controller.update(new UiSettingsController.UiSettingsCommand("en", "default"));

    assertEquals("en", dao.savedDefaultLanguage);
    assertEquals("default", dao.savedDefaultTheme);
    assertEquals("en", view.defaultLanguage());
    assertEquals("default", view.defaultTheme());
  }

  @Test
  void preservesThemeWhenLegacyClientOnlyUpdatesLanguage() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("browser", "default", null));
    UiSettingsController controller = new UiSettingsController(dao);

    controller.update(new UiSettingsController.UiSettingsCommand("zh-CN", null));

    assertEquals("zh-CN", dao.savedDefaultLanguage);
    assertEquals("default", dao.savedDefaultTheme);
  }

  @Test
  void preservesLanguageWhenOnlyThemeIsUpdated() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("browser", "default", null));
    UiSettingsController controller = new UiSettingsController(dao);

    controller.update(new UiSettingsController.UiSettingsCommand(null, "indigo"));

    assertEquals("browser", dao.savedDefaultLanguage);
    assertEquals("indigo", dao.savedDefaultTheme);
  }

  @Test
  void mapsUnsupportedLanguageToBadRequest() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("browser", "default", null));
    UiSettingsController controller = new UiSettingsController(dao);

    assertThrows(
        ResponseStatusException.class,
        () -> controller.update(new UiSettingsController.UiSettingsCommand("fr", "default")));
  }

  @Test
  void mapsUnregisteredThemeToBadRequest() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("browser", "default", null));
    UiSettingsController controller = new UiSettingsController(dao);

    assertThrows(
        ResponseStatusException.class,
        () -> controller.update(new UiSettingsController.UiSettingsCommand("en", "midnight")));
  }

  @Test
  void mapsInvalidThemeIdentifierToBadRequest() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("browser", "default", null));
    UiSettingsController controller = new UiSettingsController(dao);

    assertThrows(
        ResponseStatusException.class,
        () -> controller.update(new UiSettingsController.UiSettingsCommand("en", "../admin")));
  }

  @Test
  void fallsBackWhenAStoredThemeIsNoLongerRegistered() {
    RecordingUiSettingsDao dao =
        new RecordingUiSettingsDao(new UiSettingsRecord("en", "retired-theme", null));
    UiSettingsController controller = new UiSettingsController(dao);

    assertEquals("default", controller.read().defaultTheme());
  }

  private static final class RecordingUiSettingsDao extends UiSettingsDaoAdapter {
    private UiSettingsRecord record;
    private String savedDefaultLanguage;
    private String savedDefaultTheme;

    private RecordingUiSettingsDao(UiSettingsRecord record) {
      super(null);
      this.record = record;
    }

    @Override
    public UiSettingsRecord read() {
      return record;
    }

    @Override
    public UiSettingsRecord save(String defaultLanguage, String defaultTheme) {
      savedDefaultLanguage = UiSettingsDao.normalizeDefaultLanguage(defaultLanguage);
      savedDefaultTheme = UiSettingsDao.normalizeDefaultTheme(defaultTheme);
      record = new UiSettingsRecord(savedDefaultLanguage, savedDefaultTheme, Instant.EPOCH);
      return record;
    }
  }
}
