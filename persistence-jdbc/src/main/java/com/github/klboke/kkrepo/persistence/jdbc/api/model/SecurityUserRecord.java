package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.util.Map;

public record SecurityUserRecord(
    Long id,
    String source,
    String userId,
    String firstName,
    String lastName,
    String email,
    String passwordHash,
    String status,
    String externalId,
    Map<String, Object> attributes) {
}
