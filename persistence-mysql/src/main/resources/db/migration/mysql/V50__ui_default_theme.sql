ALTER TABLE ui_settings
  ADD COLUMN default_theme VARCHAR(64) NOT NULL DEFAULT 'default' AFTER default_language;
