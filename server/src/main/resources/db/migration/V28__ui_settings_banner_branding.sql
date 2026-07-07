-- Add banner message and branding columns to ui_settings.
ALTER TABLE ui_settings
  ADD COLUMN banner_enabled TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN banner_level VARCHAR(20) NOT NULL DEFAULT 'info',
  ADD COLUMN banner_message TEXT NULL,
  ADD COLUMN banner_dismissible TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN product_name VARCHAR(200) NOT NULL DEFAULT 'kkrepo',
  ADD COLUMN product_subtitle VARCHAR(200) NOT NULL DEFAULT 'Repository Manager',
  ADD COLUMN logo_text VARCHAR(50) NOT NULL DEFAULT 'KL',
  ADD COLUMN logo_url VARCHAR(512) NULL,
  ADD COLUMN favicon_url VARCHAR(512) NULL;
