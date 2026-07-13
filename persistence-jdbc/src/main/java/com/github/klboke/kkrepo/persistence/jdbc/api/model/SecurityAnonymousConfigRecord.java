package com.github.klboke.kkrepo.persistence.jdbc.api.model;

public record SecurityAnonymousConfigRecord(
    boolean enabled,
    String userSource,
    String userId,
    String realmName) {
}
