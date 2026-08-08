package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
import com.github.klboke.kkrepo.persistence.jdbc.api.AnsibleGalaxyRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CondaRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SwiftRegistryDao;
import com.github.klboke.kkrepo.protocol.conda.CondaPath;
import com.github.klboke.kkrepo.server.conda.CondaBrowsePaths;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/search/components")
public class ComponentSearchController {
  private static final int DEFAULT_LIMIT = 300;

  private final ComponentDao componentDao;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private final SwiftRegistryDao swiftRegistry;
  private AnsibleGalaxyRegistryDao ansibleRegistry;
  private CondaRegistryDao condaRegistry;

  @Autowired
  public ComponentSearchController(
      ComponentDao componentDao,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService,
      SwiftRegistryDao swiftRegistry) {
    this.componentDao = componentDao;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
    this.swiftRegistry = swiftRegistry;
  }

  @Autowired(required = false)
  void setAnsibleGalaxyRegistry(AnsibleGalaxyRegistryDao ansibleRegistry) {
    this.ansibleRegistry = ansibleRegistry;
  }

  @Autowired(required = false)
  void setCondaRegistry(CondaRegistryDao condaRegistry) {
    this.condaRegistry = condaRegistry;
  }

  public ComponentSearchController(
      ComponentDao componentDao,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this(componentDao, authenticationService, securityService, null);
  }

  @GetMapping
  public ComponentSearchResponse search(
      @RequestParam(value = "q", required = false) String keyword,
      @RequestParam(value = "format", required = false) String format,
      @RequestParam(value = "limit", required = false) Integer limit,
      HttpServletRequest request) {
    AuthenticatedSubject subject = currentOrAnonymous(request).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    requireSearch(subject);
    int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
    RepositoryFormat repositoryFormat = parseFormat(format);
    Map<RepositoryBrowseKey, Boolean> browseDecisions = new HashMap<>();
    List<ComponentSearchItem> items = componentDao.search(keyword, repositoryFormat, effectiveLimit).stream()
        .filter(row -> !BrowseAssetVisibility.hidden(row.format(), row.name())
            && !BrowseAssetVisibility.hidden(row.format(), row.storagePath()))
        .filter(row -> repositoryBrowseAllowed(subject, row, browseDecisions))
        .map(this::toItem)
        .toList();
    return new ComponentSearchResponse(Math.max(1, Math.min(effectiveLimit, DEFAULT_LIMIT)), items.size(), items);
  }

  private ComponentSearchItem toItem(ComponentSearchRow row) {
    return new ComponentSearchItem(
        row.repositoryName(),
        formatLabel(row.format()),
        row.namespace(),
        row.name(),
        row.version(),
        row.kind(),
        row.lastUpdatedAt(),
        browsePath(row),
        componentDetails(row));
  }

  private Map<String, Object> componentDetails(ComponentSearchRow row) {
    if (row.format() == RepositoryFormat.ANSIBLEGALAXY) {
      return ansibleDetails(row);
    }
    if (row.format() == RepositoryFormat.CONDA) {
      return condaDetails(row);
    }
    return swiftDetails(row);
  }

