package com.github.klboke.kkrepo.server.management;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao.AssetWithBlob;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.DecodedAssetId;
import com.github.klboke.kkrepo.server.management.NexusAssetIdCodec.DecodedContinuation;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.raw.RawHostedService;
import com.github.klboke.kkrepo.server.security.ForwardedHeaderPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
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

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final RawHostedService rawHostedService;
  private final NexusRepositoryManagementAuthorizer authorizer;
  private final NexusAssetIdCodec idCodec;
  private final ForwardedHeaderPolicy forwardedHeaderPolicy;

  public NexusAssetManagementService(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      RepositoryRuntimeRegistry runtimeRegistry,
      RawHostedService rawHostedService,
      NexusRepositoryManagementAuthorizer authorizer,
      NexusAssetIdCodec idCodec,
      ForwardedHeaderPolicy forwardedHeaderPolicy) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.runtimeRegistry = runtimeRegistry;
    this.rawHostedService = rawHostedService;
    this.authorizer = authorizer;
    this.idCodec = idCodec;
    this.forwardedHeaderPolicy = forwardedHeaderPolicy;
  }

  @Transactional(readOnly = true)
  public AssetPage search(
      String repositoryName,
      String name,
      String continuationToken,
      HttpServletRequest request) {
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new InvalidSearchRequestException("repository is required for asset search");
    }
    RepositoryRecord repository = repositoryDao.findByName(repositoryName).orElse(null);
    if (repository == null) {
      return new AssetPage(List.of(), null);
    }
    authorizer.requireRepositoryAction(request, repository, name, PermissionAction.BROWSE);

    if (name != null && !name.isBlank()) {
      if (continuationToken != null && !continuationToken.isBlank()) {
        throw new InvalidSearchRequestException(
            "continuationToken is not valid for an exact asset name query");
      }
      List<AssetView> items = assetDao.findAssetByPath(repository.id(), name)
          .flatMap(asset -> assetDao.findAssetWithBlobById(asset.id()))
          .filter(stored -> belongsTo(stored.asset(), repository))
          .map(stored -> List.of(toView(repository, stored, request)))
          .orElseGet(List::of);
      return new AssetPage(items, null);
    }

    long afterAssetId = 0;
    if (continuationToken != null && !continuationToken.isBlank()) {
      DecodedContinuation continuation = idCodec.decodeContinuation(continuationToken);
      if (continuation.repositoryId() != repository.id()) {
        throw new InvalidSearchRequestException("continuationToken belongs to another repository");
      }
      afterAssetId = continuation.lastAssetId();
    }
    List<AssetWithBlob> stored = assetDao.listAssetWithBlobPage(
        repository.id(), afterAssetId, PAGE_SIZE + 1);
    List<AssetView> items = new ArrayList<>(Math.min(PAGE_SIZE, stored.size()));
    for (int index = 0; index < Math.min(PAGE_SIZE, stored.size()); index++) {
      AssetWithBlob candidate = stored.get(index);
      if (belongsTo(candidate.asset(), repository)) {
        items.add(toView(repository, candidate, request));
      }
    }
    String next = stored.size() > PAGE_SIZE && !items.isEmpty()
        ? idCodec.encodeContinuation(repository.id(), stored.get(PAGE_SIZE - 1).asset().id())
        : null;
    return new AssetPage(List.copyOf(items), next);
  }

  @Transactional(readOnly = true)
  public AssetView get(String encodedId, HttpServletRequest request) {
    DecodedAssetId decoded = idCodec.decodeAssetId(encodedId);
    RepositoryRecord repository = repositoryDao.findByName(decoded.repositoryName())
        .orElseThrow(AssetNotFoundException::new);
    AssetWithBlob stored = assetDao.findAssetWithBlobById(decoded.assetId())
        .filter(candidate -> belongsTo(candidate.asset(), repository))
        .orElseThrow(AssetNotFoundException::new);
    authorizer.requireRepositoryAction(
        request, repository, stored.asset().path(), PermissionAction.READ);
    return toView(repository, stored, request);
  }

  public int delete(String encodedId, HttpServletRequest request) {
    DecodedAssetId decoded = idCodec.decodeAssetId(encodedId);
    RepositoryRecord repository = repositoryDao.findByName(decoded.repositoryName())
        .orElseThrow(AssetNotFoundException::new);
    AssetRecord asset = assetDao.findAssetById(decoded.assetId())
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

  private AssetView toView(
      RepositoryRecord repository, AssetWithBlob stored, HttpServletRequest request) {
    AssetRecord asset = stored.asset();
    return new AssetView(
        downloadUrl(repository.name(), asset.path(), request),
        asset.path(),
        idCodec.encodeAssetId(repository.name(), asset.id()),
        repository.name(),
        repository.format().id(),
        checksums(stored.blob()));
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

  private static void putIfPresent(Map<String, String> target, String key, String value) {
    if (value != null && !value.isBlank()) {
      target.put(key, value);
    }
  }

  private static boolean belongsTo(AssetRecord asset, RepositoryRecord repository) {
    return asset.id() != null
        && asset.repositoryId() == repository.id()
        && asset.format() == repository.format();
  }

  public record AssetView(
      String downloadUrl,
      String path,
      String id,
      String repository,
      String format,
      Map<String, String> checksum) {}

  public record AssetPage(
      List<AssetView> items,
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
