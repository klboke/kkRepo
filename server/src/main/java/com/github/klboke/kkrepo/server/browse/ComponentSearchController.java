package com.github.klboke.kkrepo.server.browse;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.ComponentDao.ComponentSearchRow;
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

  public ComponentSearchController(
      ComponentDao componentDao,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.componentDao = componentDao;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
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
        .map(ComponentSearchController::toItem)
        .toList();
    return new ComponentSearchResponse(Math.max(1, Math.min(effectiveLimit, DEFAULT_LIMIT)), items.size(), items);
  }

  private static ComponentSearchItem toItem(ComponentSearchRow row) {
    return new ComponentSearchItem(
        row.repositoryName(),
        formatLabel(row.format()),
        row.namespace(),
        row.name(),
        row.version(),
        row.kind(),
        row.lastUpdatedAt(),
        browsePath(row));
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
      String browsePath) {
  }
}
