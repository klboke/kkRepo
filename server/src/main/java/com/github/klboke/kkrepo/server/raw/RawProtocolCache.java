package com.github.klboke.kkrepo.server.raw;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Public protocol-facing facade over the shared immutable asset/blob cache implementation. */
@Component
public final class RawProtocolCache {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final RawAssetWriter writer;
  private final RawAssetReader reader;
  private final AssetMetadataCache metadataCache;

  public RawProtocolCache(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      RawAssetWriter writer,
      RawAssetReader reader,
      AssetMetadataCache metadataCache) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.metadataCache = metadataCache;
  }

  public Optional<CachedAssetMetadata> find(RepositoryRuntime runtime, String path) {
    return metadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  public MavenResponse serve(
      RepositoryRuntime runtime, String path, boolean headOnly, String contentDisposition) {
    CachedAssetMetadata cached = find(runtime, path)
        .orElseThrow(() -> new IllegalStateException("Published protocol asset is missing: " + path));
    return reader.serveSnapshot(cached, headOnly, path, contentDisposition);
  }

  public StoredAsset storeHidden(
      RepositoryRuntime runtime,
      String path,
      InputStream body,
      String contentType,
      Map<String, ?> attributes) {
    RawAssetWriter.Stored stored = writer.writeHidden(
        runtime, storage(runtime), blobStoreId(runtime), path, body, contentType,
        attributes, "proxy", null, false);
    return stored(stored);
  }

  public StoredAsset storeVerifiedImmutable(
      RepositoryRuntime runtime,
      String path,
      InputStream body,
      String contentType,
      Map<String, ?> attributes,
      ComponentRecord component,
      String browsePath,
      Long expectedSize,
      String expectedSha256,
      String expectedGitOid) {
    RawAssetWriter.Stored stored = writer.writeVerifiedWithComponentAtBrowsePathIfAbsent(
        runtime, storage(runtime), blobStoreId(runtime), path, body, contentType, Map.of(),
        assetAttributes(path, attributes), "proxy", null, component, browsePath,
        expectedSize, expectedSha256,
        expectedGitOid);
    return stored(stored);
  }

  /**
   * Serves the first successful cache fill from its already-verified staging file. This avoids an
   * immediate object-store read and metadata-cache miss while preserving the normal download
   * policy check; subsequent requests use {@link #serve} and the durable blob binding.
   */
  public MavenResponse serveStored(
      RepositoryRuntime runtime,
      StoredAsset stored,
      boolean headOnly,
      String contentDisposition) {
    CachedAssetMetadata snapshot = stored.snapshot();
    if (snapshot == null || snapshot.repositoryId() != runtime.id()) {
      stored.discardBody();
      throw new IllegalArgumentException("Stored protocol asset belongs to another repository");
    }
    if (stored.responseFile() == null) {
      return reader.serveSnapshot(
          snapshot, headOnly, snapshot.path(), contentDisposition);
    }
    try {
      reader.beforeRead(stored.assetId(), stored.blobId(), runtime.id());
    } catch (RuntimeException error) {
      stored.discardBody();
      throw error;
    }
    if (headOnly) {
      stored.discardBody();
      return MavenResponse.noBody(
          200, stored.size(), stored.contentType(), snapshot.blob().sha1(),
          snapshot.lastUpdatedAt());
    }
    MavenResponse response = MavenResponse.ok(
        TempBlobFiles.openDeleteOnClose(stored.responseFile()), stored.size(),
        stored.contentType(), snapshot.blob().sha1(), snapshot.lastUpdatedAt());
    String disposition = contentDisposition == null || contentDisposition.isBlank()
        ? "attachment" : contentDisposition.toLowerCase(Locale.ROOT);
    return response.withHeader("Content-Disposition", disposition);
  }

  private StoredAsset stored(RawAssetWriter.Stored stored) {
    if (stored == null || stored.asset() == null || stored.blob() == null) {
      throw new IllegalStateException("Protocol asset did not produce a complete blob binding");
    }
    return new StoredAsset(
        stored.asset().id(), stored.asset().componentId(), stored.blob().id(),
        stored.blob().sha256(), stored.blob().size(), stored.asset().contentType(),
        CachedAssetMetadata.of(stored.asset(), stored.blob()), stored.responseFile());
  }

  private Map<String, Object> assetAttributes(String path, Map<String, ?> attributes) {
    LinkedHashMap<String, Object> assetAttributes = new LinkedHashMap<>();
    assetAttributes.put("path", path);
    if (attributes != null) {
      attributes.forEach((key, value) -> {
        if (key != null && value != null) assetAttributes.put(key, value);
      });
    }
    return Map.copyOf(assetAttributes);
  }

  private BlobStorage storage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(blobStoreId(runtime));
  }

  private static long blobStoreId(RepositoryRuntime runtime) {
    if (runtime == null || runtime.blobStoreId() == null) {
      throw new IllegalStateException("Proxy repository has no blob store assigned");
    }
    return runtime.blobStoreId();
  }

  public record StoredAsset(
      long assetId,
      Long componentId,
      long blobId,
      String sha256,
      long size,
      String contentType,
      CachedAssetMetadata snapshot,
      Path responseFile) {
    public StoredAsset(
        long assetId,
        Long componentId,
        long blobId,
        String sha256,
        long size,
        String contentType) {
      this(assetId, componentId, blobId, sha256, size, contentType, null, null);
    }

    public void discardBody() {
      TempBlobFiles.deleteQuietly(responseFile);
    }
  }
}
