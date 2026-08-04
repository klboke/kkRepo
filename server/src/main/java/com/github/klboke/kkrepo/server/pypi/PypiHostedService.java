package com.github.klboke.kkrepo.server.pypi;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.PypiProjectIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.server.blob.TempBlobFiles;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PypiHostedService {
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final RepositoryIndexRebuildDao indexRebuildDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final PypiAssetWriter writer;
  private final PypiAssetReader reader;
  private final AssetMetadataCache assetMetadataCache;
  private final long projectIndexSyncWaitMs;

  public PypiHostedService(
      AssetDao assetDao,
      ComponentDao componentDao,
      RepositoryIndexRebuildDao indexRebuildDao,
      BlobStorageRegistry blobStorageRegistry,
      PypiAssetWriter writer,
      PypiAssetReader reader,
      AssetMetadataCache assetMetadataCache,
      @Value("${kkrepo.pypi.index.sync-wait-ms:0}") long projectIndexSyncWaitMs) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.indexRebuildDao = indexRebuildDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.assetMetadataCache = assetMetadataCache;
    this.projectIndexSyncWaitMs = Math.max(0, projectIndexSyncWaitMs);
  }

  public PypiResponse getRootIndex(RepositoryRuntime runtime, boolean headOnly) {
    ensureHosted(runtime);
    ensureRootIndex(runtime);
    CachedAssetMetadata snapshot = lookupCached(runtime, PypiPaths.INDEX_PREFIX)
        .orElseThrow(() -> new PypiExceptions.PypiNotFoundException(PypiPaths.INDEX_PREFIX));
    return reader.serveSnapshot(snapshot, headOnly, PypiPaths.INDEX_PREFIX);
  }

  public PypiResponse getIndex(RepositoryRuntime runtime, String projectName, boolean headOnly) {
    ensureHosted(runtime);
    String normalized = PypiPaths.normalizeName(projectName);
    String path = PypiPaths.indexPath(normalized);
    if (componentDao.listByName(runtime.id(), normalized).isEmpty()
        && lookupCached(runtime, path).isEmpty()) {
      throw new PypiExceptions.PypiNotFoundException(projectName);
    }
    waitForProjectIndexRebuild(runtime.id(), normalized);
    ensureProjectIndex(runtime, normalized);
    CachedAssetMetadata snapshot = lookupCached(runtime, path)
        .orElseThrow(() -> new PypiExceptions.PypiNotFoundException(projectName));
    return reader.serveSnapshot(snapshot, headOnly, path);
  }

  public PypiResponse getPackage(RepositoryRuntime runtime, String path, boolean headOnly) {
    ensureHosted(runtime);
    CachedAssetMetadata snapshot = lookupCached(runtime, path)
        .orElseThrow(() -> new PypiExceptions.PypiNotFoundException(path));
    return reader.serveSnapshot(snapshot, headOnly, path);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  public PypiResponse upload(
      RepositoryRuntime runtime,
      Map<String, String> fields,
      MultipartFile content,
      MultipartFile signature,
      String createdBy,
      String createdByIp) throws IOException {
    ensureHosted(runtime);
    if (content == null || content.isEmpty()) {
      throw new PypiExceptions.BadRequestException("PyPI upload requires multipart file field 'content'");
    }
    String action = fields.get(":action");
    if (!"file_upload".equals(action)) {
      throw new PypiExceptions.BadRequestException("Unsupported PyPI upload action: " + action);
    }
    String originalName = requireField(fields, "name");
    String version = requireField(fields, "version");
    String normalizedName = PypiPaths.normalizeName(originalName);
    String filename = originalFilename(content, originalName + "-" + version + ".tar.gz");
    String packagePath = PypiPaths.packagePath(normalizedName, version, filename);
    enforceWritePolicy(runtime, packagePath);
    validateMd5Digest(fields.get("md5_digest"), content);

    Map<String, Object> attrs = attributes(fields, originalName, normalizedName, version);
    PypiAssetWriter.PackageCoordinate coordinate =
        new PypiAssetWriter.PackageCoordinate(originalName, normalizedName, version, fields.get("summary"));
    writer.write(
        runtime,
        blobStorage(runtime),
        requireBlobStore(runtime),
        packagePath,
        content.getInputStream(),
        content.getContentType(),
        "package",
        coordinate,
        attrs,
        Map.of(),
        createdBy,
        createdByIp);

    if (signature != null && !signature.isEmpty()) {
      String signatureFile = originalFilename(signature, filename + ".asc");
      String signaturePath = PypiPaths.packagePath(normalizedName, version, signatureFile);
      enforceWritePolicy(runtime, signaturePath);
      writer.write(
          runtime,
          blobStorage(runtime),
          requireBlobStore(runtime),
          signaturePath,
          signature.getInputStream(),
          signature.getContentType(),
          "package-signature",
          coordinate,
          attrs,
          Map.of(),
          createdBy,
          createdByIp);
    }
    refreshIndexesAfterUpload(runtime, normalizedName);
    return PypiResponse.noBody(200);
  }

  public PypiResponse uploadAsset(
      RepositoryRuntime runtime,
      MultipartFile asset,
      String createdBy,
      String createdByIp) throws IOException {
    ensureHosted(runtime);
    if (asset == null || asset.isEmpty()) {
      throw new PypiExceptions.BadRequestException("PyPI upload requires multipart file field 'asset'");
    }
    PythonPackageMetadata metadata = inspectPackageMetadata(asset);
    String normalizedName = PypiPaths.normalizeName(metadata.name());
    String filename = originalFilename(asset, metadata.name() + "-" + metadata.version() + ".tar.gz");
    String packagePath = PypiPaths.packagePath(normalizedName, metadata.version(), filename);
    if (assetDao.findAssetByPath(runtime.id(), packagePath).isPresent()) {
      throw new PypiExceptions.WritePolicyDenied("Asset already exists: " + packagePath);
    }
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put(":action", "file_upload");
    fields.put("metadata_version", blankToDefault(metadata.metadataVersion(), "2.1"));
    fields.put("name", metadata.name());
    fields.put("version", metadata.version());
    putIfPresent(fields, "summary", metadata.summary());
    putIfPresent(fields, "requires_python", metadata.requiresPython());
    return upload(runtime, fields, asset, null, createdBy, createdByIp);
  }

  public void rebuildRootIndex(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String createdBy,
      String createdByIp) {
    writeIndex(runtime, storage, blobStoreId, PypiPaths.INDEX_PREFIX, buildRootIndex(runtime),
        "root-index", Map.of(), createdBy, createdByIp);
  }

  public void rebuildProjectIndex(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String normalizedName,
      String createdBy,
      String createdByIp) {
    writeIndex(runtime, storage, blobStoreId, PypiPaths.indexPath(normalizedName),
        buildProjectIndex(runtime, normalizedName), "index", Map.of("name", normalizedName),
        createdBy, createdByIp);
  }

  private void ensureRootIndex(RepositoryRuntime runtime) {
    if (assetDao.findAssetByPath(runtime.id(), PypiPaths.INDEX_PREFIX).isPresent()) {
      return;
    }
    rebuildRootIndex(runtime, blobStorage(runtime), requireBlobStore(runtime), "system", null);
  }

  private void ensureProjectIndex(RepositoryRuntime runtime, String normalizedName) {
    if (assetDao.findAssetByPath(runtime.id(), PypiPaths.indexPath(normalizedName)).isPresent()) {
      return;
    }
    rebuildProjectIndex(runtime, blobStorage(runtime), requireBlobStore(runtime), normalizedName, "system", null);
  }

  private void refreshIndexesAfterUpload(RepositoryRuntime runtime, String normalizedName) {
    indexRebuildDao.enqueue(runtime.id(), RepositoryIndexRebuildDao.PYPI_PROJECT, normalizedName);
    indexRebuildDao.enqueue(runtime.id(), RepositoryIndexRebuildDao.PYPI_ROOT);
  }

  private void waitForProjectIndexRebuild(long repositoryId, String normalizedName) {
    if (projectIndexSyncWaitMs <= 0) return;
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(projectIndexSyncWaitMs);
    while (indexRebuildDao.hasPending(repositoryId, RepositoryIndexRebuildDao.PYPI_PROJECT, normalizedName)) {
      long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
      if (remainingMs <= 0) return;
      try {
        Thread.sleep(Math.min(remainingMs, 50));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void writeIndex(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      String path,
      String html,
      String kind,
      Map<String, Object> attributes,
      String createdBy,
      String createdByIp) {
    writer.writeBytes(
        runtime,
        storage,
        blobStoreId,
        path,
        html.getBytes(StandardCharsets.UTF_8),
        "text/html",
        kind,
        null,
        attributes,
        createdBy,
        createdByIp);
  }

  private String buildRootIndex(RepositoryRuntime runtime) {
    Map<String, PypiLink> links = new TreeMap<>();
    for (String name : componentDao.listDistinctNamesByRepositoryId(runtime.id())) {
      if (name == null || name.isBlank()) continue;
      links.putIfAbsent(name, new PypiLink(name, name + "/", ""));
    }
    return PypiIndex.buildRoot(links.values());
  }

  private String buildProjectIndex(RepositoryRuntime runtime, String normalizedName) {
    Map<String, PypiLink> links = new TreeMap<>();
    for (PypiProjectIndexRow row : assetDao.listPypiProjectIndexRows(runtime.id(), normalizedName)) {
      String file = PypiPaths.fileName(row.path());
      String href = "../../" + row.path() + (row.md5() == null ? "" : "#md5=" + row.md5());
      String requiresPython = stringAttr(row.attributes(), "requires_python");
      links.putIfAbsent(file.toLowerCase(), new PypiLink(file, href, requiresPython));
    }
    return PypiIndex.buildProject(normalizedName, links.values());
  }

  private Map<String, Object> attributes(Map<String, String> fields,
      String originalName, String normalizedName, String version) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    fields.forEach((k, v) -> {
      if (v != null && !v.isBlank()) attrs.put(k, v);
    });
    attrs.put("name", originalName);
    attrs.put("normalizedName", normalizedName);
    attrs.put("version", version);
    return attrs;
  }

  private void enforceWritePolicy(RepositoryRuntime runtime, String path) {
    String policy = runtime.writePolicy() == null ? "ALLOW" : runtime.writePolicy();
    if ("DENY".equals(policy)) {
      throw new PypiExceptions.WritePolicyDenied("Write policy DENY forbids writing " + path);
    }
    if ("ALLOW_ONCE".equals(policy) && assetDao.findAssetByPath(runtime.id(), path).isPresent()) {
      throw new PypiExceptions.WritePolicyDenied("Write policy ALLOW_ONCE forbids overwriting " + path);
    }
  }

  private void validateMd5Digest(String expected, MultipartFile content) throws IOException {
    if (expected == null || expected.isBlank()) return;
    MessageDigest md5 = digest("MD5");
    try (InputStream in = content.getInputStream()) {
      byte[] buffer = new byte[TempBlobFiles.responseBufferSize()];
      int n;
      while ((n = in.read(buffer)) > 0) {
        md5.update(buffer, 0, n);
      }
    }
    String actual = HexFormat.of().formatHex(md5.digest());
    if (!actual.equalsIgnoreCase(expected.trim())) {
      throw new PypiExceptions.BadRequestException("PyPI md5_digest mismatch for " + content.getOriginalFilename());
    }
  }

  private MessageDigest digest(String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing digest algorithm: " + algorithm, e);
    }
  }

  private void ensureHosted(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new PypiExceptions.MethodNotAllowed("Operation is only valid on hosted PyPI repositories");
    }
  }

  private String requireField(Map<String, String> fields, String name) {
    String value = fields.get(name);
    if (value == null || value.isBlank()) {
      throw new PypiExceptions.BadRequestException("PyPI upload is missing field: " + name);
    }
    return value.trim();
  }

  private String originalFilename(MultipartFile file, String fallback) {
    String name = file == null ? null : file.getOriginalFilename();
    if (name == null || name.isBlank()) return fallback;
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return slash < 0 ? name : name.substring(slash + 1);
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("PyPI repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    if (attrs == null) return "";
    Object value = attrs.get(key);
    return value == null ? "" : value.toString();
  }

  private PythonPackageMetadata inspectPackageMetadata(MultipartFile asset) throws IOException {
    String filename = originalFilename(asset, "");
    PythonPackageMetadata metadata = null;
    if (filename.endsWith(".whl") || filename.endsWith(".zip")) {
      metadata = inspectZipMetadata(asset);
    } else if (filename.endsWith(".tar.gz") || filename.endsWith(".tgz")) {
      metadata = inspectTarGzMetadata(asset);
    }
    PythonPackageMetadata fallback = metadataFromFilename(filename);
    metadata = PythonPackageMetadata.merge(metadata, fallback);
    if (metadata == null || metadata.name() == null || metadata.name().isBlank()
        || metadata.version() == null || metadata.version().isBlank()) {
      throw new PypiExceptions.BadRequestException(
          "Unable to determine PyPI package name/version from " + filename);
    }
    return metadata;
  }

  private PythonPackageMetadata inspectZipMetadata(MultipartFile asset) throws IOException {
    try (InputStream raw = asset.getInputStream(); ZipInputStream zip = new ZipInputStream(raw)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        String name = entry.getName();
        if (name.endsWith(".dist-info/METADATA") || name.endsWith("PKG-INFO")) {
          return metadataFromText(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    return null;
  }

  private PythonPackageMetadata inspectTarGzMetadata(MultipartFile asset) throws IOException {
    try (InputStream raw = asset.getInputStream();
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
        TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextTarEntry()) != null) {
        if (!entry.isFile()) continue;
        String name = entry.getName();
        if (name.endsWith("PKG-INFO") || name.endsWith(".dist-info/METADATA")) {
          return metadataFromText(new String(tar.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    return null;
  }

  private static PythonPackageMetadata metadataFromText(String text) {
    Map<String, String> headers = new LinkedHashMap<>();
    String current = null;
    for (String line : text.split("\\R")) {
      if ((line.startsWith(" ") || line.startsWith("\t")) && current != null) {
        headers.put(current, headers.get(current) + "\n" + line.trim());
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        current = null;
        continue;
      }
      current = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      headers.putIfAbsent(current, line.substring(colon + 1).trim());
    }
    return new PythonPackageMetadata(
        headers.get("metadata-version"),
        headers.get("name"),
        headers.get("version"),
        headers.get("summary"),
        headers.get("requires-python"));
  }

  private static PythonPackageMetadata metadataFromFilename(String filename) {
    if (filename == null || filename.isBlank()) return null;
    String base = filename;
    if (base.endsWith(".tar.gz")) {
      base = base.substring(0, base.length() - ".tar.gz".length());
    } else if (base.endsWith(".whl")) {
      base = base.substring(0, base.length() - ".whl".length());
    } else if (base.endsWith(".zip")) {
      base = base.substring(0, base.length() - ".zip".length());
    } else if (base.endsWith(".tgz")) {
      base = base.substring(0, base.length() - ".tgz".length());
    } else {
      int dot = base.lastIndexOf('.');
      if (dot > 0) base = base.substring(0, dot);
    }
    String[] parts = base.split("-");
    if (parts.length < 2) return null;
    return new PythonPackageMetadata(null, parts[0], parts[1], null, null);
  }

  private static void putIfPresent(Map<String, String> fields, String key, String value) {
    if (value != null && !value.isBlank()) fields.put(key, value);
  }

  private static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private record PythonPackageMetadata(
      String metadataVersion,
      String name,
      String version,
      String summary,
      String requiresPython) {
    static PythonPackageMetadata merge(PythonPackageMetadata primary, PythonPackageMetadata fallback) {
      if (primary == null) return fallback;
      if (fallback == null) return primary;
      return new PythonPackageMetadata(
          blankToDefault(primary.metadataVersion(), fallback.metadataVersion()),
          blankToDefault(primary.name(), fallback.name()),
          blankToDefault(primary.version(), fallback.version()),
          blankToDefault(primary.summary(), fallback.summary()),
          blankToDefault(primary.requiresPython(), fallback.requiresPython()));
    }
  }
}
