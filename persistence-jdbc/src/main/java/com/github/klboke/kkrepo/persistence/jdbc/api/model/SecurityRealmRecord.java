package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.util.Map;

public record SecurityRealmRecord(
    Long id,
    String realmId,
    String type,
    String name,
    boolean enabled,
    int priority,
    Map<String, Object> attributes) {
}
