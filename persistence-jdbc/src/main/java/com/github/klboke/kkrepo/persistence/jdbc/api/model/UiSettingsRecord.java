package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;

public record UiSettingsRecord(
    String defaultLanguage,
    String defaultTheme,
    Instant updatedAt) {
}
