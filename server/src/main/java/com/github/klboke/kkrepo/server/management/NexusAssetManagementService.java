package com.github.klboke.kkrepo.server.management;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.DecodedAssetId;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.DecodedContinuation;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

@Service
public class NexusAssetManagementService {
  static final int PAGE_SIZE = 50;
  private static final int FETCH_SIZE = PAGE_SIZE + 1;

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final ComponentDao componentDao;
  private final BlobStoreDao blobStoreDao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final RawHostedService rawHostedService;
  private final NexusRepositoryManagementAuthorizer authorizer;
  private final NexusAssetIdCodec idCodec;
  private final AssetPublicIdService publicIdService;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;
  private final MavenPathParser mavenPathParser = new MavenPathParser();

  public NexusAssetManagementService(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      ComponentDao componentDao,
      BlobStoreDao blobStoreDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      RawHostedService rawHostedService,
      NexusRepositoryManagementAuthorizer authorizer,
      NexusAssetIdCodec idCodec,
      AssetPublicIdService publicIdService,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.componentDao = componentDao;
    this.blobStoreDao = blobStoreDao;
    this.runtimeRegistry = runtimeRegistry;
    this.rawHostedService = rawHostedService;
    this.authorizer = authorizer;
    this.idCodec = idCodec;
    this.publicIdService = publicIdService;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  @Transactional
  public AssetPage search(
      String repositoryName,
      String name,
      String continuationToken,
      HttpServletRequest request) {
    return search(repositoryName, name, null, continuationToken, request);
  }

  @Transactional
  public AssetPage search(
      String repositoryName,
      String name,
      String format,
      String continuationToken,
      HttpServletRequest request) {
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new InvalidSearchRequestException("repository is required for asset search");
    }
    authorizer.requireSearch(request);
    RepositoryFormat requestedFormat = parseFormat(format);
    if (format != null && !format.isBlank() && requestedFormat == null) {
      return new AssetPage(List.of(), null);
    }
    RepositoryRecord repository = repositoryDao.findByName(repositoryName).orElse(null);
    if (repository == null) {
      return new AssetPage(List.of(), null);
    }
    if (repository.type() == RepositoryType.GROUP) {
      throw new InvalidSearchRequestException(
          "Asset search does not support group repositories");
    }
    if (requestedFormat != null && requestedFormat != repository.format()) {
      return new AssetPage(List.of(), null);
    }

    long afterAssetId = 0;
    if (continuationToken != null && !continuationToken.isBlank()) {
      DecodedContinuation continuation = idCodec.decodeContinuation(continuationToken);
      if (continuation.repositoryId() != repository.id()) {
        throw new InvalidSearchRequestException("continuationToken belongs to another repository");
      }
      afterAssetId = continuation.lastAssetId();
    }
    return searchPage(repository, blankToNull(name), afterAssetId, request);
  }

  private AssetPage searchPage(
      RepositoryRecord repository,
      String componentName,
      long afterAssetId,
      HttpServletRequest request) {
    List<AssetSummaryView> items = new ArrayList<>(PAGE_SIZE);
    long cursor = Math.max(0, afterAssetId);
    while (items.size() < PAGE_SIZE) {
      long batchStart = cursor;
      List<AssetWithBlob> stored = loadPage(repository, componentName, cursor);
      if (stored.isEmpty()) {
        break;
      }
      for (int index = 0; index < stored.size(); index++) {
        AssetWithBlob candidate = stored.get(index);
        AssetRecord asset = candidate.asset();
        if (asset.id() == null || asset.id() <= cursor) {
          continue;
        }
        cursor = asset.id();
        if (belongsTo(asset, repository)
            && authorizer.repositoryActionAllowed(
                request, repository, asset.path(), PermissionAction.BROWSE)) {
          items.add(toSummary(repository, candidate, request));
        }
        if (items.size() == PAGE_SIZE) {
          long pageCursor = cursor;
          String next = hasVisibleAfter(
              repository, componentName, stored, index + 1, cursor, request)
                  ? idCodec.encodeContinuation(repository.id(), pageCursor) : null;
          return new AssetPage(List.copyOf(items), next);
        }
      }
      if (cursor == batchStart || stored.size() < FETCH_SIZE) {
        break;
      }
    }
    return new AssetPage(List.copyOf(items), null);
  }

  private List<AssetWithBlob> loadPage(
      RepositoryRecord repository, String componentName, long afterAssetId) {
    return componentName == null
        ? assetDao.listAssetWithBlobPage(repository.id(), afterAssetId, FETCH_SIZE)
        : assetDao.listAssetWithBlobPageByComponentName(
            repository.id(), componentName, afterAssetId, FETCH_SIZE);
  }

