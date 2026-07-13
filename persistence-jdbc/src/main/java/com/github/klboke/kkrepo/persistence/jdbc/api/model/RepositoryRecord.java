package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import java.util.Map;

public record RepositoryRecord(
    Long id,
    String name,
    RepositoryFormat format,
    RepositoryType type,
    String recipeName,
    boolean online,
    Long blobStoreId,
    Long routingRuleId,
    String proxyRemoteUrl,
    String versionPolicy,
    String layoutPolicy,
    String writePolicy,
    boolean strictContentTypeValidation,
    Map<String, Object> attributes) {
}
