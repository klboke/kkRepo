package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AptRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AlpineRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MetadataRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryIndexRebuildDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.ComponentRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.protocol.ansible.AnsibleGalaxyPathParser;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.protocol.nuget.NugetPath;
import com.github.klboke.kkrepo.protocol.nuget.NugetPathParser;
import com.github.klboke.kkrepo.protocol.nuget.NugetPaths;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.protocol.swift.SwiftPath;
import com.github.klboke.kkrepo.protocol.swift.SwiftPathParser;
import com.github.klboke.kkrepo.protocol.swift.SwiftToolsVersions;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.apt.AptService;
import com.github.klboke.kkrepo.server.alpine.AlpineService;
import com.github.klboke.kkrepo.server.conda.CondaBrowsePaths;
import com.github.klboke.kkrepo.server.conda.CondaService;
import com.github.klboke.kkrepo.server.maven.MavenResponse;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntime;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.npm.NpmGroupPackumentCache;
import com.github.klboke.kkrepo.server.pypi.PypiGroupSimpleIndexCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final SwiftPathParser SWIFT_PATHS = new SwiftPathParser();
  private static final NugetPathParser NUGET_PATHS = new NugetPathParser();

  private final RepositoryDao repositoryDao;
  private final AssetDao assetDao;
  private final TerraformRegistryDao terraformRegistryDao;
  private final SwiftRegistryDao swiftRegistryDao;
  private final AnsibleGalaxyRegistryDao ansibleRegistryDao;
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
  private RepositoryRuntimeRegistry runtimeRegistry;
  private AptRegistryDao aptRegistry;
  private AptService aptService;
  private AlpineRegistryDao alpineRegistry;
  private AlpineService alpineService;
  private CondaService condaService;

  public BrowseContentDeleteController(
      RepositoryDao repositoryDao,
      AssetDao assetDao,
      TerraformRegistryDao terraformRegistryDao,
      SwiftRegistryDao swiftRegistryDao,
      AnsibleGalaxyRegistryDao ansibleRegistryDao,
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
    this.terraformRegistryDao = terraformRegistryDao;
    this.swiftRegistryDao = swiftRegistryDao;
    this.ansibleRegistryDao = ansibleRegistryDao;
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

  @Autowired(required = false)
  void setCondaDeleteSupport(
      RepositoryRuntimeRegistry runtimeRegistry, CondaService condaService) {
    this.runtimeRegistry = runtimeRegistry;
    this.condaService = condaService;
  }

  @Autowired(required = false)
  void setAptDeleteSupport(
      RepositoryRuntimeRegistry runtimeRegistry,
      AptRegistryDao aptRegistry,
      AptService aptService) {
    this.runtimeRegistry = runtimeRegistry;
    this.aptRegistry = aptRegistry;
    this.aptService = aptService;
  }

  @Autowired(required = false)
  void setAlpineDeleteSupport(
      RepositoryRuntimeRegistry runtimeRegistry,
      AlpineRegistryDao alpineRegistry,
      AlpineService alpineService) {
    this.runtimeRegistry = runtimeRegistry;
    this.alpineRegistry = alpineRegistry;
    this.alpineService = alpineService;
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

    return deleteAuthorized(repository, path, sourceRepository, subject.userId());
  }

  @Transactional
  public int deleteForCleanup(
      String repository,
      String subjectKind,
      long subjectId,
      String path,
      String actorId) {
    RepositoryRecord target = repositoryByName(repository);
    if ("COMPONENT".equals(subjectKind)) {
      ComponentRecord component = componentDao.findById(subjectId)
          .filter(row -> row.repositoryId() == target.id())
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.NOT_FOUND, "Cleanup component was not found: " + subjectId));
      if (target.format() == RepositoryFormat.SWIFT) {
        return deleteAuthorized(
            repository,
            component.namespace() + "/" + component.name() + "/" + component.version(),
            repository,
            actorId).deletedAssets();
      }
      if (target.format() == RepositoryFormat.ANSIBLEGALAXY) {
        List<AssetRecord> componentAssets = assetDao.listAssetsByComponent(component.id());
        AssetRecord archive = componentAssets.stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Cleanup component has no assets: " + subjectId));
        String filename = archive.path().substring(archive.path().lastIndexOf('/') + 1);
        String publicPath = component.namespace() + "/" + component.name() + "/"
            + component.version() + "/" + filename;
        return deleteAuthorized(repository, publicPath, repository, actorId).deletedAssets();
      }
      if (target.format() == RepositoryFormat.CONDA) {
        Object browsePath = component.attributes() == null
            ? null
            : component.attributes().get("browsePath");
        String publicPath = browsePath == null ? path : browsePath.toString();
        if (publicPath == null || publicPath.isBlank()) {
          List<AssetRecord> componentAssets = assetDao.listAssetsByComponent(component.id());
          if (componentAssets.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Cleanup component has no assets: " + subjectId);
          }
          publicPath = componentAssets.getFirst().path();
        }
        return deleteAuthorized(repository, publicPath, repository, actorId).deletedAssets();
      }
      if (target.format() == RepositoryFormat.APT) {
        Object assetPath = component.attributes() == null
            ? null
            : component.attributes().get("assetPath");
        String publicPath = assetPath == null ? path : assetPath.toString();
        if (publicPath == null || publicPath.isBlank()) {
          List<AssetRecord> componentAssets = assetDao.listAssetsByComponent(component.id());
          if (componentAssets.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Cleanup component has no assets: " + subjectId);
          }
          publicPath = componentAssets.getFirst().path();
        }
        return deleteAuthorized(repository, publicPath, repository, actorId).deletedAssets();
      }
      if (target.format() == RepositoryFormat.ALPINE) {
        Object assetPath = component.attributes() == null
            ? null : component.attributes().get("assetPath");
        String publicPath = assetPath == null ? path : assetPath.toString();
        if (publicPath == null || publicPath.isBlank()) {
          List<AssetRecord> componentAssets = assetDao.listAssetsByComponent(component.id());
          if (componentAssets.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Cleanup component has no assets: " + subjectId);
          }
          publicPath = componentAssets.getFirst().path();
        }
        return deleteAuthorized(repository, publicPath, repository, actorId).deletedAssets();
      }
      if (target.format() == RepositoryFormat.TERRAFORM
          && "terraform-provider".equals(component.kind())) {
        terraformRegistryDao.deleteProviderVersion(
            target.id(), component.namespace(), component.name(), component.version());
      }
      List<AssetRecord> assets = expandNugetPackageAssets(
          target, assetDao.listAssetsByComponent(component.id()));
      if (assets.isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Cleanup component has no assets: " + subjectId);
      }
      String storagePath = assets.getFirst().path();
      String publicPath = path == null || path.isBlank() ? storagePath : path;
      return deleteResolvedAssets(
          target, target, publicPath, storagePath, assets, Set.of(component.id())).deletedAssets();
    }
    if ("ASSET".equals(subjectKind)) {
      AssetRecord asset = assetDao.findAssetById(subjectId)
          .filter(row -> row.repositoryId() == target.id())
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.NOT_FOUND, "Cleanup asset was not found: " + subjectId));
      if (target.format() == RepositoryFormat.CONDA
          || target.format() == RepositoryFormat.APT
          || target.format() == RepositoryFormat.ALPINE) {
        return deleteAuthorized(repository, asset.path(), repository, actorId).deletedAssets();
      }
      return deleteResolvedAssets(
          target,
          target,
          asset.path(),
          asset.path(),
          expandNugetPackageAssets(target, List.of(asset)),
          Set.of()).deletedAssets();
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "Unsupported cleanup subject: " + subjectKind);
  }

  private BrowseDeleteResult deleteAuthorized(
      String repository, String path, String sourceRepository, String actorId) {
    RepositoryRecord requested = repositoryByName(repository);
    String publicPath = normalize(path);
    if (publicPath.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
    }
    AnsibleCoordinate ansibleCoordinate = requested.format() == RepositoryFormat.ANSIBLEGALAXY
        ? ansibleCoordinate(publicPath)
        : null;
    CondaPath condaCoordinate = requested.format() == RepositoryFormat.CONDA
        ? CondaBrowsePaths.packagePath(publicPath).orElse(null)
        : null;
    if (requested.format() == RepositoryFormat.ANSIBLEGALAXY && ansibleCoordinate == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Ansible deletion requires a collection archive browse path");
    }
    if (requested.format() == RepositoryFormat.CONDA && condaCoordinate == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Conda deletion requires a package browse path");
    }
    String storagePath = toStoragePath(requested.format(), publicPath);
    if (requested.format() == RepositoryFormat.APT) {
      storagePath = aptStoragePath(requested, publicPath);
    }
    if (requested.format() == RepositoryFormat.ALPINE) {
      storagePath = alpineStoragePath(requested, publicPath);
    }
    String resolvedSourceRepository = sourceRepository;
    if (requested.format() == RepositoryFormat.TERRAFORM) {
      Optional<TerraformBrowseAssetPathResolver.ResolvedStoragePath> resolved =
          TerraformBrowseAssetPathResolver.resolve(
              requested,
              publicPath,
              sourceRepository,
              repositoryDao,
              assetDao,
              terraformRegistryDao);
      if (resolved.isPresent()) {
        TerraformBrowseAssetPathResolver.ResolvedStoragePath resolvedPath =
            resolved.orElseThrow();
        storagePath = resolvedPath.path();
        resolvedSourceRepository = resolvedPath.sourceRepositoryName();
      }
    }
    RepositoryRecord target = resolveTargetRepository(
        requested, storagePath, resolvedSourceRepository);
    if (target.format() == RepositoryFormat.CONDA
        && target.type() == RepositoryType.HOSTED) {
      return deleteCondaPackage(requested, target, publicPath, condaCoordinate, actorId);
    }
    if (target.format() == RepositoryFormat.APT
        && target.type() == RepositoryType.HOSTED) {
      return deleteAptPackage(requested, target, publicPath, storagePath, actorId);
    }
    if (target.format() == RepositoryFormat.ALPINE
        && target.type() == RepositoryType.HOSTED) {
      return deleteAlpinePackage(requested, target, publicPath, storagePath, actorId);
    }
    List<AssetRecord> assets = matchingAssets(target, storagePath);
    if (assets.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Browse path not found: " + publicPath);
    }

    Long ansibleComponentId = null;
    if (target.format() == RepositoryFormat.ANSIBLEGALAXY) {
      AnsibleGalaxyRegistryDao.CollectionVersion version = requireMatchingAnsibleVersion(
          target, ansibleCoordinate);
      AssetRecord archive = assets.stream()
          .filter(asset -> asset.id() == version.artifactAssetId())
          .findFirst()
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.CONFLICT,
              "Ansible collection archive does not match registry state"));
      if (version.id() == null || !ansibleRegistryDao.deleteVersion(
          target.id(), version.id(), archive.id())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Ansible collection state changed during deletion");
      }
      ansibleComponentId = version.componentId();
      assets = List.of(archive);
    }

    Long swiftComponentId = null;
    if (target.format() == RepositoryFormat.SWIFT) {
      String childPrefix = storagePath.endsWith("/") ? storagePath : storagePath + "/";
      boolean releaseDirectory = assets.stream()
          .map(AssetRecord::path)
          .anyMatch(assetPath -> assetPath.startsWith(childPrefix));
      SwiftCoordinate coordinate = swiftCoordinate(publicPath, releaseDirectory);
      if (coordinate == null) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Swift deletion requires a release, archive, or manifest path");
      }
      SwiftRegistryDao.DeletedRelease deleted = swiftRegistryDao
          .tombstoneAndDeleteReleaseState(
              target.id(),
              coordinate.scopeLc(),
              coordinate.nameLc(),
              coordinate.version(),
              "administrative delete by " + actorId,
              Instant.now())
          .orElseThrow(() -> new ResponseStatusException(
              HttpStatus.CONFLICT,
              "Swift release state was not found for " + coordinate.display()));
      swiftComponentId = deleted.componentId();
      assets = deleted.assetIds().stream()
          .map(assetDao::findAssetById)
          .flatMap(Optional::stream)
          .filter(asset -> asset.repositoryId() == target.id())
          .toList();
      if (assets.size() != deleted.assetIds().size()) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Swift release assets are incomplete for " + coordinate.display());
      }
    }

    Set<Long> extraComponentIds = new HashSet<>();
    if (swiftComponentId != null) {
      extraComponentIds.add(swiftComponentId);
    }
    if (ansibleComponentId != null) {
      extraComponentIds.add(ansibleComponentId);
    }
    return deleteResolvedAssets(
        requested, target, publicPath, storagePath, assets, extraComponentIds);
  }

  private BrowseDeleteResult deleteCondaPackage(
      RepositoryRecord requested,
      RepositoryRecord target,
      String publicPath,
      CondaPath coordinate,
      String actorId) {
    if (runtimeRegistry == null || condaService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Conda delete service is unavailable");
    }
    RepositoryRuntime runtime = runtimeRegistry.resolveById(target.id())
        .filter(candidate -> candidate.format() == RepositoryFormat.CONDA
            && candidate.type() == RepositoryType.HOSTED)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "Conda repository runtime is unavailable"));
    MavenResponse response = condaService.deleteAdministrative(
        runtime, coordinate.canonicalPath(), "administrative delete by " + actorId);
    if (response.status() == 404) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conda package state was not found");
    }
    if (response.status() != 204) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Conda package could not be deleted");
    }
    return new BrowseDeleteResult(requested.name(), target.name(), publicPath, 1);
  }

  private BrowseDeleteResult deleteAptPackage(
      RepositoryRecord requested,
      RepositoryRecord target,
      String publicPath,
      String storagePath,
      String actorId) {
    if (runtimeRegistry == null || aptService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "APT delete service is unavailable");
    }
    RepositoryRuntime runtime = runtimeRegistry.resolveById(target.id())
        .filter(candidate -> candidate.format() == RepositoryFormat.APT
            && candidate.type() == RepositoryType.HOSTED)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "APT repository runtime is unavailable"));
    MavenResponse response = aptService.delete(
        runtime, storagePath, "administrative delete by " + actorId, false);
    if (response.status() == 404) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "APT package state was not found");
    }
    if (response.status() != 204) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "APT package could not be deleted");
    }
    return new BrowseDeleteResult(requested.name(), target.name(), publicPath, 1);
  }

  private String aptStoragePath(RepositoryRecord repository, String publicPath) {
    if (aptRegistry == null) {
      return publicPath;
    }
    Optional<AptRegistryDao.PackageRecord> direct =
        aptRegistry.findPackageByPath(repository.id(), publicPath);
    if (direct.isPresent()) {
      return direct.orElseThrow().path();
    }
    String[] segments = publicPath.split("/", -1);
    if (segments.length != 6) {
      return publicPath;
    }
    return aptRegistry.findPackage(
            repository.id(), segments[0], segments[1], segments[2], segments[3], segments[4])
        .filter(record -> segments[5].equals(record.filename()))
        .map(AptRegistryDao.PackageRecord::path)
        .orElse(publicPath);
  }

  private BrowseDeleteResult deleteAlpinePackage(
      RepositoryRecord requested,
      RepositoryRecord target,
      String publicPath,
      String storagePath,
      String actorId) {
    if (runtimeRegistry == null || alpineService == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Alpine delete service is unavailable");
    }
    RepositoryRuntime runtime = runtimeRegistry.resolveById(target.id())
        .filter(candidate -> candidate.format() == RepositoryFormat.ALPINE
            && candidate.type() == RepositoryType.HOSTED)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "Alpine repository runtime is unavailable"));
    MavenResponse response = alpineService.delete(
        runtime, storagePath, "administrative delete by " + actorId, false);
    if (response.status() == 404) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Alpine package state was not found");
    }
    if (response.status() != 204) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Alpine package could not be deleted");
    }
    return new BrowseDeleteResult(requested.name(), target.name(), publicPath, 1);
  }

  private String alpineStoragePath(RepositoryRecord repository, String publicPath) {
    if (alpineRegistry == null) return publicPath;
    Optional<AlpineRegistryDao.PackageRecord> direct =
        alpineRegistry.findPackageByPath(repository.id(), publicPath);
    if (direct.isPresent()) return direct.orElseThrow().path();
    String[] segments = publicPath.split("/", -1);
    if (segments.length != 6) return publicPath;
    String namespace;
    try {
      namespace = AlpineRegistryDao.namespace(segments[0], segments[1], segments[2]);
    } catch (IllegalArgumentException invalid) {
      return publicPath;
    }
    return alpineRegistry.findPackage(
            repository.id(), namespace, segments[1], segments[3], segments[4], segments[2])
        .filter(record -> segments[5].equals(record.filename()))
        .map(AlpineRegistryDao.PackageRecord::path)
        .orElse(publicPath);
  }

  private BrowseDeleteResult deleteResolvedAssets(
      RepositoryRecord requested,
      RepositoryRecord target,
      String publicPath,
      String storagePath,
      List<AssetRecord> assets,
      Set<Long> extraComponentIds) {
    Set<Long> componentIds = assets.stream()
        .map(AssetRecord::componentId)
        .filter(id -> id != null)
        .collect(Collectors.toCollection(HashSet::new));
    componentIds.addAll(extraComponentIds);
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
    List<RepositoryRecord> members = repositorySources(requested);
    return members.stream()
        .filter(member -> !matchingAssets(member, storagePath).isEmpty())
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Browse path not found: " + storagePath));
  }

  private void requireGroupMember(RepositoryRecord requested, RepositoryRecord target) {
    if (requested.type() != RepositoryType.GROUP) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must match repository");
    }
    List<RepositoryRecord> members = repositorySources(requested);
    boolean member = members.stream()
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

  private List<RepositoryRecord> repositorySources(RepositoryRecord repository) {
    if (repository.format() == RepositoryFormat.SWIFT) {
      return BrowseRepositorySources.swiftSources(repository, repositoryDao);
    }
    if (repository.format() == RepositoryFormat.ANSIBLEGALAXY) {
      return BrowseRepositorySources.ansibleSources(repository, repositoryDao);
    }
    if (repository.format() == RepositoryFormat.CONDA) {
      return BrowseRepositorySources.condaSources(repository, repositoryDao);
    }
    return repositoryDao.listMembers(repository.id());
  }

  private AnsibleGalaxyRegistryDao.CollectionVersion requireMatchingAnsibleVersion(
      RepositoryRecord source,
      AnsibleCoordinate coordinate) {
    AnsibleGalaxyRegistryDao.CollectionVersion version = ansibleRegistryDao
        .findVersionByArtifactFilename(source.id(), coordinate.filename())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Ansible collection identity not found"));
    if (!version.namespaceDisplay().equals(coordinate.namespace())
        || !version.nameDisplay().equals(coordinate.name())
        || !version.versionOriginal().equals(coordinate.version())
        || !version.artifactFilename().equals(coordinate.filename())) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Ansible collection path does not match artifact identity");
    }
    return version;
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

  private List<AssetRecord> expandNugetPackageAssets(
      RepositoryRecord repository, List<AssetRecord> assets) {
    if (repository.format() != RepositoryFormat.NUGET || assets == null || assets.isEmpty()) {
      return assets == null ? List.of() : assets;
    }
    LinkedHashMap<Long, AssetRecord> matches = new LinkedHashMap<>();
    assets.forEach(asset -> matches.put(asset.id(), asset));
    for (AssetRecord asset : List.copyOf(matches.values())) {
      NugetPath parsed = NUGET_PATHS.parse(asset.path());
      if (parsed.packageId() == null || parsed.version() == null
          || (parsed.kind() != NugetPath.Kind.FLAT_CONTAINER_PACKAGE
              && parsed.kind() != NugetPath.Kind.FLAT_CONTAINER_NUSPEC)) {
        continue;
      }
      assetDao.findAssetByPath(
          repository.id(), NugetPaths.flatContainerPackage(parsed.packageId(), parsed.version()))
          .ifPresent(related -> matches.put(related.id(), related));
      assetDao.findAssetByPath(
          repository.id(), NugetPaths.flatContainerNuspec(parsed.packageId(), parsed.version()))
          .ifPresent(related -> matches.put(related.id(), related));
    }
    return List.copyOf(matches.values());
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

  private static SwiftCoordinate swiftCoordinate(String path, boolean releaseDirectory) {
    String normalized = normalize(path);
    SwiftPath parsed;
    try {
      parsed = releaseDirectory
          ? SWIFT_PATHS.parseReleaseMetadata(normalized)
          : SWIFT_PATHS.parse(normalized);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (parsed.kind() == SwiftPath.Kind.UNKNOWN) {
      String[] segments = normalized.split("/", -1);
      if (segments.length == 4
          && SwiftToolsVersions.fromManifestFilename(segments[3]).isPresent()) {
        parsed = SWIFT_PATHS.parse(
            segments[0] + "/" + segments[1] + "/" + segments[2] + "/Package.swift");
      }
    }
    if (parsed.kind() != SwiftPath.Kind.RELEASE_METADATA
        && parsed.kind() != SwiftPath.Kind.SOURCE_ARCHIVE
        && parsed.kind() != SwiftPath.Kind.MANIFEST) {
      return null;
    }
    return new SwiftCoordinate(
        parsed.scope().toLowerCase(Locale.ROOT),
        parsed.name().toLowerCase(Locale.ROOT),
        parsed.version());
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
    MavenCoordinates coordinates = mavenCoordinates(target.id(), storagePath);
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
      return;
    }
    if (target.format() == RepositoryFormat.YUM) {
      repositoryIndexRebuildDao.enqueue(target.id(), RepositoryIndexRebuildDao.YUM_METADATA);
      return;
    }
    if (target.format() == RepositoryFormat.RUBYGEMS) {
      repositoryIndexRebuildDao.enqueue(
          target.id(), RepositoryIndexRebuildDao.RUBYGEMS_METADATA);
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

  private static final MavenPathParser MAVEN_PATH_PARSER = new MavenPathParser();

  private MavenCoordinates mavenCoordinates(long repositoryId, String path) {
    String normalized = isMavenHash(path)
        ? path.substring(0, path.lastIndexOf('.'))
        : path;
    Coordinates coordinates = MAVEN_PATH_PARSER.parsePath(normalized).coordinates();
    if (coordinates != null) {
      return new MavenCoordinates(
          coordinates.groupId(), coordinates.artifactId(), coordinates.baseVersion());
    }
    return mavenDirectoryOrMetadataCoordinates(repositoryId, normalized);
  }

  private MavenCoordinates mavenDirectoryOrMetadataCoordinates(long repositoryId, String path) {
    String[] segments = path.split("/");
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
      MavenCoordinates gaCoordinates =
          new MavenCoordinates(String.join(".", groupSegments), artifactId, "");
      // A V-level metadata path can also look like A-level metadata when the artifactId itself
      // ends with SNAPSHOT. Preserve an existing GA; otherwise follow Maven's snapshot directory
      // layout and treat the parent segment as the base version.
      if (segments.length >= 4
          && artifactId.endsWith("SNAPSHOT")
          && componentDao.listByGa(
                  repositoryId, gaCoordinates.groupId(), gaCoordinates.artifactId())
              .isEmpty()) {
        String version = artifactId;
        artifactId = segments[segments.length - 3];
        groupSegments = List.of(segments).subList(0, segments.length - 3);
        return new MavenCoordinates(String.join(".", groupSegments), artifactId, version);
      }
      return gaCoordinates;
    }
    String version = segments[segments.length - 1];
    String artifactId = segments[segments.length - 2];
    List<String> groupSegments = List.of(segments).subList(0, segments.length - 2);
    if (groupSegments.isEmpty()) {
      return null;
    }
    return new MavenCoordinates(String.join(".", groupSegments), artifactId, version);
  }

  private static String toStoragePath(RepositoryFormat format, String publicPath) {
    if (format == RepositoryFormat.ANSIBLEGALAXY) {
      AnsibleCoordinate coordinate = ansibleCoordinate(publicPath);
      return coordinate == null
          ? publicPath
          : AnsibleGalaxyPathParser.ARTIFACT_BASE + coordinate.filename();
    }
    if (format == RepositoryFormat.CONDA) {
      return CondaBrowsePaths.toStoragePath(publicPath);
    }
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

  private static AnsibleCoordinate ansibleCoordinate(String path) {
    String[] segments = normalize(path).split("/", -1);
    if (segments.length != 4
        || segments[0].isBlank()
        || segments[1].isBlank()
        || segments[2].isBlank()
        || !AnsibleGalaxyPathParser.isArtifactFilename(segments[3])) {
      return null;
    }
    return new AnsibleCoordinate(segments[0], segments[1], segments[2], segments[3]);
  }

  private record AnsibleCoordinate(
      String namespace, String name, String version, String filename) {}

  private record SwiftCoordinate(String scopeLc, String nameLc, String version) {
    String display() {
      return scopeLc + "." + nameLc + "@" + version;
    }
  }

  public record BrowseDeleteResult(
      String repository,
      String sourceRepository,
      String path,
      int deletedAssets) {}
}