  private boolean hasVisibleAfter(
      RepositoryRecord repository,
      String componentName,
      List<AssetWithBlob> initialBatch,
      int initialIndex,
      long afterAssetId,
      HttpServletRequest request) {
    List<AssetWithBlob> batch = initialBatch;
    int startIndex = initialIndex;
    long cursor = afterAssetId;
    boolean loadedPage = false;
    while (true) {
      long batchStart = cursor;
      for (int index = startIndex; index < batch.size(); index++) {
        AssetRecord asset = batch.get(index).asset();
        if (asset.id() == null || asset.id() <= cursor) {
          continue;
        }
        cursor = asset.id();
        if (belongsTo(asset, repository)
            && authorizer.repositoryActionAllowed(
                request, repository, asset.path(), PermissionAction.BROWSE)) {
          return true;
        }
      }
      if (batch.size() < FETCH_SIZE || (loadedPage && cursor == batchStart)) {
        return false;
      }
      batch = loadPage(repository, componentName, cursor);
      if (batch.isEmpty()) {
        return false;
      }
      startIndex = 0;
      loadedPage = true;
    }
  }

  @Transactional
  public AssetDetailView get(String encodedId, HttpServletRequest request) {
    DecodedAssetId decoded = idCodec.decodeAssetId(encodedId);
    RepositoryRecord repository = repositoryDao.findByName(decoded.repositoryName())
        .orElseThrow(AssetNotFoundException::new);
    Long assetId = publicIdService.resolveAssetId(repository.id(), decoded.opaqueId());
    if (assetId == null) {
      throw new AssetNotFoundException();
    }
    AssetWithBlob stored = assetDao.findAssetWithBlobById(assetId)
        .filter(candidate -> belongsTo(candidate.asset(), repository))
        .orElseThrow(AssetNotFoundException::new);
    authorizer.requireRepositoryAction(
        request, repository, stored.asset().path(), PermissionAction.READ);
    return toDetail(repository, stored, request);
  }

  public int delete(String encodedId, HttpServletRequest request) {
    DecodedAssetId decoded = idCodec.decodeAssetId(encodedId);
    RepositoryRecord repository = repositoryDao.findByName(decoded.repositoryName())
        .orElseThrow(AssetNotFoundException::new);
    Long assetId = publicIdService.resolveAssetId(repository.id(), decoded.opaqueId());
    if (assetId == null) {
      throw new AssetNotFoundException();
    }
    AssetRecord asset = assetDao.findAssetById(assetId)
        .filter(candidate -> belongsTo(candidate, repository))
        .orElseThrow(AssetNotFoundException::new);
    authorizer.requireRepositoryAction(request, repository, asset.path(), PermissionAction.DELETE);
    if (repository.format() != RepositoryFormat.RAW || repository.type() != RepositoryType.HOSTED) {
      throw new UnsupportedAssetDeleteException(
          "Asset deletion by ID is currently supported only for Raw hosted repositories");
    }
    RepositoryRuntime runtime = runtimeRegistry.resolve(repository.name())
        .orElseThrow(AssetNotFoundException::new);
    MavenResponse response = rawHostedService.deleteById(runtime, asset.id());
    return response.status();
  }

  private AssetSummaryView toSummary(
      RepositoryRecord repository, AssetWithBlob stored, HttpServletRequest request) {
    AssetRecord asset = stored.asset();
    return new AssetSummaryView(
        downloadUrl(repository.name(), asset.path(), request),
        asset.path(),
        publicIdService.nativePublicId(repository.name(), repository.id(), asset.id()),
        repository.name(),
        repository.format().id(),
        checksums(stored.blob()));
  }

  AssetSummaryView componentSearchSummary(
      RepositoryRecord repository, AssetWithBlob stored, HttpServletRequest request) {
    return toSummary(repository, stored, request);
  }

  private AssetDetailView toDetail(
      RepositoryRecord repository, AssetWithBlob stored, HttpServletRequest request) {
    AssetRecord asset = stored.asset();
    AssetBlobRecord blob = stored.blob();
    AssetSummaryView summary = toSummary(repository, stored, request);
    return new AssetDetailView(
        summary.downloadUrl(),
        summary.path(),
        summary.id(),
        summary.repository(),
        summary.format(),
        summary.checksum(),
        firstNonBlank(asset.contentType(), blob == null ? null : blob.contentType()),
        asset.lastUpdatedAt(),
        asset.lastDownloadedAt(),
        blob == null ? null : blob.createdBy(),
        blob == null ? null : blob.createdByIp(),
        asset.size() != null ? asset.size() : blob == null ? null : blob.size(),
        blob == null ? null : blob.blobCreatedAt(),
        blobStoreName(repository, blob),
        Map.of(repository.format().id(), formatAttributes(repository, asset)));
  }

