package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.BrowseNodeDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.repositories.RepositoryNotFoundException;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only browse API backing the /browse UI. Returns one level of the browse_node tree per
 * call so the UI can render directories incrementally without loading the whole repository.
 */
@RestController
@RequestMapping("/internal/browse")
public class BrowseController {
  private final RepositoryDao repositoryDao;
  private final BrowseNodeDao browseNodeDao;
  private final DockerBrowseService dockerBrowseService;
  private final TerraformBrowseService terraformBrowseService;
  private final BrowseAssetDetailService assetDetailService;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;

  @Autowired
  public BrowseController(
      RepositoryDao repositoryDao,
      BrowseNodeDao browseNodeDao,
      DockerBrowseService dockerBrowseService,
      TerraformBrowseService terraformBrowseService,
      BrowseAssetDetailService assetDetailService,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.repositoryDao = repositoryDao;
    this.browseNodeDao = browseNodeDao;
    this.dockerBrowseService = dockerBrowseService;
    this.terraformBrowseService = terraformBrowseService;
    this.assetDetailService = assetDetailService;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  public BrowseController(
      RepositoryDao repositoryDao,
      BrowseNodeDao browseNodeDao,
      DockerBrowseService dockerBrowseService,
      BrowseAssetDetailService assetDetailService,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this(
        repositoryDao,
        browseNodeDao,
        dockerBrowseService,
        null,
        assetDetailService,
        authenticationService,
        securityService);
  }

  @GetMapping("/{repository}")
  public BrowseListing list(
      @PathVariable("repository") String repository,
      @RequestParam(value = "path", required = false) String path,
      HttpServletRequest request) {
    RepositoryRecord repo = repositoryDao.findByName(repository)
        .orElseThrow(() -> new RepositoryNotFoundException(repository));
    String parent = normalize(path);
    AuthenticatedSubject subject = currentOrAnonymous(request).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    requireBrowse(subject, repo, parent);
    rejectHiddenPath(repo.format(), parent);
    // For GROUP repos browse_node has nothing of its own — fan out across members and merge.
    // The first member that surfaces a given path wins (matches Maven group "first-win" rules).
    List<RepositoryRecord> sources = repo.type() == RepositoryType.GROUP
        ? repositoryDao.listMembers(repo.id())
        : List.of(repo);
    if (repo.format() == RepositoryFormat.DOCKER && dockerBrowseService != null) {
      return new BrowseListing(repo.name(), parent, dockerBrowseService.list(repo, sources, parent));
    }
    if (repo.format() == RepositoryFormat.TERRAFORM && terraformBrowseService != null) {
      Optional<List<BrowseEntry>> projected = terraformBrowseService.list(repo, sources, parent);
      if (projected.isPresent()) {
        return new BrowseListing(repo.name(), parent, sorted(projected.orElseThrow()));
      }
    }
    BrowsePath browsePath = browsePath(repo.format(), parent);
    LinkedHashMap<String, BrowseEntry> merged = new LinkedHashMap<>();
    for (RepositoryRecord source : sources) {
      for (BrowseNodeDao.BrowseChild row : browseNodeDao.listChildren(source.id(), browsePath.storageParent())) {
        if (!row.hasAssetSubtree() || BrowseAssetVisibility.hidden(repo.format(), row.path())) {
          continue;
        }
        if (isNpmInternalTarballDirectory(repo, browsePath.storageParent(), row)) {
          for (BrowseNodeDao.BrowseChild tarball : browseNodeDao.listChildren(source.id(), row.path())) {
            if (tarball.leaf()) {
              mergeEntry(merged, toEntry(repo, source, tarball, browsePath));
            }
          }
        } else {
          mergeEntry(merged, toEntry(repo, source, row, browsePath));
        }
      }
    }
    addCargoDynamicRootEntries(repo, browsePath, merged);
    if (repo.format() == RepositoryFormat.TERRAFORM
        && terraformBrowseService != null
        && isTerraformProviderTypePath(parent)
        && merged.values().stream().anyMatch(entry -> !entry.leaf())) {
      BrowseEntry versions = terraformBrowseService.versionsJson(repo, parent);
      merged.putIfAbsent(versions.path(), versions);
    }
    // Final ordering: directories first then files, alphabetical within each bucket.
    List<BrowseEntry> entries = sorted(new ArrayList<>(merged.values()));
    return new BrowseListing(repo.name(), browsePath.publicParent(), entries);
  }

  private static List<BrowseEntry> sorted(List<BrowseEntry> entries) {
    entries = new ArrayList<>(entries);
    entries.sort((a, b) -> {
      if (a.leaf() != b.leaf()) return a.leaf() ? 1 : -1;
      return a.path().compareTo(b.path());
    });
    return entries;
  }

  private static boolean isTerraformProviderTypePath(String path) {
    String[] parts = path.split("/");
    return parts.length == 4
        && "v1".equals(parts[0])
        && "providers".equals(parts[1]);
  }

  private static void addCargoDynamicRootEntries(
      RepositoryRecord repo,
      BrowsePath browsePath,
      LinkedHashMap<String, BrowseEntry> merged) {
    if (repo.format() != RepositoryFormat.CARGO || !browsePath.publicParent().isEmpty()) {
      return;
    }
    merged.putIfAbsent("config.json", new BrowseEntry(
        "config.json",
        "config.json",
        repo.name(),
        true,
        null,
        "application/json",
        null,
        null,
        "/repository/" + repo.name() + "/config.json"));
  }

  @GetMapping("/{repository}/attributes")
  public BrowseAssetDetailService.BrowseAssetDetail attributes(
      @PathVariable("repository") String repository,
      @RequestParam("path") String path,
      @RequestParam(value = "source", required = false) String source,
      HttpServletRequest request) {
    RepositoryRecord repo = repositoryDao.findByName(repository)
        .orElseThrow(() -> new RepositoryNotFoundException(repository));
    String normalizedPath = normalize(path);
    AuthenticatedSubject subject = currentOrAnonymous(request).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    requireBrowse(subject, repo, normalizedPath);
    rejectHiddenPath(repo.format(), normalizedPath);
    return assetDetailService.detail(repo, normalizedPath, source);
  }

  private static void rejectHiddenPath(RepositoryFormat format, String path) {
    if (BrowseAssetVisibility.hidden(format, path)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
    }
  }

  private static BrowseEntry toEntry(
      RepositoryRecord visibleRepository,
      RepositoryRecord sourceRepository,
      BrowseNodeDao.BrowseChild row,
      BrowsePath browsePath) {
    String publicPath = browsePath.toPublicPath(row.path());
    return new BrowseEntry(
        row.displayName(),
        publicPath,
        sourceRepository.name(),
        row.leaf(),
        row.assetSize(),
        row.assetContentType(),
        row.assetSha1(),
        row.assetLastUpdatedAt(),
        row.leaf() ? BrowseDownloadUrls.asset(visibleRepository, row.path()) : null);
  }

  private static void mergeEntry(LinkedHashMap<String, BrowseEntry> merged, BrowseEntry entry) {
    BrowseEntry existing = merged.get(entry.path());
    if (existing == null || (existing.leaf() && !entry.leaf())) {
      merged.put(entry.path(), entry);
    }
  }

  private static boolean isNpmInternalTarballDirectory(
      RepositoryRecord repo,
      String parent,
      BrowseNodeDao.BrowseChild row) {
    return repo.format() == RepositoryFormat.NPM
        && !parent.isEmpty()
        && !row.leaf()
        && "-".equals(row.displayName());
  }

  private static BrowsePath browsePath(RepositoryFormat format, String parent) {
    if (format != RepositoryFormat.PYPI) {
      return new BrowsePath(parent, parent, false);
    }
    if (parent.isEmpty()) {
      return new BrowsePath("packages", "", true);
    }
    if (parent.equals("simple") || parent.startsWith("simple/")) {
      return new BrowsePath(parent, parent, false);
    }
    return new BrowsePath("packages/" + parent, parent, true);
  }

  private record BrowsePath(String storageParent, String publicParent, boolean stripPypiPackages) {
    String toPublicPath(String storagePath) {
      if (!stripPypiPackages) return storagePath;
      if ("packages".equals(storagePath)) return "";
      if (storagePath.startsWith("packages/")) return storagePath.substring("packages/".length());
      return storagePath;
    }
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String trimmed = path.trim();
    if (trimmed.isEmpty()) return "";
    // strip leading/trailing slashes — browse_node paths are stored without either
    while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed;
  }

  private Optional<AuthenticatedSubject> currentOrAnonymous(HttpServletRequest request) {
    Optional<AuthenticatedSubject> authenticated = currentSubject(request)
        .or(() -> authenticationService.authenticate(request));
    if (authenticated.isPresent()) {
      request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, authenticated.get());
      return authenticated;
    }
    return authenticationService.authenticateAnonymous();
  }

  private Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object subject = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    if (subject instanceof AuthenticatedSubject authenticated
        && authenticated.userId() != null
        && !authenticated.userId().isBlank()) {
      return Optional.of(authenticated);
    }
    return Optional.empty();
  }

  private void requireBrowse(AuthenticatedSubject subject, RepositoryRecord repository, String path) {
    AccessDecision decision = securityService.decide(
        subject.permissionSubject(),
        new RepositoryPermission(repository.name(), repository.format(), path, PermissionAction.BROWSE));
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
  }

  @ExceptionHandler(RepositoryNotFoundException.class)
  public ResponseEntity<Map<String, String>> handleNotFound(RepositoryNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
  }

  public record BrowseListing(String repository, String path, List<BrowseEntry> entries) {}

  public record BrowseEntry(
      String name,
      String path,
      String sourceRepository,
      boolean leaf,
      Long size,
      String contentType,
      String sha1,
      Instant lastUpdatedAt,
      String downloadUrl) {}
}
