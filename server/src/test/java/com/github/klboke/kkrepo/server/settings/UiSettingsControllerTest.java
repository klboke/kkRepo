package com.github.klboke.kkrepo.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.persistence.mysql.dao.UiSettingsDao;
import com.github.klboke.kkrepo.persistence.mysql.model.UiSettingsRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UiSettingsControllerTest {
  @Test
  void readsCurrentDefaultLanguage() {
    RecordingUiSettingsDao dao = new RecordingUiSettingsDao(new UiSettingsRecord("zh-CN", Instant.EPOCH));
    UiSettingsController controller = new UiSettingsController(dao);

    UiSettingsController.UiSettingsView view = controller.read();

    assertEquals("zh-CN", view.defaultLanguage());
    assertEquals(Instant.EPOCH, view.updatedAt());
    assertEquals(java.util.List.of("browser", "zh-CN", "en"), view.supportedDefaultLanguages());
  }

  @Test
  void updatesDefaultLanguage() {
    RecordingUiSettingsDao dao = new RecordingUiSettingsDao(new UiSettingsRecord("browser", null));
    UiSettingsController controller = new UiSettingsController(dao);

    UiSettingsController.UiSettingsView view =
        controller.update(new UiSettingsController.UiSettingsCommand("en"));

    assertEquals("en", dao.savedDefaultLanguage);
    assertEquals("en", view.defaultLanguage());
  }

  @Test
  void mapsUnsupportedLanguageToBadRequest() {
    RecordingUiSettingsDao dao = new RecordingUiSettingsDao(new UiSettingsRecord("browser", null));
    UiSettingsController controller = new UiSettingsController(dao);

    assertThrows(
        ResponseStatusException.class,
        () -> controller.update(new UiSettingsController.UiSettingsCommand("fr")));
  }

  private static final class RecordingUiSettingsDao extends UiSettingsDao {
    private UiSettingsRecord record;
    private String savedDefaultLanguage;

    private RecordingUiSettingsDao(UiSettingsRecord record) {
      super(null);
      this.record = record;
    }

    @Override
    public UiSettingsRecord read() {
      return record;
    }

    @Override
    public UiSettingsRecord saveDefaultLanguage(String defaultLanguage) {
      savedDefaultLanguage = UiSettingsDao.normalizeDefaultLanguage(defaultLanguage);
      record = new UiSettingsRecord(savedDefaultLanguage, Instant.EPOCH);
      return record;
    }
  }
}