  private Map<String, Object> condaDetails(ComponentSearchRow row) {
    if (condaRegistry == null || row.storagePath() == null) {
      return Map.of();
    }
    Optional<CondaPath> coordinate = CondaBrowsePaths.packagePath(row.storagePath());
    if (coordinate.isEmpty()) {
      return Map.of();
    }
    CondaPath path = coordinate.orElseThrow();
    return condaRegistry.findPackage(
            row.repositoryId(), path.channel(), path.subdir(), path.filename())
        .map(record -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("channel", record.channel());
          details.put("subdir", record.subdir());
          details.put("build", record.build());
          details.put("buildNumber", record.buildNumber());
          details.put("archiveFormat", record.archiveFormat());
          if (record.md5() != null) details.put("md5", record.md5());
          if (record.sha256() != null) details.put("sha256", record.sha256());
          details.put("size", record.size());
          details.put("sourceKind", record.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          Object depends = record.metadata().get("depends");
          if (depends != null) details.put("depends", depends);
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> ansibleDetails(ComponentSearchRow row) {
    if (ansibleRegistry == null || row.namespace() == null || row.name() == null
        || row.version() == null) {
      return Map.of();
    }
    return ansibleRegistry.findVersion(
            row.repositoryId(), row.namespace(), row.name(), row.version())
        .map(version -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("artifactSha256", version.artifactSha256());
          details.put("artifactSize", version.artifactSize());
          details.put("dependencies", version.dependencies());
          if (version.requiresAnsible() != null) {
            details.put("requiresAnsible", version.requiresAnsible());
          }
          details.put("signatureCount", ansibleRegistry.listSignatures(version.id()).size());
          details.put("sourceKind", version.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private Map<String, Object> swiftDetails(ComponentSearchRow row) {
    if (swiftRegistry == null || row.format() != RepositoryFormat.SWIFT
        || row.namespace() == null || row.name() == null || row.version() == null) {
      return Map.of();
    }
    return swiftRegistry.findRelease(
            row.repositoryId(),
            row.namespace().toLowerCase(Locale.ROOT),
            row.name().toLowerCase(Locale.ROOT),
            row.version())
        .map(release -> {
          LinkedHashMap<String, Object> details = new LinkedHashMap<>();
          details.put("checksum", release.archiveSha256());
          details.put("signatureStatus",
              release.signatureFormat() == null ? "unsigned" : "signed");
          if (release.signatureFormat() != null) {
            details.put("signatureFormat", release.signatureFormat());
          }
          details.put("sourceKind", release.sourceKind());
          details.put("sourceRepository", row.repositoryName());
          List<String> toolsVersions = swiftRegistry.listManifests(release.id()).stream()
              .map(SwiftRegistryDao.Manifest::toolsVersion)
              .filter(value -> value != null && !value.isBlank())
              .distinct()
              .sorted()
              .toList();
          details.put("swiftToolsVersions", toolsVersions);
          return Map.copyOf(details);
        })
        .orElseGet(Map::of);
  }

  private static String browsePath(ComponentSearchRow row) {
    if (row.storagePath() == null || row.storagePath().isBlank()) {
      return null;
    }
    return row.storagePath();
  }

  private static RepositoryFormat parseFormat(String value) {
    if (value == null || value.isBlank() || "custom".equalsIgnoreCase(value)) {
      return null;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "maven2" -> RepositoryFormat.MAVEN2;
      case "npm" -> RepositoryFormat.NPM;
      case "cargo" -> RepositoryFormat.CARGO;
      case "nuget" -> RepositoryFormat.NUGET;
      case "pypi" -> RepositoryFormat.PYPI;
      case "rubygems" -> RepositoryFormat.RUBYGEMS;
      case "yum" -> RepositoryFormat.YUM;
      case "helm" -> RepositoryFormat.HELM;
      case "go" -> RepositoryFormat.GO;
      case "pub" -> RepositoryFormat.PUB;
      case "composer" -> RepositoryFormat.COMPOSER;
      case "terraform" -> RepositoryFormat.TERRAFORM;
      case "swift" -> RepositoryFormat.SWIFT;
      case "ansiblegalaxy", "ansible" -> RepositoryFormat.ANSIBLEGALAXY;
      case "conda" -> RepositoryFormat.CONDA;
      case "raw" -> RepositoryFormat.RAW;
      default -> null;
    };
  }

  private static String formatLabel(RepositoryFormat format) {
    return format == null ? "" : format.name().toLowerCase(Locale.ROOT);
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

  private void requireSearch(AuthenticatedSubject subject) {
    AccessDecision decision = securityService.decide(subject.permissionSubject(), "nexus:search:read");
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
  }

  private boolean repositoryBrowseAllowed(
      AuthenticatedSubject subject,
      ComponentSearchRow row,
      Map<RepositoryBrowseKey, Boolean> browseDecisions) {
    RepositoryBrowseKey key = new RepositoryBrowseKey(row.repositoryName(), row.format());
    return browseDecisions.computeIfAbsent(key, ignored -> securityService.decide(
        subject.permissionSubject(),
        new RepositoryPermission(row.repositoryName(), row.format(), "", PermissionAction.BROWSE)).allowed());
  }

  private record RepositoryBrowseKey(String repositoryName, RepositoryFormat format) {
  }

  public record ComponentSearchResponse(int limit, int count, List<ComponentSearchItem> items) {
  }

  public record ComponentSearchItem(
      String repository,
      String format,
      String group,
      String name,
      String version,
      String kind,
      Instant lastUpdatedAt,
      String browsePath,
      Map<String, Object> details) {
  }
}
