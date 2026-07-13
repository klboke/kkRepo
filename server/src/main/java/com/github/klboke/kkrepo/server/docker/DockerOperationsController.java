package com.github.klboke.kkrepo.server.docker;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/docker")
public class DockerOperationsController {
  private final DockerConnectorManager connectorManager;
  private final DockerConnectorRuntime connectorRuntime;
  private final DockerTransferLimiter transferLimiter;
  private final RepositoryDao repositoryDao;
  private final NexusLikeCacheController cacheController;
  private final AssetMetadataCache assetMetadataCache;
  private final ProxyNegativeCache proxyNegativeCache;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;

  public DockerOperationsController(
      DockerConnectorManager connectorManager,
      DockerConnectorRuntime connectorRuntime,
      DockerTransferLimiter transferLimiter,
      RepositoryDao repositoryDao,
      NexusLikeCacheController cacheController,
      AssetMetadataCache assetMetadataCache,
      ProxyNegativeCache proxyNegativeCache,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService) {
    this.connectorManager = connectorManager;
    this.connectorRuntime = connectorRuntime;
    this.transferLimiter = transferLimiter;
    this.repositoryDao = repositoryDao;
    this.cacheController = cacheController;
    this.assetMetadataCache = assetMetadataCache;
    this.proxyNegativeCache = proxyNegativeCache;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
  }

  @GetMapping("/connectors")
  public Map<String, Object> connectors(HttpServletRequest request) {
    requireDockerAdmin(request, "read");
    return Map.of(
        "connector", connectorStatus(connectorManager.snapshot()),
        "transfer", transferLimiter.snapshot());
  }

  @PostMapping("/connectors/refresh")
  public Map<String, Object> refreshConnectors(HttpServletRequest request) {
    requireDockerAdmin(request, "edit");
    return Map.of(
        "connector", connectorStatus(connectorRuntime.sync()),
        "transfer", transferLimiter.snapshot());
  }

  @PostMapping("/cache/clear")
  public Map<String, Object> clearCache(HttpServletRequest request) {
    requireDockerAdmin(request, "edit");
    int repositories = 0;
    for (RepositoryRecord record : repositoryDao.list()) {
      if (record.format() != RepositoryFormat.DOCKER || record.id() == null) {
        continue;
      }
      repositories++;
      cacheController.invalidate(record.id(), NexusCacheType.CONTENT);
      cacheController.invalidate(record.id(), NexusCacheType.METADATA);
      assetMetadataCache.evictRepository(record.id());
      if (proxyNegativeCache != null) {
        proxyNegativeCache.invalidateRepository(record.id());
      }
    }
    return Map.of("repositories", repositories, "cleared", true);
  }

  private AuthenticatedSubject requireDockerAdmin(HttpServletRequest request, String action) {
    AuthenticatedSubject subject = currentSubject(request)
        .or(() -> authenticationService.authenticate(request))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    String permission = "nexus:repository-admin:docker:*:" + action;
    var decision = securityService.decide(subject.permissionSubject(), permission);
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
    return subject;
  }

  private Map<String, Object> connectorStatus(DockerConnectorManager.Snapshot snapshot) {
    return Map.of(
        "enabled", snapshot.enabled(),
        "repositoriesByPort", snapshot.repositoriesByPort(),
        "connectors", snapshot.connectors(),
        "tuning", snapshot.tuning(),
        "refreshedAt", snapshot.refreshedAt(),
        "sequence", snapshot.sequence(),
        "lastError", connectorRuntime.lastError() == null ? "" : connectorRuntime.lastError());
  }

  private static Optional<AuthenticatedSubject> currentSubject(HttpServletRequest request) {
    Object value = request.getAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE);
    return value instanceof AuthenticatedSubject subject ? Optional.of(subject) : Optional.empty();
  }
}
