package com.github.klboke.kkrepo.persistence.mysql.model;

import java.time.Instant;

public record UiSettingsRecord(
    String defaultLanguage,
    Instant updatedAt) {
}
