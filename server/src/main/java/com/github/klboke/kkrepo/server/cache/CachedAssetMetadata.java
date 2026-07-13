package com.github.klboke.kkrepo.server.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import java.time.Instant;
import java.util.Map;

/**
 * Hot-path snapshot of an {@link AssetRecord} joined with its {@link AssetBlobRecord}. Contains
 * everything any download/HEAD/conditional-GET path needs, so a single cache hit replaces the two
 * MySQL point queries the hosted/proxy services would otherwise issue.
 *
 * <p>{@code lastDownloadedAt} is intentionally omitted: it is not used on the read path and its
 * write would force a cache eviction on every GET.
 */
public record CachedAssetMetadata(
    @JsonProperty("assetId") long assetId,
    @JsonProperty("repositoryId") long repositoryId,
    @JsonProperty("componentId") Long componentId,
    @JsonProperty("blobId") Long blobId,
    @JsonProperty("format") RepositoryFormat format,
    @JsonProperty("path") String path,
    @JsonProperty("name") String name,
    @JsonProperty("kind") String kind,
    @JsonProperty("contentType") String contentType,
    @JsonProperty("size") Long size,
    @JsonProperty("lastUpdatedAt") Instant lastUpdatedAt,
    @JsonProperty("attributes") Map<String, Object> attributes,
    @JsonProperty("blob") CachedBlob blob) {

  @JsonCreator
  public CachedAssetMetadata {
  }

  public static CachedAssetMetadata of(AssetRecord asset, AssetBlobRecord blob) {
    return new CachedAssetMetadata(
        asset.id(),
        asset.repositoryId(),
        asset.componentId(),
        asset.assetBlobId(),
        asset.format(),
        asset.path(),
        asset.name(),
        asset.kind(),
        asset.contentType(),
        asset.size(),
        asset.lastUpdatedAt(),
        asset.attributes(),
        blob == null ? null : CachedBlob.of(blob));
  }

  /** Reconstructs the {@link AssetRecord} for callers that still want the old surface. */
  public AssetRecord toAssetRecord() {
    return new AssetRecord(
        assetId,
        repositoryId,
        componentId,
        blobId,
        format,
        path,
        null,
        name,
        kind,
        contentType,
        size,
        null,
        lastUpdatedAt,
        attributes);
  }

  public AssetBlobRecord toBlobRecord() {
    return blob == null ? null : blob.toBlobRecord();
  }

  public CachedAssetMetadata withLastUpdatedAt(Instant updatedAt) {
    return new CachedAssetMetadata(
        assetId, repositoryId, componentId, blobId, format, path, name, kind, contentType,
        size, updatedAt, attributes, blob);
  }

  public CachedAssetMetadata withLastUpdatedAtAndAttributes(Instant updatedAt, Map<String, Object> updatedAttributes) {
    return new CachedAssetMetadata(
        assetId, repositoryId, componentId, blobId, format, path, name, kind, contentType,
        size, updatedAt, updatedAttributes, blob);
  }

  public record CachedBlob(
      @JsonProperty("id") long id,
      @JsonProperty("blobStoreId") long blobStoreId,
      @JsonProperty("blobRef") String blobRef,
      @JsonProperty("objectKey") String objectKey,
      @JsonProperty("sha1") String sha1,
      @JsonProperty("sha256") String sha256,
      @JsonProperty("md5") String md5,
      @JsonProperty("size") long size,
      @JsonProperty("contentType") String contentType,
      @JsonProperty("createdBy") String createdBy,
      @JsonProperty("createdByIp") String createdByIp,
      @JsonProperty("blobCreatedAt") Instant blobCreatedAt,
      @JsonProperty("blobUpdatedAt") Instant blobUpdatedAt,
      @JsonProperty("attributes") Map<String, Object> attributes) {

    @JsonCreator
    public CachedBlob {
    }

    static CachedBlob of(AssetBlobRecord blob) {
      return new CachedBlob(
          blob.id(),
          blob.blobStoreId(),
          blob.blobRef(),
          blob.objectKey(),
          blob.sha1(),
          blob.sha256(),
          blob.md5(),
          blob.size(),
          blob.contentType(),
          blob.createdBy(),
          blob.createdByIp(),
          blob.blobCreatedAt(),
          blob.blobUpdatedAt(),
          blob.attributes());
    }

    AssetBlobRecord toBlobRecord() {
      return new AssetBlobRecord(
          id, blobStoreId, blobRef, null, objectKey, null,
          sha1, sha256, md5, size, contentType, createdBy, createdByIp,
          blobCreatedAt, blobUpdatedAt, attributes);
    }
  }
}
