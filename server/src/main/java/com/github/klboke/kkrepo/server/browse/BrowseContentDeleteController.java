package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.npm.NpmGroupPackumentCache;
import com.github.klboke.kkrepo.server.pypi.PypiGroupSimpleIndexCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/browse")
public class BrowseContentDeleteController {
  private static final List<String> MAVEN_HASH_SUFFIXES = List.of(".sha1", ".sha256", ".sha512", ".md5");

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final BrowseNodeDao browseNodeDao;
  private final ComponentDao componentDao;
  private final MetadataRebuildDao metadataRebuildDao;
  private final RepositoryIndexRebuildDao repositoryIndexRebuildDao;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private final AssetMetadataCache assetMetadataCache;
  private final NpmGroupPackumentCache npmGroupPackumentCache;
  private final PypiGroupSimpleIndexCache pypiGroupSimpleIndexCache;
  private final GroupMemberAssetCache groupMemberAssetCache;
  private final NexusLikeCacheController cacheController;

  public BrowseContentDeleteController(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      BrowseNodeDao browseNodeDao,
      ComponentDao componentDao,
      MetadataRebuildDao metadataRebuildDao,
      RepositoryIndexRebuildDao repositoryIndexRebuildDao,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService,
      AssetMetadataCache assetMetadataCache,
      NpmGroupPackumentCache npmGroupPackumentCache,
      PypiGroupSimpleIndexCache pypiGroupSimpleIndexCache,
      GroupMemberAssetCache groupMemberAssetCache,
      NexusLikeCacheController cacheController) {
    this.repositoryDao = repositoryDao;
    this.assetDao = assetDao;
    this.browseNodeDao = browseNodeDao;
    this.componentDao = componentDao;
    this.metadataRebuildDao = metadataRebuildDao;
    this.repositoryIndexRebuildDao = repositoryIndexRebuildDao;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
    this.assetMetadataCache = assetMetadataCache;
    this.npmGroupPackumentCache = npmGroupPackumentCache;
    this.pypiGroupSimpleIndexCache = pypiGroupSimpleIndexCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
    this.cacheController = cacheController;
  }

  @DeleteMapping("/{repository}")
  @Transactional
  public BrowseDeleteResult delete(
      @PathVariable("repository") String repository,
      @RequestParam("path") String path,
      @RequestParam(value = "source", required = false) String sourceRepository,
      HttpServletRequest request) {
    AuthenticatedSubject subject = authenticationService.authenticate(request).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    AccessDecision decision = securityService.decide(subject.permissionSubject(), "nexus:*");
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }

    RepositoryRecord requested = repositoryByName(repository);
    String publicPath = normalize(path);
    if (publicPath.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
    }
    String storagePath = toStoragePath(requested.format(), publicPath);
    RepositoryRecord target = resolveTargetRepository(requested, storagePath, sourceRepository);
    List<AssetRecord> assets = matchingAssets(target, storagePath);
    if (assets.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Browse path not found: " + publicPath);
    }

