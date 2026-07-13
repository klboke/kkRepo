package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.util.Map;

public record SecurityPrivilegeRecord(
    String privilegeId,
    String name,
    String description,
    String type,
    boolean readOnly,
    Map<String, Object> properties) {
}
