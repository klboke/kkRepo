-- Allow the UI languages added to login/assets/ui-i18n.js (UiSettingsDao.LANGUAGES).
ALTER TABLE ui_settings DROP CONSTRAINT IF EXISTS ck_ui_settings_default_language;
ALTER TABLE ui_settings ADD CONSTRAINT ck_ui_settings_default_language
  CHECK (default_language IN ('browser', 'en', 'zh-CN', 'fr', 'de', 'es', 'it', 'pt-BR', 'nl', 'pl', 'ru', 'uk', 'ja', 'ko', 'tr'));