    Set<Long> componentIds = assets.stream()
        .map(AssetRecord::componentId)
        .filter(id -> id != null)
        .collect(Collectors.toSet());
    Set<String> npmPackageIds = assets.stream()
        .map(BrowseContentDeleteController::npmPackageIdForInvalidation)
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toSet());
    Set<String> pypiProjects = assets.stream()
        .map(BrowseContentDeleteController::pypiProjectForInvalidation)
        .filter(id -> id != null && !id.isBlank())
        .collect(Collectors.toSet());
    for (AssetRecord asset : assets) {
      deleteAsset(asset);
    }
    for (Long componentId : componentIds) {
      componentDao.deleteIfNoAssets(componentId);
    }
    enqueueMavenMetadataRebuild(target, storagePath);
    enqueueRepositoryIndexRebuild(target, storagePath);
    if (target.format() == RepositoryFormat.NPM) {
      if (npmPackageIds.isEmpty()) {
        npmGroupPackumentCache.invalidateMemberAfterCommit(target.id());
      } else {
        npmPackageIds.forEach(packageId ->
            npmGroupPackumentCache.invalidateMemberPackageAfterCommit(target.id(), packageId));
      }
    }
    if (target.format() == RepositoryFormat.PYPI && pypiGroupSimpleIndexCache != null) {
      if (pypiProjects.isEmpty()) {
        pypiGroupSimpleIndexCache.invalidateMemberAfterCommit(target.id());
      } else {
        pypiProjects.forEach(project ->
            pypiGroupSimpleIndexCache.invalidateMemberProjectAfterCommit(target.id(), project));
      }
    }
    if ((target.format() == RepositoryFormat.NPM || target.format() == RepositoryFormat.PYPI)
        && groupMemberAssetCache != null) {
      groupMemberAssetCache.invalidateMemberAfterCommit(target.id());
    }
    if (target.format() == RepositoryFormat.TERRAFORM && cacheController != null) {
      cacheController.invalidateAfterCommit(target.id(), NexusCacheType.METADATA);
    }
    return new BrowseDeleteResult(requested.name(), target.name(), publicPath, assets.size());
  }

  private RepositoryRecord resolveTargetRepository(
      RepositoryRecord requested,
      String storagePath,
      String sourceRepository) {
    String source = blankToNull(sourceRepository);
    if (source != null) {
      RepositoryRecord target = repositoryByName(source);
      if (!target.name().equals(requested.name())) {
        requireGroupMember(requested, target);
      }
      return target;
    }
    if (requested.type() != RepositoryType.GROUP) {
      return requested;
    }
    return repositoryDao.listMembers(requested.id()).stream()
        .filter(member -> !matchingAssets(member, storagePath).isEmpty())
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Browse path not found: " + storagePath));
  }

  private void requireGroupMember(RepositoryRecord requested, RepositoryRecord target) {
    if (requested.type() != RepositoryType.GROUP) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must match repository");
    }
    boolean member = repositoryDao.listMembers(requested.id()).stream()
        .anyMatch(row -> row.id().equals(target.id()));
    if (!member) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "source is not a member of " + requested.name());
    }
  }

  private RepositoryRecord repositoryByName(String name) {
    return repositoryDao.findByName(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found: " + name));
  }

  private List<AssetRecord> matchingAssets(RepositoryRecord repository, String storagePath) {
    LinkedHashMap<Long, AssetRecord> matches = new LinkedHashMap<>();
    assetDao.findAssetByPath(repository.id(), storagePath)
        .ifPresent(asset -> matches.put(asset.id(), asset));
    for (String sibling : mavenHashSiblings(repository, storagePath)) {
      assetDao.findAssetByPath(repository.id(), sibling)
          .ifPresent(asset -> matches.put(asset.id(), asset));
    }
    String childPrefix = storagePath.endsWith("/") ? storagePath : storagePath + "/";
    for (AssetRecord asset : assetDao.listAssetsByPrefix(repository.id(), childPrefix)) {
      matches.put(asset.id(), asset);
    }
    return new ArrayList<>(matches.values());
  }

  private List<String> mavenHashSiblings(RepositoryRecord repository, String storagePath) {
    if (repository.format() != RepositoryFormat.MAVEN2 || isMavenHash(storagePath)) {
      return List.of();
    }
    return MAVEN_HASH_SUFFIXES.stream().map(suffix -> storagePath + suffix).toList();
  }

  private boolean isMavenHash(String path) {
    return MAVEN_HASH_SUFFIXES.stream().anyMatch(path::endsWith);
  }

  private void deleteAsset(AssetRecord asset) {
    Long blobId = asset.assetBlobId();
    browseNodeDao.deleteByAssetId(asset.id());
    assetDao.deleteAssetById(asset.id());
    if (blobId != null) {
      assetDao.markBlobDeletedIfUnreferenced(blobId, "asset unlinked");
    }
    assetMetadataCache.evictAfterCommit(asset.repositoryId(), asset.path());
  }

  private static String npmPackageIdForInvalidation(AssetRecord asset) {
    if (asset == null || asset.format() != RepositoryFormat.NPM) {
      return null;
    }
    Object attr = asset.attributes() == null ? null : asset.attributes().get("packageId");
    if (attr != null && !attr.toString().isBlank()) {
      return attr.toString();
    }
    if ("package-root".equals(asset.kind())) {
      return asset.path();
    }
    String path = asset.path();
    int tarballSegment = path == null ? -1 : path.indexOf("/-/");
    return tarballSegment <= 0 ? null : path.substring(0, tarballSegment);
  }

  private static String pypiProjectForInvalidation(AssetRecord asset) {
    if (asset == null || asset.format() != RepositoryFormat.PYPI) {
      return null;
    }
    Map<String, Object> attrs = asset.attributes();
    Object normalized = attrs == null ? null : attrs.get("normalizedName");
    if (normalized != null && !normalized.toString().isBlank()) {
      return normalized.toString();
    }
    Object name = attrs == null ? null : attrs.get("name");
    if (name != null && !name.toString().isBlank()) {
      return name.toString();
    }
    String path = asset.path();
    if (path == null) {
      return null;
    }
    if (path.startsWith("packages/")) {
      String rest = path.substring("packages/".length());
      int slash = rest.indexOf('/');
      return slash <= 0 ? null : rest.substring(0, slash);
    }
    if (path.startsWith("simple/")) {
      String rest = path.substring("simple/".length());
      int slash = rest.indexOf('/');
      return slash <= 0 ? null : rest.substring(0, slash);
    }
    return null;
  }

  private void enqueueMavenMetadataRebuild(RepositoryRecord target, String storagePath) {
    if (target.type() != RepositoryType.HOSTED || target.format() != RepositoryFormat.MAVEN2) {
      return;
    }
    MavenCoordinates coordinates = mavenCoordinates(storagePath);
    if (coordinates == null) {
      return;
    }
    metadataRebuildDao.enqueue(target.id(), "ga:" + coordinates.groupId() + "/" + coordinates.artifactId());
    if (!coordinates.version().isBlank() && coordinates.version().endsWith("SNAPSHOT")) {
      metadataRebuildDao.enqueue(
          target.id(),
          "gav:" + coordinates.groupId() + "/" + coordinates.artifactId() + "/" + coordinates.version());
    }
  }

  private void enqueueRepositoryIndexRebuild(RepositoryRecord target, String storagePath) {
    if (target.type() != RepositoryType.HOSTED) {
      return;
    }
    if (target.format() == RepositoryFormat.HELM) {
      repositoryIndexRebuildDao.enqueue(target.id(), RepositoryIndexRebuildDao.HELM_INDEX);
      return;
    }
    if (target.format() == RepositoryFormat.PYPI) {
      repositoryIndexRebuildDao.enqueue(target.id(), RepositoryIndexRebuildDao.PYPI_ROOT);
      String project = pypiProjectName(storagePath);
      if (project != null && !project.isBlank()) {
        repositoryIndexRebuildDao.enqueue(target.id(), RepositoryIndexRebuildDao.PYPI_PROJECT, project);
      }
    }
  }

  private String pypiProjectName(String storagePath) {
    String[] segments = storagePath == null ? new String[0] : storagePath.split("/");
    if (segments.length >= 2 && "packages".equals(segments[0])) {
      return segments[1];
    }
    if (segments.length >= 2 && "simple".equals(segments[0])) {
      return segments[1];
    }
    return null;
  }

  private MavenCoordinates mavenCoordinates(String path) {
    String normalized = isMavenHash(path)
        ? path.substring(0, path.lastIndexOf('.'))
        : path;
    String[] segments = normalized.split("/");
    if (segments.length < 3) {
      return null;
    }
    String filename = segments[segments.length - 1];
    if ("maven-metadata.xml".equals(filename)) {
      String artifactId = segments[segments.length - 2];
      List<String> groupSegments = List.of(segments).subList(0, segments.length - 2);
      if (groupSegments.isEmpty()) {
        return null;
      }
      return new MavenCoordinates(String.join(".", groupSegments), artifactId, "");
    }

    boolean assetPath = looksLikeMavenAssetPath(segments);
    String version = assetPath ? segments[segments.length - 2] : segments[segments.length - 1];
    String artifactId = assetPath ? segments[segments.length - 3] : segments[segments.length - 2];
    List<String> groupSegments = assetPath
        ? List.of(segments).subList(0, segments.length - 3)
        : List.of(segments).subList(0, segments.length - 2);
    if (groupSegments.isEmpty()) {
      return null;
    }
    return new MavenCoordinates(String.join(".", groupSegments), artifactId, version);
  }

  private boolean looksLikeMavenAssetPath(String[] segments) {
    if (segments.length < 4) {
      return false;
    }
    String filename = segments[segments.length - 1];
    String version = segments[segments.length - 2];
    String artifactId = segments[segments.length - 3];
    return filename.startsWith(artifactId + "-" + version + ".")
        || filename.startsWith(artifactId + "-" + version + "-");
  }

  private static String toStoragePath(RepositoryFormat format, String publicPath) {
    if (format != RepositoryFormat.PYPI) {
      return publicPath;
    }
    if (publicPath.equals("simple") || publicPath.startsWith("simple/")) {
      return publicPath;
    }
    return "packages/" + publicPath;
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String trimmed = path.trim();
    while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private record MavenCoordinates(String groupId, String artifactId, String version) {}

  public record BrowseDeleteResult(
      String repository,
      String sourceRepository,
      String path,
      int deletedAssets) {}
}
