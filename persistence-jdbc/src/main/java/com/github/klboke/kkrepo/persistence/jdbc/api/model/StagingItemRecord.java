package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import java.time.Instant;
import java.util.Map;

/**
 * One staged logical change. Staged content is isolated from the live repository tables until
 * promotion; {@code assetBlobId} points at a content-addressed blob retained through
 * {@code blob_reference} so garbage collection cannot reclaim it while the session is open.
 */
public record StagingItemRecord(
    Long id,
    long sessionId,
    long repositoryId,
    RepositoryFormat format,
    String logicalPath,
    byte[] logicalPathHash,
    String action,
    String status,
    Long blobStoreId,
    Long assetBlobId,
    String contentType,
    Long size,
    String sha256,
    String md5,
    String sha1,
    String sourcePath,
    String expectedSha256,
    Long expectedSize,
    Map<String, Object> attributes,
    Instant createdAt,
    Instant updatedAt) {
}