  private String blobStoreName(RepositoryRecord repository, AssetBlobRecord blob) {
    Long blobStoreId = blob == null ? repository.blobStoreId() : blob.blobStoreId();
    if (blobStoreId == null) {
      return null;
    }
    return blobStoreDao.findById(blobStoreId).map(record -> record.name()).orElse(null);
  }

  private Map<String, Object> formatAttributes(
      RepositoryRecord repository, AssetRecord asset) {
    ComponentRecord component = asset.componentId() == null
        ? null
        : componentDao.findById(asset.componentId())
            .filter(candidate -> candidate.repositoryId() == repository.id()
                && candidate.format() == repository.format())
            .orElse(null);
    if (repository.format() == RepositoryFormat.MAVEN2) {
      return mavenAttributes(asset, component);
    }
    if (repository.format() == RepositoryFormat.RAW) {
      return Map.of();
    }
    return component == null || component.attributes() == null
        ? Map.of()
        : Map.copyOf(component.attributes());
  }

  private Map<String, Object> mavenAttributes(AssetRecord asset, ComponentRecord component) {
    Coordinates coordinates = mavenPathParser.parsePath(asset.path()).coordinates();
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    putIfPresent(attributes, "extension", coordinates == null ? null : coordinates.extension());
    putIfPresent(attributes, "groupId",
        component == null ? coordinates == null ? null : coordinates.groupId() : component.namespace());
    putIfPresent(attributes, "artifactId",
        component == null ? coordinates == null ? null : coordinates.artifactId() : component.name());
    putIfPresent(attributes, "version",
        component == null ? coordinates == null ? null : coordinates.baseVersion() : component.version());
    putIfPresent(attributes, "classifier", coordinates == null ? null : coordinates.classifier());
    return Map.copyOf(attributes);
  }

  private String downloadUrl(
      String repositoryName, String path, HttpServletRequest request) {
    String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    return forwardedHeaderPolicy.serverBaseUrl(request)
        + contextPath
        + "/repository/"
        + UriUtils.encodePathSegment(repositoryName, StandardCharsets.UTF_8)
        + "/"
        + encodePath(path);
  }

  private static String encodePath(String path) {
    if (path == null || path.isEmpty()) {
      return "";
    }
    return java.util.Arrays.stream(path.split("/", -1))
        .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("/"));
  }

  private static Map<String, String> checksums(AssetBlobRecord blob) {
    if (blob == null) {
      return Map.of();
    }
    LinkedHashMap<String, String> checksums = new LinkedHashMap<>();
    putIfPresent(checksums, "sha1", blob.sha1());
    putIfPresent(checksums, "sha256", blob.sha256());
    putIfPresent(checksums, "md5", blob.md5());
    return Map.copyOf(checksums);
  }

  private static <T> void putIfPresent(Map<String, T> target, String key, T value) {
    if (value instanceof String text && text.isBlank()) {
      return;
    }
    if (value != null) {
      target.put(key, value);
    }
  }

  private static String firstNonBlank(String first, String second) {
    return first == null || first.isBlank() ? second : first;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static RepositoryFormat parseFormat(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return RepositoryFormat.fromJson(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static boolean belongsTo(AssetRecord asset, RepositoryRecord repository) {
    return asset.id() != null
        && asset.repositoryId() == repository.id()
        && asset.format() == repository.format();
  }

  public record AssetSummaryView(
      String downloadUrl,
      String path,
      String id,
      String repository,
      String format,
      Map<String, String> checksum) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record AssetDetailView(
      String downloadUrl,
      String path,
      String id,
      String repository,
      String format,
      Map<String, String> checksum,
      String contentType,
      Instant lastModified,
      Instant lastDownloaded,
      String uploader,
      String uploaderIp,
      Long fileSize,
      Instant blobCreated,
      String blobStoreName,
      @JsonIgnore Map<String, Object> formatAttributes) {

    @JsonIgnore
    @Override
    public Map<String, Object> formatAttributes() {
      return formatAttributes;
    }

    @JsonAnyGetter
    public Map<String, Object> formatSpecificAttributes() {
      return formatAttributes;
    }
  }

  public record AssetPage(
      List<AssetSummaryView> items,
      @JsonInclude(JsonInclude.Include.ALWAYS) String continuationToken) {}

  public static final class InvalidSearchRequestException extends RuntimeException {
    public InvalidSearchRequestException(String message) {
      super(message);
    }
  }

  public static final class AssetNotFoundException extends RuntimeException {}

  public static final class UnsupportedAssetDeleteException extends RuntimeException {
    public UnsupportedAssetDeleteException(String message) {
      super(message);
    }
  }
}
