package com.github.klboke.kkrepo.server.swift;

import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Shared OSS/S3-backed asset access for hosted and materialized GitHub releases. */
@Component
final class SwiftAssetSupport {
  private final AssetDao assets;
  private final BlobStorageRegistry storages;
  private final RawHostedService hosted;

  SwiftAssetSupport(AssetDao assets, BlobStorageRegistry storages, RawHostedService hosted) {
    this.assets = assets;
    this.storages = storages;
    this.hosted = hosted;
  }

  void storeFile(
      RepositoryRuntime runtime,
      String path,
      Path file,
      String contentType,
      Map<String, ?> attributes,
      String actor,
      String ip) {
    hosted.putInternalUnindexedFile(runtime, path, file, contentType, attributes, actor, ip);
  }

  void storeBytes(
      RepositoryRuntime runtime,
      String path,
      byte[] bytes,
      String contentType,
      Map<String, ?> attributes,
      String actor,
      String ip) {
    hosted.putInternalUnindexed(
        runtime, path, new ByteArrayInputStream(bytes), contentType, attributes, actor, ip);
  }

  AssetRecord stageFile(
      RepositoryRuntime runtime,
      String logicalPath,
      Path file,
      String contentType,
      Map<String, ?> attributes,
      String actor,
      String ip) {
    String stagingPath = stagingPath(logicalPath);
    hosted.putInternalUnindexedFile(
        runtime, stagingPath, file, contentType, attributes, actor, ip);
    return markStaged(runtime, stagingPath, logicalPath, attributes);
  }

  AssetRecord stageBytes(
      RepositoryRuntime runtime,
      String logicalPath,
      byte[] bytes,
      String contentType,
      Map<String, ?> attributes,
      String actor,
      String ip) {
    String stagingPath = stagingPath(logicalPath);
    hosted.putInternalUnindexed(
        runtime,
        stagingPath,
        new ByteArrayInputStream(bytes),
        contentType,
        attributes,
        actor,
        ip);
    return markStaged(runtime, stagingPath, logicalPath, attributes);
  }

  Optional<AssetRecord> find(RepositoryRuntime runtime, String path) {
    return assets.findAssetByPath(runtime.id(), path);
  }

  Optional<AssetRecord> find(long repositoryId, long assetId) {
    return assets.findAssetById(assetId).filter(asset -> asset.repositoryId() == repositoryId);
  }

  AssetRecord required(long repositoryId, long assetId) {
    return find(repositoryId, assetId)
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release asset is missing"));
  }

  AssetBlobRecord requiredBlob(AssetRecord asset) {
    if (asset.assetBlobId() == null) {
      throw new SwiftExceptions.NotFound("Swift release blob is missing");
    }
    return assets.findBlobById(asset.assetBlobId())
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release blob is missing"));
  }

  byte[] bytes(long repositoryId, long assetId) {
    AssetDao.AssetWithBlob stored = assets.findAssetWithBlobById(assetId)
        .filter(value -> value.asset().repositoryId() == repositoryId)
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release asset is missing"));
    AssetBlobRecord blob = stored.blob();
    if (blob == null) {
      throw new SwiftExceptions.NotFound("Swift release blob is missing");
    }
    try (InputStream input = open(blob)) {
      return input.readAllBytes();
    } catch (IOException e) {
      throw new SwiftExceptions.BadUpstream("Failed reading Swift release asset", e);
    }
  }

  MavenResponse serve(long repositoryId, long assetId, boolean headOnly) {
    AssetDao.AssetWithBlob stored = assets.findAssetWithBlobById(assetId)
        .filter(value -> value.asset().repositoryId() == repositoryId)
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release asset is missing"));
    AssetRecord asset = stored.asset();
    AssetBlobRecord blob = stored.blob();
    if (blob == null) {
      throw new SwiftExceptions.NotFound("Swift release blob is missing");
    }
    var storage = storages.forBlobStoreId(blob.blobStoreId());
    var reference = BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size());
    if (headOnly) {
      return MavenResponse.noBody(
          200, blob.size(), asset.contentType(), blob.sha256(), asset.lastUpdatedAt());
    }
    return MavenResponse.ok(
        () -> storage.get(reference)
            .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release blob is missing")),
        blob.size(), asset.contentType(), blob.sha256(), asset.lastUpdatedAt());
  }

  void delete(RepositoryRuntime runtime, String path) {
    hosted.deleteInternal(runtime, path);
  }

  private AssetRecord markStaged(
      RepositoryRuntime runtime,
      String stagingPath,
      String logicalPath,
      Map<String, ?> attributes) {
    AssetRecord staged = find(runtime, stagingPath)
        .orElseThrow(() -> new IllegalStateException(
            "Swift staged asset was not persisted: " + stagingPath));
    LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
    if (staged.attributes() != null) {
      merged.putAll(staged.attributes());
    }
    if (attributes != null) {
      attributes.forEach((key, value) -> merged.put(key, value));
    }
    merged.put("swiftLogicalPath", logicalPath);
    Map<String, Object> immutable = Map.copyOf(merged);
    assets.updateAssetAttributes(staged.id(), immutable);
    return new AssetRecord(
        staged.id(),
        staged.repositoryId(),
        staged.componentId(),
        staged.assetBlobId(),
        staged.format(),
        staged.path(),
        staged.pathHash(),
        staged.name(),
        staged.kind(),
        staged.contentType(),
        staged.size(),
        staged.lastDownloadedAt(),
        staged.lastUpdatedAt() == null ? Instant.now() : staged.lastUpdatedAt(),
        immutable);
  }

  private static String stagingPath(String logicalPath) {
    int slash = logicalPath == null ? -1 : logicalPath.lastIndexOf('/');
    String filename = slash < 0 ? logicalPath : logicalPath.substring(slash + 1);
    if (filename == null || filename.isBlank()) {
      filename = "asset";
    }
    return ".swift/staging/" + UUID.randomUUID() + "/" + filename;
  }

  private InputStream open(AssetBlobRecord blob) {
    return storages.forBlobStoreId(blob.blobStoreId())
        .get(BlobReferenceCodec.reference(
            blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
        .orElseThrow(() -> new SwiftExceptions.NotFound("Swift release blob is missing"));
  }
}
