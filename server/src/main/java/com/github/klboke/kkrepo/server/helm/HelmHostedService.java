package com.github.klboke.kkrepo.server.helm;

import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.HelmIndexRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.protocol.helm.HelmAssetKind;
import com.github.klboke.kkrepo.protocol.helm.HelmChartMetadata;
import com.github.klboke.kkrepo.protocol.helm.HelmChartPackageParser;
import com.github.klboke.kkrepo.protocol.helm.HelmIndex;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenExceptions;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HelmHostedService {
  static final String INDEX_PATH = "index.yaml";

  private final AssetDao assetDao;
  private final RepositoryIndexRebuildDao indexRebuildDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final HelmAssetWriter writer;
  private final HelmAssetReader reader;
  private final AssetMetadataCache assetMetadataCache;
  private final HelmChartPackageParser chartParser = new HelmChartPackageParser();

  public HelmHostedService(
      AssetDao assetDao,
      RepositoryIndexRebuildDao indexRebuildDao,
      BlobStorageRegistry blobStorageRegistry,
      HelmAssetWriter writer,
      HelmAssetReader reader,
      AssetMetadataCache assetMetadataCache) {
    this.assetDao = assetDao;
    this.indexRebuildDao = indexRebuildDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.assetMetadataCache = assetMetadataCache;
  }

  public MavenResponse get(RepositoryRuntime runtime, String rawPath, boolean headOnly) {
    ensureHosted(runtime);
    String path = normalizePath(rawPath);
    HelmAssetKind kind = readableKind(path);
    if (kind == HelmAssetKind.INDEX) {
      ensureIndex(runtime);
    }
    CachedAssetMetadata snapshot = assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao))
        .orElseThrow(() -> new MavenExceptions.MavenNotFoundException(path));
    return reader.serveSnapshot(snapshot, headOnly, path);
  }

  public MavenResponse put(RepositoryRuntime runtime, String rawPath, InputStream body,
      String contentType, String createdBy, String createdByIp) {
    ensureHosted(runtime);
    String path = normalizePath(rawPath);
    HelmAssetKind kind = knownKind(path);
    if (kind == null || kind == HelmAssetKind.INDEX) {
      return MavenResponse.noBody(404);
    }
    enforceWritePolicy(runtime, path);
    BlobStorage storage = blobStorage(runtime);
    try {
      writer.write(
          runtime,
          storage,
          requireBlobStore(runtime),
          path,
          body,
          contentType,
          kind,
          null,
          Map.of(),
          Map.of(),
          createdBy,
          createdByIp);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.LayoutPolicyViolation(e.getMessage());
    }
    if (kind == HelmAssetKind.PACKAGE) {
      enqueueIndex(runtime);
    }
    return MavenResponse.noBody(200);
  }

  public MavenResponse push(RepositoryRuntime runtime, MultipartFile chart, String createdBy, String createdByIp)
      throws IOException {
    ensureHosted(runtime);
    if (chart == null || chart.isEmpty()) {
      throw new MavenExceptions.LayoutPolicyViolation("Helm chart multipart field is required");
    }
    HelmChartMetadata metadata;
    try (InputStream input = chart.getInputStream()) {
      metadata = chartParser.parse(input);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.LayoutPolicyViolation(e.getMessage());
    }
    validateChartPathSegment("name", metadata.name());
    validateChartPathSegment("version", metadata.version());
    String path = metadata.name() + "-" + metadata.version() + HelmAssetKind.PACKAGE.extension();
    if (assetDao.findAssetByPath(runtime.id(), path).isPresent()) {
      throw new MavenExceptions.WritePolicyDenied("Asset already exists: " + path);
    }
    enforceWritePolicy(runtime, path);
    BlobStorage storage = blobStorage(runtime);
    try (InputStream input = chart.getInputStream()) {
      writer.write(
          runtime,
          storage,
          requireBlobStore(runtime),
          path,
          input,
          chart.getContentType(),
          HelmAssetKind.PACKAGE,
          metadata,
          Map.of(),
          Map.of(),
          createdBy,
          createdByIp);
    }
    enqueueIndex(runtime);
    return MavenResponse.created();
  }

  @Transactional
  public MavenResponse delete(RepositoryRuntime runtime, String rawPath) {
    ensureHosted(runtime);
    String path = normalizePath(rawPath);
    HelmAssetKind kind = knownKind(path);
    if (kind != HelmAssetKind.PACKAGE) {
      return MavenResponse.noBody(404);
    }
    BlobStorage storage = blobStorage(runtime);
    int deleted = writer.deleteAsset(runtime, storage, path);
    if (deleted == 0) {
      return MavenResponse.noBody(404);
    }
    if (kind == HelmAssetKind.PACKAGE) {
      enqueueIndex(runtime);
    }
    return MavenResponse.noBody(200);
  }

  public void rebuildIndex(RepositoryRuntime runtime, BlobStorage storage, long blobStoreId,
      String createdBy, String createdByIp) {
    writeGeneratedIndex(runtime, storage, blobStoreId, indexRecords(runtime), createdBy, createdByIp);
  }

  private void ensureIndex(RepositoryRuntime runtime) {
    if (assetDao.findAssetByPath(runtime.id(), INDEX_PATH).isPresent()) {
      return;
    }
    BlobStorage storage = blobStorage(runtime);
    writeGeneratedIndex(runtime, storage, requireBlobStore(runtime), List.of(), "system", null);
    enqueueIndex(runtime);
  }

  private List<HelmIndex.ChartRecord> indexRecords(RepositoryRuntime runtime) {
    return assetDao.listHelmIndexRows(runtime.id()).stream()
        .map(this::toChartRecord)
        .filter(record -> record.name() != null && record.version() != null)
        .toList();
  }

  private void enqueueIndex(RepositoryRuntime runtime) {
    indexRebuildDao.enqueue(runtime.id(), RepositoryIndexRebuildDao.HELM_INDEX);
  }

  private void writeGeneratedIndex(
      RepositoryRuntime runtime,
      BlobStorage storage,
      long blobStoreId,
      List<HelmIndex.ChartRecord> records,
      String createdBy,
      String createdByIp) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("kkrepo-helm-index-", ".yaml");
      try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
        HelmIndex.writeHosted(records, Instant.now(), out);
      }
      try (InputStream input = Files.newInputStream(tmp)) {
        writer.write(
            runtime,
            storage,
            blobStoreId,
            INDEX_PATH,
            input,
            HelmIndex.CONTENT_TYPE,
            HelmAssetKind.INDEX,
            null,
            Map.of(),
            Map.of(),
            createdBy,
            createdByIp);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Failed to generate Helm index for " + runtime.name(), e);
    } finally {
      if (tmp != null) {
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
      }
    }
  }

  private HelmIndex.ChartRecord toChartRecord(HelmIndexRow row) {
    Map<String, Object> attrs = row.attributes() == null ? Map.of() : row.attributes();
    return new HelmIndex.ChartRecord(
        stringAttr(attrs, "name"),
        stringAttr(attrs, "version"),
        stringAttr(attrs, "apiVersion"),
        stringAttr(attrs, "description"),
        stringAttr(attrs, "appVersion"),
        stringAttr(attrs, "icon"),
        row.lastUpdatedAt(),
        row.sha256(),
        List.of(localIndexUrl(attrs, row.path())),
        stringList(attrs.get("sources")),
        maintainerList(attrs.get("maintainers")));
  }

  private String localIndexUrl(Map<String, Object> attrs, String path) {
    String name = stringAttr(attrs, "name");
    String version = stringAttr(attrs, "version");
    if (name != null && version != null) {
      return name + "-" + version + HelmAssetKind.PACKAGE.extension();
    }
    return path;
  }

  private void enforceWritePolicy(RepositoryRuntime runtime, String path) {
    String policy = runtime.writePolicy() == null ? "ALLOW" : runtime.writePolicy();
    if ("DENY".equals(policy)) {
      throw new MavenExceptions.WritePolicyDenied("Write policy DENY forbids writing " + path);
    }
    if ("ALLOW_ONCE".equals(policy) && assetDao.findAssetByPath(runtime.id(), path).isPresent()) {
      throw new MavenExceptions.WritePolicyDenied("Write policy ALLOW_ONCE forbids overwriting " + path);
    }
  }

  private void ensureHosted(RepositoryRuntime runtime) {
    if (!runtime.isHosted()) {
      throw new MavenExceptions.MethodNotAllowed("Operation is only valid on hosted Helm repositories");
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Helm repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  static String normalizePath(String rawPath) {
    String path = rawPath == null ? "" : rawPath.trim();
    while (path.startsWith("/")) path = path.substring(1);
    if (path.isEmpty()) {
      throw new MavenExceptions.MavenNotFoundException("Missing Helm path");
    }
    return path;
  }

  private static void validateChartPathSegment(String field, String value) {
    if (value == null || value.isBlank()
        || value.indexOf('\0') >= 0
        || value.contains("/")
        || value.contains("\\")
        || value.equals(".")
        || value.equals("..")
        || value.contains("..")) {
      throw new MavenExceptions.LayoutPolicyViolation("Invalid Helm chart " + field + ": " + value);
    }
  }

  private static HelmAssetKind readableKind(String path) {
    try {
      return HelmAssetKind.fromPath(path);
    } catch (IllegalArgumentException e) {
      throw new MavenExceptions.MavenNotFoundException(path);
    }
  }

  private static HelmAssetKind knownKind(String path) {
    try {
      return HelmAssetKind.fromPath(path);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String stringAttr(Map<String, Object> attrs, String key) {
    Object value = attrs.get(key);
    return value == null ? null : value.toString();
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(item -> item != null && !item.toString().isBlank())
        .map(Object::toString)
        .toList();
  }

  private static List<Map<String, String>> maintainerList(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(HelmHostedService::stringMap)
        .filter(map -> !map.isEmpty())
        .toList();
  }

  private static Map<String, String> stringMap(Map<?, ?> raw) {
    Map<String, String> result = new LinkedHashMap<>();
    raw.forEach((key, value) -> {
      if (key != null && value != null) result.put(key.toString(), value.toString());
    });
    return result;
  }
}
