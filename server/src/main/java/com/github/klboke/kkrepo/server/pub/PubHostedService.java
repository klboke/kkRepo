package com.github.klboke.kkrepo.server.pub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.core.BlobReference;
import com.github.klboke.kkrepo.core.BlobStorage;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.mysql.dao.AssetDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.ComponentDao;
import com.github.klboke.kkrepo.persistence.mysql.dao.PubUploadSessionDao;
import com.github.klboke.kkrepo.persistence.mysql.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.mysql.model.PubUploadSessionRecord;
import com.github.klboke.kkrepo.protocol.maven.policy.WritePolicy;
import com.github.klboke.kkrepo.protocol.pub.PubPackageName;
import com.github.klboke.kkrepo.protocol.pub.PubContentTypes;
import com.github.klboke.kkrepo.protocol.pub.PubPath;
import com.github.klboke.kkrepo.protocol.pub.PubPaths;
import com.github.klboke.kkrepo.protocol.pub.PubVersions;
import com.github.klboke.kkrepo.server.blob.BlobReferenceCodec;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.CachedAssetMetadata;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PubHostedService {
  private static final Duration UPLOAD_SESSION_TTL = Duration.ofMinutes(30);

  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final PubUploadSessionDao uploadSessionDao;
  private final BlobStorageRegistry blobStorageRegistry;
  private final PubAssetWriter writer;
  private final PubAssetReader reader;
  private final AssetMetadataCache assetMetadataCache;
  private final ObjectMapper objectMapper;

  public PubHostedService(
      AssetDao assetDao,
      ComponentDao componentDao,
      PubUploadSessionDao uploadSessionDao,
      BlobStorageRegistry blobStorageRegistry,
      PubAssetWriter writer,
      PubAssetReader reader,
      AssetMetadataCache assetMetadataCache,
      ObjectMapper objectMapper) {
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.uploadSessionDao = uploadSessionDao;
    this.blobStorageRegistry = blobStorageRegistry;
    this.writer = writer;
    this.reader = reader;
    this.assetMetadataCache = assetMetadataCache;
    this.objectMapper = objectMapper;
  }

  public MavenResponse get(RepositoryRuntime runtime, PubPath path, String baseUrl, boolean headOnly) {
    ensureHosted(runtime);
    return switch (path.kind()) {
      case PACKAGE_METADATA -> packageMetadata(runtime, path.packageName(), baseUrl, headOnly);
      case VERSION_METADATA -> versionMetadata(runtime, path.packageName(), path.version(), baseUrl, headOnly);
      case VERSION_JSON -> versionJson(runtime, path.packageName(), path.version(), baseUrl, headOnly);
      case ARCHIVE -> download(runtime, path.packageName(), path.version(), headOnly);
      case PUBLISH_INIT -> initPublish(runtime, baseUrl, null, null, headOnly);
      case PACKAGE_NAMES, PACKAGE_NAME_COMPLETION -> packageNames(runtime, headOnly);
      case ADVISORIES -> throw new PubExceptions.PubNotFoundException(path.rawPath());
      default -> throw new PubExceptions.PubNotFoundException(path.rawPath());
    };
  }

  @Transactional
  public MavenResponse initPublish(
      RepositoryRuntime runtime,
      String baseUrl,
      String principalUserId,
      Long principalApiKeyId,
      boolean headOnly) {
    ensureHosted(runtime);
    enforceWritePolicy(runtime);
    String sessionId = UUID.randomUUID().toString();
    String fieldToken = UUID.randomUUID().toString();
    Instant now = Instant.now();
    uploadSessionDao.insert(new PubUploadSessionRecord(
        null,
        runtime.id(),
        sessionId,
        fieldToken,
        principalUserId,
        principalApiKeyId,
        PubUploadSessionDao.STATUS_NEW,
        now.plus(UPLOAD_SESSION_TTL),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Map.of(),
        null,
        null,
        now,
        now));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("url", baseUrl + "/api/packages/versions/upload/" + sessionId);
    body.put("fields", Map.of("session", sessionId, "token", fieldToken));
    return PubResponses.json(objectMapper, body, 200, headOnly);
  }

  public MavenResponse upload(
      RepositoryRuntime runtime,
      String sessionId,
      Map<String, String> fields,
      Part file,
      String baseUrl,
      String principalUserId,
      Long principalApiKeyId) throws IOException {
    ensureHosted(runtime);
    enforceWritePolicy(runtime);
    if (file == null || file.getSize() <= 0) {
      throw new PubExceptions.BadRequestException("Pub upload requires multipart file field 'file'");
    }
    PubUploadSessionRecord session = uploadSessionDao.find(runtime.id(), sessionId)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException("Pub upload session not found: " + sessionId));
    validateSessionUsable(session, PubUploadSessionDao.STATUS_NEW, principalUserId, principalApiKeyId);
    validateFieldToken(session, fields);

    PubAssetWriter.StagedArchive staged;
    try (InputStream body = file.getInputStream()) {
      staged = writer.stageArchive(runtime, blobStorage(runtime), sessionId, body, null, null);
    }
    try {
      return markUploaded(runtime, sessionId, staged, baseUrl);
    } catch (RuntimeException e) {
      blobStorage(runtime).delete(staged.reference());
      throw e;
    }
  }

  @Transactional
  MavenResponse markUploaded(
      RepositoryRuntime runtime,
      String sessionId,
      PubAssetWriter.StagedArchive staged,
      String baseUrl) {
    PubUploadSessionRecord locked = uploadSessionDao.lock(runtime.id(), sessionId)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException("Pub upload session not found: " + sessionId));
    validateSessionUsable(locked, PubUploadSessionDao.STATUS_NEW, locked.principalUserId(), locked.principalApiKeyId());
    uploadSessionDao.markUploaded(
        locked.id(),
        requireBlobStore(runtime),
        BlobReferenceCodec.format(staged.reference()),
        staged.reference().objectKey(),
        staged.digests().md5(),
        staged.digests().sha1(),
        staged.digests().sha256(),
        staged.digests().sha512(),
        staged.digests().size(),
        staged.metadata().packageName(),
        staged.metadata().version(),
        staged.metadata().pubspec());
    return MavenResponse.noBody(204)
        .withHeader("Location", baseUrl + "/api/packages/versions/finalize/" + sessionId);
  }

  @Transactional
  public MavenResponse finalizeUpload(
      RepositoryRuntime runtime,
      String sessionId,
      String principalUserId,
      Long principalApiKeyId,
      String sourceClient,
      boolean headOnly) {
    ensureHosted(runtime);
    enforceWritePolicy(runtime);
    PubUploadSessionRecord session = uploadSessionDao.lock(runtime.id(), sessionId)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException("Pub upload session not found: " + sessionId));
    validateSessionUsable(session, PubUploadSessionDao.STATUS_UPLOADED, principalUserId, principalApiKeyId);
    if (PubUploadSessionDao.STATUS_FINALIZED.equals(session.status())) {
      return publishSuccess(headOnly);
    }
    BlobReference reference = BlobReferenceCodec.reference(
        session.blobRef(), session.objectKey(), session.sha256(), session.size());
    PubAssetWriter.Digests digests = new PubAssetWriter.Digests(
        session.md5(), session.sha1(), session.sha256(), session.sha512(), session.size());
    PubPackageMetadata metadata = new PubPackageMetadata(
        session.packageName(), session.version(), session.pubspec());
    writer.persistStagedArchive(runtime, blobStorage(runtime), requireBlobStore(runtime), reference,
        digests, metadata,
        publishAttributes("pub-client", principalUserId, principalApiKeyId, session.sessionId(), sourceClient),
        principalUserId, null);
    uploadSessionDao.markFinalized(session.id(), Instant.now());
    return publishSuccess(headOnly);
  }

  public String uploadArchive(
      RepositoryRuntime runtime,
      InputStream body,
      String createdBy,
      String createdByIp,
      String sourceClient) {
    ensureHosted(runtime);
    enforceWritePolicy(runtime);
    PubAssetWriter.Stored stored = writer.writeArchive(
        runtime,
        blobStorage(runtime),
        requireBlobStore(runtime),
        body,
        null,
        null,
        null,
        publishAttributes("component-upload", createdBy, null, null, sourceClient),
        Map.of(),
        createdBy,
        createdByIp,
        false,
        false);
    stored.discardBody();
    return stored.asset().path();
  }

  private static Map<String, Object> publishAttributes(
      String source,
      String principalUserId,
      Long principalApiKeyId,
      String uploadSessionId,
      String sourceClient) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    putNonBlank(attrs, "publishSource", source);
    putNonBlank(attrs, "publishedBy", principalUserId);
    if (principalApiKeyId != null) {
      attrs.put("publishApiKeyId", principalApiKeyId);
    }
    putNonBlank(attrs, "uploadSessionId", uploadSessionId);
    putNonBlank(attrs, "sourceClient", sourceClient);
    return attrs;
  }

  private static void putNonBlank(Map<String, Object> attrs, String key, String value) {
    if (value != null && !value.isBlank()) {
      attrs.put(key, value);
    }
  }

  MavenResponse packageMetadata(RepositoryRuntime runtime, String packageName, String baseUrl, boolean headOnly) {
    String normalized = PubPackageName.require(packageName);
    List<ComponentRecord> components = componentDao.listByName(runtime.id(), normalized);
    if (components.isEmpty()) {
      throw new PubExceptions.PubNotFoundException(normalized);
    }
    return PubResponses.json(objectMapper, packageMetadataBody(normalized, components, baseUrl),
        200, null, latestUpdatedAt(components), headOnly);
  }

  MavenResponse versionMetadata(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      String baseUrl,
      boolean headOnly) {
    String normalized = PubPackageName.require(packageName);
    String safeVersion = PubVersions.require(version);
    ComponentRecord component = componentDao.findByNameAndVersion(runtime.id(), normalized, safeVersion)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(normalized + " " + safeVersion));
    return PubResponses.json(objectMapper, versionEntry(component, baseUrl),
        200, null, component.lastUpdatedAt(), headOnly);
  }

  MavenResponse versionJson(
      RepositoryRuntime runtime,
      String packageName,
      String version,
      String baseUrl,
      boolean headOnly) {
    String normalized = PubPackageName.require(packageName);
    String safeVersion = PubVersions.require(version);
    ComponentRecord component = componentDao.findByNameAndVersion(runtime.id(), normalized, safeVersion)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(normalized + " " + safeVersion));
    return PubResponses.json(objectMapper, versionEntry(component, baseUrl),
        200, null, component.lastUpdatedAt(), PubContentTypes.VERSION_JSON, headOnly);
  }

  MavenResponse download(RepositoryRuntime runtime, String packageName, String version, boolean headOnly) {
    String path = PubPaths.archivePath(packageName, version);
    CachedAssetMetadata snapshot = lookupCached(runtime, path)
        .orElseThrow(() -> new PubExceptions.PubNotFoundException(path));
    return reader.serveSnapshot(snapshot, headOnly, path);
  }

  Map<String, Object> packageMetadataBody(String packageName, List<ComponentRecord> components, String baseUrl) {
    List<ComponentRecord> sorted = new ArrayList<>(components);
    sorted.sort(Comparator.comparing(ComponentRecord::version, PubVersions.COMPARATOR));
    List<Map<String, Object>> versions = sorted.stream()
        .map(component -> versionEntry(component, baseUrl))
        .toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", packageName);
    body.put("latest", PubMetadataSupport.latestStableFirst(
        versions, entry -> String.valueOf(entry.get("version"))));
    body.put("versions", versions);
    return body;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> versionEntry(ComponentRecord component, String baseUrl) {
    Map<String, Object> attrs = component.attributes() == null ? Map.of() : component.attributes();
    Map<String, Object> pubspec = attrs.get("pubspec") instanceof Map<?, ?> map
        ? new LinkedHashMap<>((Map<String, Object>) map)
        : Map.of("name", component.name(), "version", component.version());
    String sha256 = text(attrs.get("archiveSha256"));
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("version", component.version());
    entry.put("archive_url", baseUrl + "/" + PubPaths.apiArchivePath(component.name(), component.version()));
    if (sha256 != null) {
      entry.put("archive_sha256", sha256);
    }
    entry.put("pubspec", pubspec);
    Object published = attrs.get("publishedAt");
    if (published != null) {
      entry.put("published", String.valueOf(published));
    }
    return entry;
  }

  private MavenResponse packageNames(RepositoryRuntime runtime, boolean headOnly) {
    Map<String, Object> body = Map.of(
        "packages", componentDao.listDistinctNamesByRepositoryId(runtime.id()));
    return PubResponses.json(objectMapper, body, 200, headOnly);
  }

  private Optional<CachedAssetMetadata> lookupCached(RepositoryRuntime runtime, String path) {
    return assetMetadataCache.find(
        runtime.id(), path,
        () -> AssetMetadataCache.Loaded.from(assetDao.findAssetByPath(runtime.id(), path), assetDao));
  }

  private MavenResponse publishSuccess(boolean headOnly) {
    return PubResponses.json(objectMapper,
        Map.of("success", Map.of("message", "Successfully uploaded package.")),
        200,
        headOnly);
  }

  static void validateSessionUsable(
      PubUploadSessionRecord session,
      String expectedStatus,
      String principalUserId,
      Long principalApiKeyId) {
    if (session.principalUserId() != null && principalUserId != null
        && !session.principalUserId().equals(principalUserId)) {
      throw new PubExceptions.BadRequestException("Pub upload session belongs to another principal");
    }
    if (session.principalApiKeyId() != null && principalApiKeyId != null
        && !session.principalApiKeyId().equals(principalApiKeyId)) {
      throw new PubExceptions.BadRequestException("Pub upload session belongs to another token");
    }
    if (PubUploadSessionDao.STATUS_FINALIZED.equals(session.status())
        && PubUploadSessionDao.STATUS_UPLOADED.equals(expectedStatus)) {
      return;
    }
    if (session.expiresAt() == null || session.expiresAt().isBefore(Instant.now())) {
      throw new PubExceptions.BadRequestException("Pub upload session expired");
    }
    if (!expectedStatus.equals(session.status())) {
      throw new PubExceptions.BadRequestException("Pub upload session is not " + expectedStatus);
    }
  }

  static void validateFieldToken(PubUploadSessionRecord session, Map<String, String> fields) {
    String token = fields == null ? null : fields.get("token");
    if (token == null || !token.equals(session.fieldToken())) {
      throw new PubExceptions.BadRequestException("Invalid Pub upload form token");
    }
  }

  private Instant latestUpdatedAt(List<ComponentRecord> components) {
    return components.stream()
        .map(ComponentRecord::lastUpdatedAt)
        .filter(value -> value != null)
        .max(Instant::compareTo)
        .orElse(null);
  }

  private void enforceWritePolicy(RepositoryRuntime runtime) {
    WritePolicy policy = WritePolicy.parse(runtime.writePolicy());
    if (policy == WritePolicy.DENY) {
      throw new PubExceptions.WritePolicyDenied("Write policy DENY forbids Pub publish");
    }
  }

  private void ensureHosted(RepositoryRuntime runtime) {
    if (runtime.format() != RepositoryFormat.PUB || !runtime.isHosted()) {
      throw new PubExceptions.MethodNotAllowed("Operation is only valid on hosted Pub repositories");
    }
  }

  private BlobStorage blobStorage(RepositoryRuntime runtime) {
    return blobStorageRegistry.forBlobStoreId(requireBlobStore(runtime));
  }

  private long requireBlobStore(RepositoryRuntime runtime) {
    Long id = runtime.blobStoreId();
    if (id == null) {
      throw new IllegalStateException("Pub repository " + runtime.name() + " has no blob store assigned");
    }
    return id;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() ? null : text;
  }
}
