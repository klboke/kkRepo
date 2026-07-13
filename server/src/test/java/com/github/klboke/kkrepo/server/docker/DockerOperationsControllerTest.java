package com.github.klboke.kkrepo.server.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cache.AssetMetadataCache;
import com.github.klboke.kkrepo.server.cache.NexusCacheType;
import com.github.klboke.kkrepo.server.cache.NexusLikeCacheController;
import com.github.klboke.kkrepo.server.maven.ProxyNegativeCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.support.dao.SecurityDaoAdapter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DockerOperationsControllerTest {
  @Test
  @SuppressWarnings("unchecked")
  void connectorsReturnsRuntimeAndTransferSnapshot() {
    DockerConnectorManager connectorManager = mock(DockerConnectorManager.class);
    DockerConnectorRuntime connectorRuntime = mock(DockerConnectorRuntime.class);
    DockerTransferLimiter transferLimiter = new DockerTransferLimiter(2, 3);
    Instant refreshedAt = Instant.parse("2026-06-22T10:00:00Z");
    when(connectorManager.snapshot()).thenReturn(new DockerConnectorManager.Snapshot(
        true,
        Map.of(5001, "docker-hosted"),
        List.of(new DockerConnectorManager.ConnectorStatus(
            1L,
            "docker-hosted",
            "HOSTED",
            true,
            5001,
            "http://127.0.0.1:5001",
            true,
            "active")),
        new DockerConnectorManager.ConnectorTuning(2000, 100, 60000, 2, 3),
        refreshedAt,
        7L));
    when(connectorRuntime.lastError()).thenReturn("");
    DockerOperationsController controller = controller(
        connectorManager,
        connectorRuntime,
        transferLimiter,
        repositoryDao(List.of()),
        mock(NexusLikeCacheController.class),
        mock(AssetMetadataCache.class),
        mock(ProxyNegativeCache.class));

    Map<String, Object> payload = controller.connectors(requestWithSubject(adminSubject()));

    Map<String, Object> connector = (Map<String, Object>) payload.get("connector");
    assertEquals(true, connector.get("enabled"));
    assertEquals(7L, connector.get("sequence"));
    assertEquals(transferLimiter.snapshot(), payload.get("transfer"));
  }

  @Test
  void clearCacheInvalidatesDockerRepositoriesOnly() {
    NexusLikeCacheController cacheController = mock(NexusLikeCacheController.class);
    AssetMetadataCache assetMetadataCache = mock(AssetMetadataCache.class);
    ProxyNegativeCache proxyNegativeCache = mock(ProxyNegativeCache.class);
    DockerOperationsController controller = controller(
        mock(DockerConnectorManager.class),
        mock(DockerConnectorRuntime.class),
        new DockerTransferLimiter(0, 0),
        repositoryDao(List.of(
            repository(1L, "docker-hosted", RepositoryFormat.DOCKER),
            repository(2L, "maven-hosted", RepositoryFormat.MAVEN2))),
        cacheController,
        assetMetadataCache,
        proxyNegativeCache);

    Map<String, Object> payload = controller.clearCache(requestWithSubject(adminSubject()));

    assertEquals(1, payload.get("repositories"));
    assertEquals(true, payload.get("cleared"));
    verify(cacheController).invalidate(1L, NexusCacheType.CONTENT);
    verify(cacheController).invalidate(1L, NexusCacheType.METADATA);
    verify(assetMetadataCache).evictRepository(1L);
    verify(proxyNegativeCache).invalidateRepository(1L);
  }

  private static DockerOperationsController controller(
      DockerConnectorManager connectorManager,
      DockerConnectorRuntime connectorRuntime,
      DockerTransferLimiter transferLimiter,
      RepositoryDao repositoryDao,
      NexusLikeCacheController cacheController,
      AssetMetadataCache assetMetadataCache,
      ProxyNegativeCache proxyNegativeCache) {
    return new DockerOperationsController(
        connectorManager,
        connectorRuntime,
        transferLimiter,
        repositoryDao,
        cacheController,
        assetMetadataCache,
        proxyNegativeCache,
        new StubAuthenticationService(Optional.empty()),
        new StubSecurityService(AccessDecision.allow()));
  }

  private static RepositoryDao repositoryDao(List<RepositoryRecord> repositories) {
    RepositoryDao dao = mock(RepositoryDao.class);
    when(dao.list()).thenReturn(repositories);
    return dao;
  }

  private static RepositoryRecord repository(long id, String name, RepositoryFormat format) {
    return new RepositoryRecord(
        id,
        name,
        format,
        RepositoryType.HOSTED,
        format.name().toLowerCase() + "-hosted",
        true,
        1L,
        null,
        null,
        null,
        null,
        null,
        true,
        Map.<String, Object>of());
  }

  private static MockHttpServletRequest requestWithSubject(AuthenticatedSubject subject) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    return request;
  }

  private static AuthenticatedSubject adminSubject() {
    return new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null));
  }

  private static class StubAuthenticationService extends SecurityAuthenticationService {
    private final Optional<AuthenticatedSubject> subject;

    private StubAuthenticationService(Optional<AuthenticatedSubject> subject) {
      super(new SecurityDaoAdapter(null, null), new ObjectMapper(), "X-Nexus-Plus-Token");
      this.subject = subject;
    }

    @Override
    public Optional<AuthenticatedSubject> authenticate(jakarta.servlet.http.HttpServletRequest request) {
      return subject;
    }
  }

  private static class StubSecurityService extends SecurityManagementService {
    private final AccessDecision decision;

    private StubSecurityService(AccessDecision decision) {
      super(new SecurityDaoAdapter(null, null));
      this.decision = decision;
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, String requestedPermission) {
      return decision;
    }
  }
}
