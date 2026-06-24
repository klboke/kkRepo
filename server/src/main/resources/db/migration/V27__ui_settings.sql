CREATE TABLE ui_settings (
  id TINYINT UNSIGNED NOT NULL,
  default_language VARCHAR(20) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT ck_ui_settings_singleton CHECK (id = 1),
  CONSTRAINT ck_ui_settings_default_language CHECK (default_language IN ('browser', 'zh-CN', 'en'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ui_settings (id, default_language)
VALUES (1, 'en');
