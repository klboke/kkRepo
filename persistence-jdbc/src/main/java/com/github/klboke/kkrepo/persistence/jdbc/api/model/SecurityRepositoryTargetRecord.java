package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.util.Map;

public record SecurityRepositoryTargetRecord(
    Long id,
    String targetId,
    String name,
    String format,
    String contentExpression,
    Map<String, Object> pathPatterns,
    Map<String, Object> attributes) {
}
