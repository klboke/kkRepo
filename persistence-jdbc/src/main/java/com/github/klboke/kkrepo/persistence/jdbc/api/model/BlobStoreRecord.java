package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.util.Map;

public record BlobStoreRecord(
    Long id,
    String name,
    String type,
    String endpoint,
    String region,
    String bucket,
    String prefix,
    Map<String, Object> attributes) {
}
