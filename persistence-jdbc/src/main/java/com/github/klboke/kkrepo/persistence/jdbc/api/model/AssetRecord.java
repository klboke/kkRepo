package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.time.Instant;
import java.util.Map;

public record AssetRecord(
    Long id,
    long repositoryId,
    Long componentId,
    Long assetBlobId,
    RepositoryFormat format,
    String path,
    byte[] pathHash,
    String name,
    String kind,
    String contentType,
    Long size,
    Instant lastDownloadedAt,
    Instant lastUpdatedAt,
    Map<String, Object> attributes) {
}
