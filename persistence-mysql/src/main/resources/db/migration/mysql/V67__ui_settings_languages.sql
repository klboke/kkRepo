-- Allow the UI languages added to login/assets/ui-i18n.js (UiSettingsDao.LANGUAGES).
-- MySQL before 8.0.16 parsed but ignored CHECK constraints, so the old constraint may be missing:
-- drop it only when present. Restart-safe: rerunning drops and recreates the same constraint.
SET @kkrepo_ui_language_check_sql = (
  SELECT IF(COUNT(*) > 0,
    'ALTER TABLE ui_settings DROP CHECK ck_ui_settings_default_language',
    'SELECT 1')
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE() AND table_name = 'ui_settings'
    AND constraint_name = 'ck_ui_settings_default_language' AND constraint_type = 'CHECK'
);
PREPARE kkrepo_ui_language_check_statement FROM @kkrepo_ui_language_check_sql;
EXECUTE kkrepo_ui_language_check_statement;
DEALLOCATE PREPARE kkrepo_ui_language_check_statement;

ALTER TABLE ui_settings ADD CONSTRAINT ck_ui_settings_default_language
  CHECK (default_language IN ('browser', 'en', 'zh-CN', 'fr', 'de', 'es', 'it', 'pt-BR', 'nl', 'pl', 'ru', 'uk', 'ja', 'ko', 'tr'));
