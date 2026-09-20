package com.github.klboke.kkrepo.server;

import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.statistics.StorageStatisticsService;
import com.github.klboke.kkrepo.server.statistics.StorageStatisticsService.UsageSnapshot;
import com.github.klboke.kkrepo.server.statistics.StorageUsageResponse;
import com.github.klboke.kkrepo.server.statistics.StorageUsageResponse.BlobStoreUsage;
import com.github.klboke.kkrepo.storage.file.FileBlobStoreConfig;
import com.github.klboke.kkrepo.storage.file.FileBlobStorePathValidator;
import com.github.klboke.kkrepo.storage.file.FileBlobStorageFactory;
import com.github.klboke.kkrepo.storage.file.admin.FileBlobStoreAdmin;
import com.github.klboke.kkrepo.storage.file.admin.FileBlobStoreAdmin.FileBlobStoreSummary;
import com.github.klboke.kkrepo.storage.file.admin.FileBlobStoreAdmin.FileProbeResult;
import com.github.klboke.kkrepo.storage.s3.Engine;
import com.github.klboke.kkrepo.storage.s3.S3BlobStoreConfig;
import com.github.klboke.kkrepo.storage.s3.admin.S3BlobStoreAdmin;
import com.github.klboke.kkrepo.storage.s3.admin.S3BucketSummary;
import com.github.klboke.kkrepo.storage.s3.admin.S3ProbeResult;
import com.github.klboke.kkrepo.storage.s3.config.S3StorageProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/blob-stores")
public class BlobStoresController {
  private final BlobStoreDao blobStoreDao;
  private final S3BlobStoreAdmin s3BlobStoreAdmin;
  private final FileBlobStoreAdmin fileBlobStoreAdmin;
  private final FileBlobStorageFactory fileBlobStorageFactory;
  private final FileBlobStorePathValidator filePathValidator;
  private final S3StorageProperties s3Properties;
  private final BlobStorageRegistry blobStorageRegistry;
  private final Environment environment;
  private StorageStatisticsService statistics;

  @Autowired
  void setStorageStatistics(StorageStatisticsService statistics) {
    this.statistics = statistics;
  }

  @GetMapping("/statistics/usage")
  public UsageSnapshot<BlobStoreUsage> statistics() {
    // SecurityManagementFilter requires nexus:blobstores:read for this endpoint.
    // Read only the catalog here: the count request must never probe S3/OSS/File stores.
    return StorageUsageResponse.blobs(
        statistics.blobs(blobStoreRecords().stream().map(BlobStoreRecord::id).toList()));
  }

  public BlobStoresController(
      BlobStoreDao blobStoreDao,
      S3BlobStoreAdmin s3BlobStoreAdmin,
      FileBlobStoreAdmin fileBlobStoreAdmin,
      FileBlobStorageFactory fileBlobStorageFactory,
      FileBlobStorePathValidator filePathValidator,
      S3StorageProperties s3Properties,
      BlobStorageRegistry blobStorageRegistry,
      Environment environment) {
    this.blobStoreDao = blobStoreDao;
    this.s3BlobStoreAdmin = s3BlobStoreAdmin;
    this.fileBlobStoreAdmin = fileBlobStoreAdmin;
    this.fileBlobStorageFactory = fileBlobStorageFactory;
    this.filePathValidator = filePathValidator;
    this.s3Properties = s3Properties;
    this.blobStorageRegistry = blobStorageRegistry;
    this.environment = environment;
  }

  @GetMapping
  public BlobStoresResponse list() {
    List<BlobStoreRecord> records = blobStoreRecords();
    List<BlobStoreView> stores = new ArrayList<>();
    for (BlobStoreRecord record : records) {
      boolean configured = isConfigured(record);
      stores.add(toView(
          record,
          summary(record),
          configured,
          "database"));
    }
    return new BlobStoresResponse(stores);
  }

  @PostMapping
  public BlobStoreView create(@RequestBody BlobStoreRequest request) {
    String name = required(request.name(), "name");
    if (blobStoreDao.findByName(name).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Blob store name already exists");
    }
    BlobStoreRecord record = toCreateRecord(name, request);
    validateFileRecord(record);
    BlobStoreRecord stored = blobStoreDao.findById(blobStoreDao.insert(record)).orElseThrow();
    refreshBlobStoreCatalog();
    return toView(stored, summary(stored), isConfigured(stored), "database");
  }

