package com.github.klboke.kkrepo.server.raw;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RawHostedService {
  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final RawAssetWriter writer;
  private final RawAssetReader reader;
  private final AssetMetadataCache assetMetadataCache;

  public RawHostedService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      RawAssetWriter writer,
      RawAssetReader reader,
      AssetMetadataCache assetMetadataCache) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.assetMetadataCache = assetMetadataCache;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    ensureHosted(runtime);
    if (isDirectoryRequest(rawPath)) {
      return getIndex(runtime, rawPath, headOnly);
    }
    String path = normalizeAssetPath(rawPath);
    CachedAssetMetadata snapshot = lookupCached(runtime, path)
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    return reader.serveSnapshot(snapshot, headOnly, path, runtime.rawContentDispositionOrDefault());
  }

  Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  public MavenResponse put(RepositoryRuntime runtime, String rawPath, InputStream body,
      String contentType, String createdBy, String createdByIp) {
    return put(runtime, rawPath, body, contentType, Map.of(), createdBy, createdByIp, true);
  }

  public MavenResponse putWithAttributes(RepositoryRuntime runtime, String rawPath, InputStream body,
      String contentType, Map<String, ?> blobAttributes, String createdBy, String createdByIp) {
    return put(runtime, rawPath, body, contentType, blobAttributes, createdBy, createdByIp, true);
  }

  public MavenResponse putGenerated(RepositoryRuntime runtime, String rawPath, InputStream body,
      String contentType, String createdBy, String createdByIp) {
    return put(runtime, rawPath, body, contentType, Map.of(), createdBy, createdByIp, false);
  }

  /**
   * Stores generated protocol content for any repository format. The caller owns format/type
   * validation; this exists so protocol implementations can reuse the distributed blob/metadata
   * transaction without pretending their content is a Raw repository.
   */
  public MavenResponse putInternal(RepositoryRuntime runtime, String rawPath, InputStream body,
      String contentType, Map<String, ?> blobAttributes, String createdBy, String createdByIp) {
    String path = normalizeAssetPath(rawPath);
    writer.write(runtime, blobStorage(runtime), requireBlobStore(runtime), path, body, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp);
    return MavenResponse.created();
  }

  public MavenResponse putInternalUnindexed(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      Map<String, ?> blobAttributes,
      String createdBy,
      String createdByIp) {
    String path = normalizeAssetPath(rawPath);
    writer.writeUnindexed(
        runtime, blobStorage(runtime), requireBlobStore(runtime), path, body, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp, false);
    return MavenResponse.created();
  }

  public MavenResponse putInternalWithComponent(
      RepositoryRuntime runtime,
      String rawPath,
      InputStream body,
      String contentType,
      Map<String, ?> blobAttributes,
      String createdBy,
      String createdByIp,
      ComponentRecord component) {
    String path = normalizeAssetPath(rawPath);
    writer.writeWithComponent(
        runtime, blobStorage(runtime), requireBlobStore(runtime), path, body, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp, component, false);
    return MavenResponse.created();
  }

  public MavenResponse putInternalUnindexedFile(
      RepositoryRuntime runtime,
      String rawPath,
      Path file,
      String contentType,
      Map<String, ?> blobAttributes,
      String createdBy,
      String createdByIp) {
    String path = normalizeAssetPath(rawPath);
    writer.writeFile(
        runtime, blobStorage(runtime), requireBlobStore(runtime), path, file, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp, null);
    return MavenResponse.created();
  }

  public MavenResponse putInternalWithComponentFile(
      RepositoryRuntime runtime,
      String rawPath,
      Path file,
      String contentType,
      Map<String, ?> blobAttributes,
      String createdBy,
      String createdByIp,
      ComponentRecord component) {
    String path = normalizeAssetPath(rawPath);
    writer.writeFile(
        runtime, blobStorage(runtime), requireBlobStore(runtime), path, file, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp, component);
    return MavenResponse.created();
  }

  /**
   * Stores protocol content at its wire path while exposing a separate logical browse path.
   * The asset has exactly one browse leaf, matching the browse schema's unique asset binding.
   */
  public MavenResponse putInternalWithComponentFileAtBrowsePath(
      RepositoryRuntime runtime,
      String rawPath,
      Path file,
      String contentType,
      Map<String, ?> blobAttributes,
      String createdBy,
      String createdByIp,
      ComponentRecord component,
      String rawBrowsePath) {
    String path = normalizeAssetPath(rawPath);
    String browsePath = normalizeAssetPath(rawBrowsePath);
    writer.writeFileAtBrowsePath(
        runtime, blobStorage(runtime), requireBlobStore(runtime), path, file, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp,
        component, browsePath);
    return MavenResponse.created();
  }

  /**
   * Stores an immutable protocol asset and returns whether this call created the path binding.
   * If another replica already owns or concurrently wins the path, its asset/blob binding is
   * returned unchanged by the writer and this method reports {@code false}.
   */
  public boolean putInternalWithComponentFileAtBrowsePathIfAbsent(
      RepositoryRuntime runtime,
      String rawPath,
      Path file,
      String contentType,
      Map<String, ?> blobAttributes,
      String createdBy,
      String createdByIp,
      ComponentRecord component,
      String rawBrowsePath) {
    String path = normalizeAssetPath(rawPath);
    String browsePath = normalizeAssetPath(rawBrowsePath);
    return writer.writeFileAtBrowsePathIfAbsent(
        runtime, blobStorage(runtime), requireBlobStore(runtime), path, file, contentType,
        blobAttributes == null ? Map.of() : blobAttributes, createdBy, createdByIp,
        component, browsePath).created();
  }

  /** Deletes protocol-generated content without applying Raw repository type checks. */
  public MavenResponse deleteInternal(RepositoryRuntime runtime, String rawPath) {
    String path = normalizeAssetPath(rawPath);
    int deleted = writer.deleteAsset(runtime, blobStorage(runtime), path);
    return deleted == 0 ? MavenResponse.noBody(404) : MavenResponse.noBody(204);
  }

  private MavenResponse put(RepositoryRuntime runtime, String rawPath, InputStream body,
      String contentType, Map<String, ?> blobAttributes,
      String createdBy, String createdByIp, boolean enforcePolicy) {
    ensureHosted(runtime);
    String path = normalizeAssetPath(rawPath);
    if (enforcePolicy) {
      enforceWritePolicy(runtime, path);
    }
    BlobStorage storage = blobStorage(runtime);
    writer.write(
        runtime,
        storage,
        requireBlobStore(runtime),
        path,
        body,
        contentType,
        blobAttributes == null ? Map.of() : blobAttributes,
        createdBy,
        createdByIp);
    return MavenResponse.created();
  }

  public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
    ensureHosted(runtime);
    String path = normalizeAssetPath(rawPath);
    BlobStorage storage = blobStorage(runtime);
    int deleted = writer.deleteAsset(runtime, storage, path);
    return deleted == 0 ? MavenResponse.noBody(404) : MavenResponse.noBody(204);
  }

  private MavenResponse getIndex(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    String base = normalizeDirectoryPath(rawPath);
    for (String candidate : indexCandidates(base)) {
      Optional<CachedAssetMetadata> snapshot = lookupCached(runtime, candidate);
      if (snapshot.isPresent()) {
        return reader.serveSnapshot(snapshot.get(), headOnly, candidate, runtime.rawContentDispositionOrDefault());
      }
    }
    throw new MavenExceptions.MavenNotFoundException("You can't browse this way");
  }

  private void enforceWritePolicy(RepositoryRuntime runtime, String path) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new MavenExceptions.WritePolicyDenied("Write policy DENY forbids writing " + path);
    }
    if (policy == WritePolicy.ALLOW_ONCE && assetDao.findAssetByPath(runtime.id(), path).isPresent()) {
      throw new MavenExceptions.WritePolicyDenied("Write policy ALLOW_ONCE forbids overwriting " + path);
    }
  }

  private void ensureHosted(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on hosted raw repositories");
    }
  }

  BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Raw repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  static boolean isDirectoryRequest(String rawPath) {
    return rawPath == null || rawPath.isBlank() || rawPath.endsWith("/");
  }

  static String[] indexCandidates(String directory) {
    return directory.isEmpty()
        ? new String[] {"index.html", "index.htm"}
        : new String[] {directory + "/index.html", directory + "/index.htm"};
  }

  static String normalizeAssetPath(String rawPath) {
    String path = normalizeDirectoryPath(rawPath);
    if (path.isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException("Missing raw path");
    }
    return path;
  }

  static String normalizeDirectoryPath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim().replaceAll("/+", "/");
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    return path;
  }
}
