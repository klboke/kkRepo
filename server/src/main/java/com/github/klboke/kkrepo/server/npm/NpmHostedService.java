package com.github.klboke.kkrepo.server.npm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.npm.NpmMetadata;
import com.github.klboke.kkrepo.protocol.npm.NpmPackageId;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class NpmHostedService {
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final AssetDao assetDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final NpmAssetWriter writer;
  private final ObjectMapper mapper;
  private final AssetMetadataCache assetMetadataCache;

  private record PublishWritePlan(Optional<Map<String, Object>> existingPackageRoot, String effectiveRevision) {}

  public NpmHostedService(
      AssetDao assetDao,
      BlobStorageRegistry blobStorageRegistry,
      NpmAssetWriter writer,
      ObjectMapper mapper,
      AssetMetadataCache assetMetadataCache) {
    this.assetDao = assetDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.mapper = mapper;
    this.assetMetadataCache = assetMetadataCache;
  }

  public MavenResponse get(RepositoryRuntime runtime, NpmPath path, String repositoryBaseUrl, boolean headOnly) {
    return get(runtime, path, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  public MavenResponse get(
      RepositoryRuntime runtime,
      NpmPath path,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    if (!runtime.isHosted()) {
      throw new IllegalStateException("NpmHostedService.get called on non-hosted " + runtime.name());
    }
    if (path.isPackageMetadata()) {
      return getPackage(runtime, path.packageId(), repositoryBaseUrl, headOnly, variant);
    }
    if (path.isTarball()) {
      return getTarball(runtime, path.packageId(), path.tarballName(), headOnly);
    }
    if (path.kind() == NpmPath.Kind.DIST_TAGS) {
      return getDistTags(runtime, path.packageId(), headOnly);
    }
    throw new NpmExceptions.NpmNotFoundException(path.rawPath());
  }

  public MavenResponse putPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String revision,
      InputStream body,
      String createdBy,
      String createdByIp) {
    enforceHosted(runtime);
    try {
      Map<String, Object> incoming = mapper.readValue(body, MAP_TYPE);
      String payloadName = NpmMetadata.stringValue(incoming.get(NpmMetadata.NAME), packageId.id());
      if (!packageId.id().equals(payloadName)) {
        throw new NpmExceptions.BadRequestException(
            "Package name mismatch. Path is " + packageId.id() + " but payload is " + payloadName);
      }

      PublishWritePlan plan = packageRootWritePlan(runtime, packageId, revision, incoming);
      validateAttachmentsWrite(runtime, packageId, incoming);
      long blobStoreId = requireBlobStore(runtime);
      BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
      writeAttachments(runtime, storage, blobStoreId, packageId, incoming, createdBy, createdByIp);
      Map<String, Object> toStore = packageRootForStorage(packageId, incoming, plan);
      byte[] json = NpmResponseSupport.write(mapper, toStore);
      writer.writePackageRoot(runtime, storage, blobStoreId, packageId, json, createdBy, createdByIp);
      return NpmResponseSupport.success(mapper);
    } catch (IOException e) {
      throw new NpmExceptions.BadRequestException("Invalid npm publish JSON", e);
    }
  }

  public MavenResponse deletePackage(RepositoryRuntime runtime, NpmPackageId packageId, String revision) {
    enforceHosted(runtime);
    enforceDeletePolicy(runtime, "package");
    assertRevisionIfPresent(runtime, packageId, revision);
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    int deleted = writer.deletePackage(runtime, storage, packageId);
    if (deleted == 0) {
      throw new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found");
    }
    return NpmResponseSupport.success(mapper);
  }

  public MavenResponse uploadTarball(
      RepositoryRuntime runtime,
      MultipartFile asset,
      String createdBy,
      String createdByIp) throws IOException {
    enforceHosted(runtime);
    if (asset == null || asset.isEmpty()) {
      throw new NpmExceptions.BadRequestException("npm upload requires multipart file field 'asset'");
    }
    String tarballName = originalFilename(asset);
    if (!tarballName.endsWith(".tgz")) {
      throw new NpmExceptions.BadRequestException("npm upload only accepts .tgz package archives");
    }

    Map<String, Object> packageJson = readPackageJson(asset);
    String packageName = NpmMetadata.stringValue(packageJson.get(NpmMetadata.NAME), null);
    String version = NpmMetadata.stringValue(packageJson.get(NpmMetadata.VERSION), null);
    if (packageName == null || packageName.isBlank()) {
      throw new NpmExceptions.BadRequestException("npm package.json is missing name");
    }
    if (version == null || version.isBlank()) {
      throw new NpmExceptions.BadRequestException("npm package.json is missing version");
    }

    NpmPackageId packageId;
    try {
      packageId = NpmPackageId.parse(packageName);
    } catch (IllegalArgumentException e) {
      throw new NpmExceptions.BadRequestException(e.getMessage(), e);
    }

    Optional<Map<String, Object>> existingRoot = loadPackageRootMap(runtime, packageId);
    enforceWritePolicy(runtime, "package-root", false);
    if (existingRoot.map(root -> NpmMetadata.versions(root).containsKey(version)).orElse(false)) {
      throw new NpmExceptions.WritePolicyDenied(
          "Asset already exists: " + packageId.id() + " version " + version);
    }
    Optional<AssetRecord> existingTarball = assetDao.findAssetByPath(runtime.id(), packageId.tarballPath(tarballName));
    if (existingTarball.isPresent()) {
      throw new NpmExceptions.WritePolicyDenied(
          "Asset already exists: " + packageId.tarballPath(tarballName));
    }
    enforceWritePolicy(runtime, "tarball", false);

    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    NpmAssetWriter.Stored stored;
    try (InputStream body = asset.getInputStream()) {
      stored = writer.writeTarball(runtime, storage, blobStoreId, packageId, version, tarballName,
          body, asset.getContentType(), createdBy, createdByIp, Map.of());
    }

    Map<String, Object> packageRoot = existingRoot
        .map(NpmMetadata::deepCopy)
        .orElseGet(LinkedHashMap::new);
    packageRoot.put(NpmMetadata.NAME, packageId.id());
    Map<String, Object> versionDocument = NpmMetadata.deepCopy(packageJson);
    versionDocument.put(NpmMetadata.NAME, packageId.id());
    versionDocument.put(NpmMetadata.VERSION, version);
    Map<String, Object> dist = mapValue(versionDocument.get(NpmMetadata.DIST));
    dist.put(NpmMetadata.TARBALL, tarballName);
    dist.put(NpmMetadata.SHASUM, stored.digests().sha1());
    versionDocument.put(NpmMetadata.DIST, dist);
    NpmMetadata.versions(packageRoot).put(version, versionDocument);
    NpmMetadata.distTags(packageRoot).put("latest", version);
    NpmMetadata.prepareForStorage(
        packageRoot, packageId, NpmMetadata.nextRevision(existingRoot.orElse(null), existingRoot.isEmpty()));
    writer.writePackageRoot(runtime, storage, blobStoreId, packageId,
        NpmResponseSupport.write(mapper, packageRoot), createdBy, createdByIp);
    return MavenResponse.created();
  }

  public MavenResponse deleteTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      String revision) {
    enforceHosted(runtime);
    enforceDeletePolicy(runtime, "tarball");
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    int deleted = writer.deletePath(runtime, storage, packageId.tarballPath(tarballName));
    if (deleted == 0) {
      throw new NpmExceptions.NpmNotFoundException(
          "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found");
    }
    removeVersionForTarball(runtime, packageId, tarballName, "system", null);
    return NpmResponseSupport.success(mapper);
  }

  public MavenResponse putDistTag(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tag,
      InputStream body,
      String createdBy,
      String createdByIp) {
    enforceHosted(runtime);
    enforceWritePolicy(runtime, "package-root", true);
    if ("latest".equals(tag)) {
      throw new NpmExceptions.BadRequestException("Unable to update latest tag");
    }
    String version;
    try {
      version = mapper.readValue(body, String.class);
    } catch (IOException e) {
      throw new NpmExceptions.BadRequestException("dist-tag payload must be a JSON string", e);
    }
    Optional<Map<String, Object>> packageRootOpt = loadPackageRootMap(runtime, packageId);
    if (packageRootOpt.isEmpty()) {
      return NpmResponseSupport.success(mapper);
    }
    Map<String, Object> packageRoot = packageRootOpt.get();
    if (!NpmMetadata.versions(packageRoot).containsKey(version)) {
      throw new NpmExceptions.BadRequestException(
          "version " + version + " of package " + packageId.id() + " is not present in repository " + runtime.name());
    }
    NpmMetadata.distTags(packageRoot).put(tag, version);
    savePackageRoot(runtime, packageId, packageRoot, createdBy, createdByIp);
    return NpmResponseSupport.success(mapper);
  }

  public MavenResponse deleteDistTag(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tag,
      String createdBy,
      String createdByIp) {
    enforceHosted(runtime);
    enforceWritePolicy(runtime, "package-root", true);
    if ("latest".equals(tag)) {
      throw new NpmExceptions.BadRequestException("Unable to delete latest");
    }
    Optional<Map<String, Object>> packageRootOpt = loadPackageRootMap(runtime, packageId);
    if (packageRootOpt.isEmpty()) {
      return NpmResponseSupport.success(mapper);
    }
    Map<String, Object> packageRoot = packageRootOpt.get();
    NpmMetadata.distTags(packageRoot).remove(tag);
    savePackageRoot(runtime, packageId, packageRoot, createdBy, createdByIp);
    return NpmResponseSupport.success(mapper);
  }

  Optional<Map<String, Object>> packageRoot(RepositoryRuntime runtime, NpmPackageId packageId) {
    return loadPackageRootMap(runtime, packageId);
  }

  MavenResponse getPackage(RepositoryRuntime runtime, NpmPackageId packageId, String repositoryBaseUrl, boolean headOnly) {
    return getPackage(runtime, packageId, repositoryBaseUrl, headOnly, NpmPackumentVariant.FULL);
  }

  MavenResponse getPackage(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String repositoryBaseUrl,
      boolean headOnly,
      NpmPackumentVariant variant) {
    Map<String, Object> packageRoot = loadPackageRootMap(runtime, packageId)
        .orElseThrow(() -> new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found"));
    Map<String, Object> copy = NpmMetadata.deepCopy(packageRoot);
    NpmMetadata.rewriteTarballUrls(copy, packageId, repositoryBaseUrl);
    if (variant.abbreviated()) {
      copy = NpmMetadata.abbreviatePackageRoot(copy);
    }
    byte[] bytes = NpmResponseSupport.write(mapper, copy);
    AssetRecord asset = assetDao.findAssetByPath(runtime.id(), packageId.id()).orElse(null);
    Instant lastModified = asset == null ? Instant.now() : asset.lastUpdatedAt();
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, NpmResponseSupport.JSON, null, lastModified);
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        NpmResponseSupport.JSON, null, lastModified);
  }

  MavenResponse getDistTags(RepositoryRuntime runtime, NpmPackageId packageId, boolean headOnly) {
    Map<String, Object> packageRoot = loadPackageRootMap(runtime, packageId)
        .orElseThrow(() -> new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found"));
    byte[] bytes = NpmResponseSupport.write(mapper, NpmMetadata.distTags(packageRoot));
    if (headOnly) {
      return MavenResponse.noBody(200, bytes.length, NpmResponseSupport.JSON, null, Instant.now());
    }
    return MavenResponse.ok(new ByteArrayInputStream(bytes), bytes.length,
        NpmResponseSupport.JSON, null, Instant.now());
  }

  MavenResponse getTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      boolean headOnly) {
    String tarballPath = packageId.tarballPath(tarballName);
    CachedAssetMetadata snapshot = assetMetadataCache.find(
        runtime.id(),
        tarballPath,
        () -> AssetMetadataCache.Loaded.from(
            assetDao.findAssetByPath(runtime.id(), tarballPath), assetDao))
        .orElseThrow(() -> new NpmExceptions.NpmNotFoundException(
            "Tarball '" + tarballName + "' in package '" + packageId.id() + "' not found"));
    return serveSnapshot(snapshot, headOnly);
  }

  MavenResponse serveAsset(AssetRecord asset, boolean headOnly) {
    AssetBlobRecord blob = asset.assetBlobId() == null
        ? null
        : assetDao.findBlobById(asset.assetBlobId()).orElse(null);
    if (blob == null) {
      throw new NpmExceptions.NpmNotFoundException(asset.path());
    }
    String contentType = responseContentType(asset);
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, blob.sha1(), asset.lastUpdatedAt());
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(BlobReferenceCodec.reference(
                blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new NpmExceptions.NpmNotFoundException(asset.path())),
        blob.size(), contentType, blob.sha1(), asset.lastUpdatedAt());
  }

  private MavenResponse serveSnapshot(CachedAssetMetadata snapshot, boolean headOnly) {
    AssetBlobRecord blob = snapshot.toBlobRecord();
    if (blob == null) {
      throw new NpmExceptions.NpmNotFoundException(snapshot.path());
    }
    String contentType = responseContentType(snapshot.kind(), snapshot.path(), snapshot.contentType());
    if (headOnly) {
      return MavenResponse.noBody(200, blob.size(), contentType, blob.sha1(), snapshot.lastUpdatedAt());
    }
    return MavenResponse.ok(
        () -> blobStorageRegistry.forBlobStoreId(blob.blobStoreId()).get(BlobReferenceCodec.reference(
                blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size()))
            .orElseThrow(() -> new NpmExceptions.NpmNotFoundException(snapshot.path())),
        blob.size(), contentType, blob.sha1(), snapshot.lastUpdatedAt());
  }

  private static String responseContentType(AssetRecord asset) {
    return responseContentType(asset.kind(), asset.path(), asset.contentType());
  }

  private static String responseContentType(String kind, String path, String contentType) {
    if ("tarball".equals(kind) || path.toLowerCase(Locale.ROOT).endsWith(".tgz")) {
      return NpmResponseSupport.TARBALL;
    }
    return contentType;
  }

  private void writeAttachments(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      NpmPackageId packageId,
      Map<String, Object> packageRoot,
      String createdBy,
      String createdByIp) {
    Object raw = packageRoot.get(NpmMetadata.ATTACHMENTS);
    if (!(raw instanceof Map<?, ?> attachments)) {
      return;
    }
    for (Map.Entry<?, ?> entry : attachments.entrySet()) {
      String tarballName = NpmMetadata.extractTarballName(String.valueOf(entry.getKey()));
      if (!(entry.getValue() instanceof Map<?, ?> attachment)) {
        continue;
      }
      Object data = attachment.get("data");
      if (data == null) {
        continue;
      }
      String version = NpmMetadata.findVersionForTarball(packageRoot, tarballName);
      if (version == null) {
        throw new NpmExceptions.BadRequestException("Unable to resolve version for tarball " + tarballName);
      }
      Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), packageId.tarballPath(tarballName));
      enforceWritePolicy(runtime, "tarball", existing.isPresent());
      byte[] tarball = Base64.getMimeDecoder().decode(data.toString());
      String contentType = NpmMetadata.stringValue(attachment.get("content_type"), NpmResponseSupport.TARBALL);
      writer.writeTarball(runtime, storage, blobStoreId, packageId, version, tarballName,
          new ByteArrayInputStream(tarball), contentType, createdBy, createdByIp, Map.of());
    }
  }

  private void validateAttachmentsWrite(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      Map<String, Object> packageRoot) {
    Object raw = packageRoot.get(NpmMetadata.ATTACHMENTS);
    if (!(raw instanceof Map<?, ?> attachments)) {
      return;
    }
    for (Map.Entry<?, ?> entry : attachments.entrySet()) {
      String tarballName = NpmMetadata.extractTarballName(String.valueOf(entry.getKey()));
      if (!(entry.getValue() instanceof Map<?, ?> attachment) || attachment.get("data") == null) {
        continue;
      }
      String version = NpmMetadata.findVersionForTarball(packageRoot, tarballName);
      if (version == null) {
        throw new NpmExceptions.BadRequestException("Unable to resolve version for tarball " + tarballName);
      }
      Optional<AssetRecord> existing = assetDao.findAssetByPath(runtime.id(), packageId.tarballPath(tarballName));
      enforceWritePolicy(runtime, "tarball", existing.isPresent());
    }
  }

  private PublishWritePlan packageRootWritePlan(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String revision,
      Map<String, Object> incoming) {
    Optional<Map<String, Object>> existingOpt = loadPackageRootMap(runtime, packageId);
    Optional<AssetRecord> existingAsset = assetDao.findAssetByPath(runtime.id(), packageId.id());
    enforceWritePolicy(runtime, "package-root", existingAsset.isPresent());
    String effectiveRevision = revision == null
        ? NpmMetadata.stringValue(incoming.get(NpmMetadata.META_REV), null)
        : revision;
    if (existingOpt.isPresent()) {
      Map<String, Object> existing = existingOpt.get();
      if (effectiveRevision != null) {
        String oldRev = NpmMetadata.stringValue(existing.get(NpmMetadata.META_REV), null);
        if (!effectiveRevision.equals(oldRev)) {
          throw new NpmExceptions.BadRequestException("Revision mismatch for " + packageId.id());
        }
      }
    }
    return new PublishWritePlan(existingOpt, effectiveRevision);
  }

  private Map<String, Object> packageRootForStorage(
      NpmPackageId packageId,
      Map<String, Object> incoming,
      PublishWritePlan plan) {
    Map<String, Object> packageRoot = incoming;
    if (plan.existingPackageRoot().isPresent()) {
      Map<String, Object> existing = plan.existingPackageRoot().get();
      if (plan.effectiveRevision() == null) {
        packageRoot = NpmMetadata.overlay(existing, incoming);
      }
      NpmMetadata.prepareForStorage(packageRoot, packageId, NpmMetadata.nextRevision(existing, false));
      return packageRoot;
    }
    NpmMetadata.prepareForStorage(packageRoot, packageId, "1");
    return packageRoot;
  }

  private void removeVersionForTarball(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      String tarballName,
      String createdBy,
      String createdByIp) {
    Optional<Map<String, Object>> packageRootOpt = loadPackageRootMap(runtime, packageId);
    if (packageRootOpt.isEmpty()) {
      return;
    }
    Map<String, Object> packageRoot = packageRootOpt.get();
    String version = NpmMetadata.findVersionForTarball(packageRoot, tarballName);
    if (version != null) {
      NpmMetadata.versions(packageRoot).remove(version);
      NpmMetadata.distTags(packageRoot).entrySet().removeIf(e -> version.equals(String.valueOf(e.getValue())));
      savePackageRoot(runtime, packageId, packageRoot, createdBy, createdByIp);
    }
  }

  private void savePackageRoot(
      RepositoryRuntime runtime,
      NpmPackageId packageId,
      Map<String, Object> packageRoot,
      String createdBy,
      String createdByIp) {
    long blobStoreId = requireBlobStore(runtime);
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blobStoreId);
    Map<String, Object> existing = loadPackageRootMap(runtime, packageId).orElse(null);
    NpmMetadata.prepareForStorage(packageRoot, packageId, NpmMetadata.nextRevision(existing, existing == null));
    writer.writePackageRoot(runtime, storage, blobStoreId, packageId,
        NpmResponseSupport.write(mapper, packageRoot), createdBy, createdByIp);
  }

  private Optional<Map<String, Object>> loadPackageRootMap(RepositoryRuntime runtime, NpmPackageId packageId) {
    Optional<AssetRecord> asset = assetDao.findAssetByPath(runtime.id(), packageId.id());
    if (asset.isEmpty() || asset.get().assetBlobId() == null) {
      return Optional.empty();
    }
    AssetBlobRecord blob = assetDao.findBlobById(asset.get().assetBlobId()).orElse(null);
    if (blob == null) {
      return Optional.empty();
    }
    BlobStorage storage = blobStorageRegistry.forBlobStoreId(blob.blobStoreId());
    try (InputStream in = storage.get(BlobReferenceCodec.reference(
        blob.blobRef(), blob.objectKey(), blob.sha256(), blob.size())).orElse(null)) {
      if (in == null) {
        return Optional.empty();
      }
      return Optional.of(mapper.readValue(in, MAP_TYPE));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read npm package root " + packageId.id(), e);
    }
  }

  private void assertRevisionIfPresent(RepositoryRuntime runtime, NpmPackageId packageId, String revision) {
    if (revision == null) {
      return;
    }
    Map<String, Object> existing = loadPackageRootMap(runtime, packageId)
        .orElseThrow(() -> new NpmExceptions.NpmNotFoundException("Package '" + packageId.id() + "' not found"));
    String oldRev = NpmMetadata.stringValue(existing.get(NpmMetadata.META_REV), null);
    if (!revision.equals(oldRev)) {
      throw new NpmExceptions.BadRequestException("Revision mismatch for " + packageId.id());
    }
  }

  private void enforceHosted(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new NpmExceptions.MethodNotAllowed("Write methods are only valid on hosted npm repositories");
    }
  }

  private void enforceWritePolicy(RepositoryRuntime runtime, String kind, boolean existing) {
    String policy = runtime.writePolicy() == null ? "ALLOW_ONCE" : runtime.writePolicy();
    if ("DENY".equals(policy)) {
      throw new NpmExceptions.WritePolicyDenied("Write policy DENY forbids writing " + kind);
    }
    if ("ALLOW_ONCE".equals(policy) && existing && !"package-root".equals(kind)) {
      throw new NpmExceptions.WritePolicyDenied("Write policy ALLOW_ONCE forbids overwriting " + kind);
    }
  }

  private void enforceDeletePolicy(RepositoryRuntime runtime, String kind) {
    String policy = runtime.writePolicy() == null ? "ALLOW_ONCE" : runtime.writePolicy();
    if ("DENY".equals(policy)) {
      throw new NpmExceptions.WritePolicyDenied("Write policy DENY forbids deleting " + kind);
    }
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private Map<String, Object> readPackageJson(MultipartFile asset) throws IOException {
    try (InputStream raw = asset.getInputStream();
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      byte[] fallback = null;
      while ((entry = tar.getNextTarEntry()) != null) {
        if (!entry.isFile()) continue;
        String name = stripLeadingDotSlash(entry.getName());
        if ("package/package.json".equals(name)) {
          return mapper.readValue(tar.readAllBytes(), MAP_TYPE);
        }
        if (fallback == null && name.endsWith("/package.json")) {
          fallback = tar.readAllBytes();
        }
      }
      if (fallback != null) {
        return mapper.readValue(fallback, MAP_TYPE);
      }
    }
    throw new NpmExceptions.BadRequestException("npm tarball does not contain package/package.json");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return new LinkedHashMap<>((Map<String, Object>) map);
    }
    return new LinkedHashMap<>();
  }

  private static String originalFilename(MultipartFile file) {
    String name = file.getOriginalFilename();
    if (name == null || name.isBlank()) {
      throw new NpmExceptions.BadRequestException("npm upload file name is required");
    }
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return slash < 0 ? name : name.substring(slash + 1);
  }

  private static String stripLeadingDotSlash(String value) {
    String result = value == null ? "" : value;
    while (result.startsWith("./")) {
      result = result.substring(2);
    }
    return result;
  }
}