  @PutMapping("/{id}")
  public BlobStoreView update(@PathVariable("id") long id, @RequestBody BlobStoreRequest request) {
    BlobStoreRecord existing = blobStoreDao.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blob store not found"));
    BlobStoreRecord updated = toUpdateRecord(existing, request);
    validateFileRecord(updated);
    blobStoreDao.updateById(updated);
    refreshBlobStoreCatalog();
    BlobStoreRecord stored = blobStoreDao.findById(id).orElseThrow();
    return toView(stored, summary(stored), isConfigured(stored), "database");
  }

  private List<BlobStoreRecord> blobStoreRecords() {
    if (blobStorageRegistry == null) {
      return blobStoreDao.list();
    }
    return blobStorageRegistry.records();
  }

  private void refreshBlobStoreCatalog() {
    if (blobStorageRegistry != null) {
      blobStorageRegistry.refreshAllAndBroadcast();
    }
  }

  @PostMapping("/{id}/check")
  public BlobStoreProbeResult check(@PathVariable("id") long id) {
    BlobStoreRecord record = blobStoreDao.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Blob store not found"));
    if (isFileStore(record)) {
      return toProbeResult(fileBlobStoreAdmin.probeReadWrite(toFileConfig(record)));
    }
    return toProbeResult(s3BlobStoreAdmin.probeReadWrite(toS3Config(record)));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException error) {
    String message = error.getReason();
    if (message == null || message.isBlank()) {
      message = error.getStatusCode().toString();
    }
    return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", message));
  }

  private BlobStoreRecord toCreateRecord(String name, BlobStoreRequest request) {
    if (isFileRequest(request, null)) {
      return new BlobStoreRecord(
          null,
          name,
          FileBlobStoreConfig.TYPE,
          null,
          null,
          null,
          "",
          fileAttributes(required(request.path(), "path")));
    }
    String engine = normalizeS3Engine(valueOrDefault(request.engine(), s3Properties.getEngine()));
    String source = credentialSource(request.credentialSource(), engine);
    return new BlobStoreRecord(
        null,
        name,
        "s3",
        required(request.endpoint(), "endpoint"),
        valueOrDefault(request.region(), s3Properties.getRegion()),
        required(request.bucket(), "bucket"),
        valueOrDefault(request.prefix(), ""),
        s3Attributes(
            engine,
            createCredential(request.accessKey(), "accessKey", source),
            createCredential(request.secretKey(), "secretKey", source),
            request.pathStyleAccess() == null ? s3Properties.isPathStyleAccess() : request.pathStyleAccess(),
            request.multipartThresholdBytes() == null
                ? s3Properties.getMultipartThresholdBytes()
                : request.multipartThresholdBytes(),
            request.multipartPartSizeBytes() == null
                ? s3Properties.getMultipartPartSizeBytes()
                : request.multipartPartSizeBytes(),
            request.multipartConcurrency() == null
                ? s3Properties.getMultipartConcurrency()
                : request.multipartConcurrency()));
  }

  private BlobStoreRecord toUpdateRecord(BlobStoreRecord existing, BlobStoreRequest request) {
    if (isFileRequest(request, existing)) {
      return new BlobStoreRecord(
          existing.id(),
          existing.name(),
          FileBlobStoreConfig.TYPE,
          null,
          null,
          null,
          "",
          fileAttributes(valueOrDefault(
              request.path(),
              stringAttr(existing.attributes(), FileBlobStoreConfig.ATTR_PATH, ""))));
    }
    String engine = normalizeS3Engine(valueOrDefault(
        request.engine(), stringAttr(existing.attributes(), "engine", s3Properties.getEngine())));
    String source = credentialSource(request.credentialSource(), engine);
    return new BlobStoreRecord(
        existing.id(),
        existing.name(),
        "s3",
        required(request.endpoint(), "endpoint"),
        valueOrDefault(request.region(), s3Properties.getRegion()),
        required(request.bucket(), "bucket"),
        valueOrDefault(request.prefix(), ""),
        s3Attributes(
            engine,
            updateCredential(request.accessKey(), "accessKey", source, existing, s3Properties.getAccessKey()),
            updateCredential(request.secretKey(), "secretKey", source, existing, s3Properties.getSecretKey()),
            request.pathStyleAccess() == null
                ? boolAttr(existing.attributes(), "pathStyleAccess", s3Properties.isPathStyleAccess())
                : request.pathStyleAccess(),
            request.multipartThresholdBytes() == null
                ? longAttr(existing.attributes(), "multipartThresholdBytes", s3Properties.getMultipartThresholdBytes())
                : request.multipartThresholdBytes(),
            request.multipartPartSizeBytes() == null
                ? longAttr(existing.attributes(), "multipartPartSizeBytes", s3Properties.getMultipartPartSizeBytes())
                : request.multipartPartSizeBytes(),
            request.multipartConcurrency() == null
                ? intAttr(existing.attributes(), "multipartConcurrency", s3Properties.getMultipartConcurrency())
                : request.multipartConcurrency()));
  }

