package com.github.klboke.kkrepo.persistence.mysql.model;

import java.time.Instant;

public record UiSettingsRecord(
    String defaultLanguage,
    boolean bannerEnabled,
    String bannerLevel,
    String bannerMessage,
    boolean bannerDismissible,
    String productName,
    String productSubtitle,
    String logoText,
    String logoUrl,
    String faviconUrl,
    Instant updatedAt) {
}
