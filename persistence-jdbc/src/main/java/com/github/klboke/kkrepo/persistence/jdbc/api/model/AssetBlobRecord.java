package com.github.klboke.kkrepo.persistence.jdbc.api.model;

import java.time.Instant;
import java.util.Map;

public record AssetBlobRecord(
    Long id,
    long blobStoreId,
    String blobRef,
    byte[] blobRefHash,
    String objectKey,
    byte[] objectKeyHash,
    String sha1,
    String sha256,
    String md5,
    long size,
    String contentType,
    String createdBy,
    String createdByIp,
    Instant blobCreatedAt,
    Instant blobUpdatedAt,
    Map<String, Object> attributes) {
  public AssetBlobRecord withId(long id) {
    return new AssetBlobRecord(id, blobStoreId, blobRef, blobRefHash, objectKey, objectKeyHash,
        sha1, sha256, md5, size, contentType, createdBy, createdByIp, blobCreatedAt,
        blobUpdatedAt, attributes);
  }

  public AssetBlobRecord withAttributes(Map<String, Object> attributes) {
    return new AssetBlobRecord(id, blobStoreId, blobRef, blobRefHash, objectKey, objectKeyHash,
        sha1, sha256, md5, size, contentType, createdBy, createdByIp, blobCreatedAt,
        blobUpdatedAt, attributes);
  }
}