  private void validateFileRecord(BlobStoreRecord record) {
    if (!isFileStore(record)) {
      return;
    }
    try {
      validateFileStoragePolicy();
      FileBlobStoreConfig config = toFileConfig(record);
      List<FileBlobStoreConfig> existing = blobStoreDao.list().stream()
          .filter(BlobStoresController::isFileStore)
          .map(this::toFileConfig)
          .toList();
      filePathValidator.validate(config, existing);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  private void validateFileStoragePolicy() {
    var properties = fileBlobStorageFactory.properties();
    if (!properties.isEnabled()) {
      throw new IllegalArgumentException("File blob stores are disabled on this node");
    }
    if (isProductionProfile() && !properties.isProductionEnabled()) {
      throw new IllegalArgumentException(
          "File blob stores in production require kkrepo.storage.file.production-enabled=true");
    }
    if (isProductionProfile() && !properties.isSharedFilesystem()) {
      throw new IllegalArgumentException(
          "File blob stores in production require a strong-consistency shared filesystem such as NAS");
    }
  }

  private boolean isProductionProfile() {
    if (environment == null) {
      return false;
    }
    for (String profile : environment.getActiveProfiles()) {
      String normalized = normalize(profile);
      if ("prod".equals(normalized) || "production".equals(normalized)) {
        return true;
      }
    }
    return false;
  }

  private BlobStoreSummary summary(BlobStoreRecord record) {
    try {
      if (isFileStore(record)) {
        return toSummary(fileBlobStoreAdmin.summary(toFileConfig(record)));
      }
      return toSummary(s3BlobStoreAdmin.summary(toS3Config(record)));
    } catch (RuntimeException e) {
      return new BlobStoreSummary(
          record.name(),
          record.endpoint(),
          record.region(),
          record.bucket(),
          path(record),
          "",
          "Unavailable",
          false,
          0,
          0,
          e.getMessage());
    }
  }

  private S3BlobStoreConfig toS3Config(BlobStoreRecord record) {
    return new S3BlobStoreConfig(
        record.id() == null ? 0 : record.id(),
        record.name(),
        stringAttr(record.attributes(), "engine", s3Properties.getEngine()),
        valueOrDefault(record.endpoint(), s3Properties.getEndpoint()),
        valueOrDefault(record.region(), s3Properties.getRegion()),
        valueOrDefault(record.bucket(), s3Properties.getBucket()),
        valueOrDefault(record.prefix(), ""),
        S3BlobStoreConfig.credentialAttribute(record.attributes(), "accessKey", s3Properties.getAccessKey()),
        S3BlobStoreConfig.credentialAttribute(record.attributes(), "secretKey", s3Properties.getSecretKey()),
        boolAttr(record.attributes(), "pathStyleAccess", s3Properties.isPathStyleAccess()),
        intAttr(record.attributes(), "maxConnections", s3Properties.getMaxConnections()),
        intAttr(record.attributes(), "connectionTimeoutMs", s3Properties.getConnectionTimeoutMs()),
        intAttr(record.attributes(), "socketTimeoutMs", s3Properties.getSocketTimeoutMs()),
        intAttr(record.attributes(), "connectionAcquisitionTimeoutMs", s3Properties.getConnectionAcquisitionTimeoutMs()),
        boolAttr(record.attributes(), "tcpKeepAlive", s3Properties.isTcpKeepAlive()),
        longAttr(record.attributes(), "multipartThresholdBytes", s3Properties.getMultipartThresholdBytes()),
        longAttr(record.attributes(), "multipartPartSizeBytes", s3Properties.getMultipartPartSizeBytes()),
        intAttr(record.attributes(), "multipartConcurrency", s3Properties.getMultipartConcurrency()));
  }

  private FileBlobStoreConfig toFileConfig(BlobStoreRecord record) {
    return fileBlobStorageFactory.configFor(
        record.id() == null ? 0 : record.id(),
        record.name(),
        required(path(record), "path"));
  }

  private boolean isConfigured(BlobStoreRecord record) {
    return !isFileStore(record) && record.name().equals(s3Properties.getName());
  }

  private static Map<String, Object> s3Attributes(
      String engine,
      String accessKey,
      String secretKey,
      boolean pathStyleAccess,
      long multipartThresholdBytes,
      long multipartPartSizeBytes,
      int multipartConcurrency) {
    try {
      S3BlobStoreConfig.validateCredentials(engine, accessKey, secretKey);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("engine", normalizeS3Engine(engine));
    attributes.put("accessKey", accessKey);
    attributes.put("secretKey", secretKey);
    attributes.put("pathStyleAccess", pathStyleAccess);
    attributes.put("multipartThresholdBytes", Math.max(0, multipartThresholdBytes));
    attributes.put("multipartPartSizeBytes", Math.max(5L * 1024 * 1024, multipartPartSizeBytes));
    attributes.put("multipartConcurrency", Math.max(1, multipartConcurrency));
    return attributes;
  }

  private static String credentialSource(String value, String engine) {
    String source = normalize(value);
    if (!source.isEmpty() && !source.equals("static") && !source.equals("default")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credentialSource must be static or default");
    }
    if (source.equals("default") && Engine.fromValue(engine) == Engine.OSS_NATIVE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "oss-native requires static credentials");
    }
    return source;
  }

  private static String createCredential(String value, String field, String source) {
    if (source.equals("default")) return "";
    return source.equals("static") ? required(value, field) : valueOrDefault(value, "");
  }

  private static String updateCredential(
      String value, String field, String source, BlobStoreRecord existing, String fallback) {
    if (source.equals("default")) return "";
    // Preserve the existing edit contract: blank input keeps the saved secret. An explicit
    // credentialSource=default clears both attributes, including any legacy global fallback.
    String resolved = valueOrDefault(value,
        S3BlobStoreConfig.credentialAttribute(existing.attributes(), field, fallback));
    return source.equals("static") ? required(resolved, field) : resolved;
  }

  private static Map<String, Object> fileAttributes(String path) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("engine", FileBlobStoreConfig.ENGINE);
    attributes.put(FileBlobStoreConfig.ATTR_PATH, path);
    return attributes;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value.trim();
  }

  private static String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String stringAttr(Map<String, Object> attributes, String key, String fallback) {
    if (attributes == null) return fallback;
    Object value = attributes.get(key);
    return value == null || value.toString().isBlank() ? fallback : value.toString();
  }

  private static boolean boolAttr(Map<String, Object> attributes, String key, boolean fallback) {
    if (attributes == null) return fallback;
    Object value = attributes.get(key);
    if (value == null) return fallback;
    if (value instanceof Boolean b) return b;
    return Boolean.parseBoolean(value.toString());
  }

  private static int intAttr(Map<String, Object> attributes, String key, int fallback) {
    if (attributes == null) return fallback;
    Object value = attributes.get(key);
    if (value == null) return fallback;
    if (value instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(value.toString());
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private static long longAttr(Map<String, Object> attributes, String key, long fallback) {
    if (attributes == null) return fallback;
    Object value = attributes.get(key);
    if (value == null) return fallback;
    if (value instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(value.toString());
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private BlobStoreView toView(
      BlobStoreRecord record,
      BlobStoreSummary summary,
      boolean configured,
      String source) {
    boolean fileStore = isFileStore(record);
    String engine = fileStore
        ? FileBlobStoreConfig.ENGINE
        : normalizeS3Engine(stringAttr(record.attributes(), "engine", s3Properties.getEngine()));
    boolean accessConfigured = !fileStore && !S3BlobStoreConfig.credentialAttribute(
        record.attributes(), "accessKey", s3Properties.getAccessKey()).isBlank();
    boolean secretConfigured = !fileStore && !S3BlobStoreConfig.credentialAttribute(
        record.attributes(), "secretKey", s3Properties.getSecretKey()).isBlank();
    return new BlobStoreView(
        record.id(),
        record.name(),
        record.type(),
        engine,
        lifecycleState(record, configured, summary),
        record.endpoint(),
        record.region(),
        record.bucket(),
        record.prefix(),
        fileStore ? path(record) : "",
        summary == null ? "" : summary.resolvedPath(),
        fileStore ? false : boolAttr(record.attributes(), "pathStyleAccess", s3Properties.isPathStyleAccess()),
        fileStore ? 0 : longAttr(record.attributes(), "multipartThresholdBytes", s3Properties.getMultipartThresholdBytes()),
        fileStore ? 0 : longAttr(record.attributes(), "multipartPartSizeBytes", s3Properties.getMultipartPartSizeBytes()),
        fileStore ? 0 : intAttr(record.attributes(), "multipartConcurrency", s3Properties.getMultipartConcurrency()),
        configured,
        source,
        summary != null && summary.bucketExists(),
        summary == null ? 0 : summary.objectCount(),
        summary == null ? 0 : summary.totalSize(),
        "",
        accessConfigured,
        secretConfigured,
        summary == null ? "" : summary.message(),
        fileStore ? "" : (accessConfigured || secretConfigured ? "static" : "default"));
  }

  private static String lifecycleState(BlobStoreRecord record, boolean configured, BlobStoreSummary summary) {
    if (record.id() == null && configured) {
      return "Configured";
    }
    if (summary != null && summary.state() != null && !summary.state().isBlank()) {
      return summary.state();
    }
    return "Started";
  }

  private static BlobStoreSummary toSummary(S3BucketSummary summary) {
    return new BlobStoreSummary(
        summary.name(),
        summary.endpoint(),
        summary.region(),
        summary.bucket(),
        "",
        "",
        summary.state(),
        summary.bucketExists(),
        summary.objectCount(),
        summary.totalSize(),
        summary.message());
  }

  private static BlobStoreSummary toSummary(FileBlobStoreSummary summary) {
    return new BlobStoreSummary(
        summary.name(),
        "",
        "",
        "",
        summary.path(),
        summary.resolvedPath(),
        summary.state(),
        summary.exists(),
        summary.objectCount(),
        summary.totalSize(),
        summary.message());
  }

  private static BlobStoreProbeResult toProbeResult(S3ProbeResult result) {
    return new BlobStoreProbeResult(
        result.ok(),
        result.objectKey(),
        result.message(),
        toSummary(result.summary()));
  }

  private static BlobStoreProbeResult toProbeResult(FileProbeResult result) {
    return new BlobStoreProbeResult(
        result.ok(),
        result.objectKey(),
        result.message(),
        toSummary(result.summary()));
  }

  private static boolean isFileRequest(BlobStoreRequest request, BlobStoreRecord existing) {
    if (FileBlobStoreConfig.TYPE.equals(normalize(request.type()))
        || FileBlobStoreConfig.ENGINE.equals(normalize(request.engine()))) {
      return true;
    }
    if (request.type() == null && request.engine() == null && existing != null) {
      return isFileStore(existing);
    }
    return false;
  }

  private static boolean isFileStore(BlobStoreRecord record) {
    return FileBlobStoreConfig.isFileStore(record.type(), record.attributes());
  }

  private static String path(BlobStoreRecord record) {
    return stringAttr(record.attributes(), FileBlobStoreConfig.ATTR_PATH, "");
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeS3Engine(String value) {
    return Engine.fromValue(value).id();
  }

  public record BlobStoresResponse(List<BlobStoreView> stores) {
  }

  public record BlobStoreView(
      Long id,
      String name,
      String type,
      String engine,
      String state,
      String endpoint,
      String region,
      String bucket,
      String prefix,
      String path,
      String resolvedPath,
      boolean pathStyleAccess,
      long multipartThresholdBytes,
      long multipartPartSizeBytes,
      int multipartConcurrency,
      boolean configured,
      String source,
      boolean bucketExists,
      long objectCount,
      long totalSize,
      String accessKey,
      boolean accessKeyConfigured,
      boolean secretConfigured,
      String message,
      String credentialSource) {
  }

  public record BlobStoreRequest(
      String name,
      String type,
      String engine,
      String endpoint,
      String region,
      String bucket,
      String prefix,
      String path,
      String accessKey,
      String secretKey,
      Boolean pathStyleAccess,
      Long multipartThresholdBytes,
      Long multipartPartSizeBytes,
      Integer multipartConcurrency,
      String credentialSource) {
    public BlobStoreRequest(
        String name, String type, String engine, String endpoint, String region, String bucket,
        String prefix, String path, String accessKey, String secretKey, Boolean pathStyleAccess,
        Long multipartThresholdBytes, Long multipartPartSizeBytes, Integer multipartConcurrency) {
      this(name, type, engine, endpoint, region, bucket, prefix, path, accessKey, secretKey, pathStyleAccess,
          multipartThresholdBytes, multipartPartSizeBytes, multipartConcurrency, null);
    }

    public BlobStoreRequest(
        String name,
        String type,
        String engine,
        String endpoint,
        String region,
        String bucket,
        String prefix,
        String path,
        String accessKey,
        String secretKey,
        Boolean pathStyleAccess) {
      this(name, type, engine, endpoint, region, bucket, prefix, path, accessKey, secretKey, pathStyleAccess,
          null, null, null, null);
    }
  }

  public record BlobStoreSummary(
      String name,
      String endpoint,
      String region,
      String bucket,
      String path,
      String resolvedPath,
      String state,
      boolean bucketExists,
      long objectCount,
      long totalSize,
      String message) {
  }

  public record BlobStoreProbeResult(
      boolean ok,
      String objectKey,
      String message,
      BlobStoreSummary summary) {
  }
}
