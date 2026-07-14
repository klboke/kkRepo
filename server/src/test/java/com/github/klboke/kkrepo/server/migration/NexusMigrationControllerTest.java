package com.github.klboke.kkrepo.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.auth.PermissionSubject;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.ConfigMigrationCounts;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationPreflight;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationRequest;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationResult;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationTargetBlobStore;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationValidation;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.catalog.CatalogCacheBroadcaster;
import com.github.klboke.kkrepo.server.docker.DockerConnectorManager;
import com.github.klboke.kkrepo.server.docker.DockerConnectorRuntime;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.ApiKeyAuthCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.BasicAuthCache;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache;
import com.github.klboke.kkrepo.server.security.SecurityCatalogCache;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import com.github.klboke.kkrepo.server.support.InMemorySharedCache;
import com.github.klboke.kkrepo.server.support.dao.BlobStoreDaoAdapter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;

class NexusMigrationControllerTest {

  @Test
  void targetBlobStoreRequiresExistingDefaultBlobStore() {
    NexusMigrationController controller = controllerWith(null);

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        controller::targetBlobStore);

    assertEquals("target blob store 'default' must exist before Nexus migration", thrown.getMessage());
  }

  @Test
  void targetBlobStoreUsesExistingDefaultBlobStore() {
    BlobStoreRecord defaultStore = new BlobStoreRecord(
        7L,
        "default",
        "s3",
        "http://s3.local",
        "us-east-1",
        "kkrepo",
        "target-prefix",
        Map.of("engine", "s3", "pathStyleAccess", true));
    NexusMigrationController controller = controllerWith(defaultStore);

    NexusMigrationTargetBlobStore target = controller.targetBlobStore();

    assertEquals("default", target.name());
    assertEquals("s3", target.type());
    assertEquals("http://s3.local", target.endpoint());
    assertEquals("us-east-1", target.region());
    assertEquals("kkrepo", target.bucket());
    assertEquals("target-prefix", target.prefix());
    assertEquals("s3", target.attributes().get("engine"));
    assertEquals("existing-target-default", target.attributes().get("source"));
  }

  @Test
  void badRequestHandlerReturnsExplicitMessageBody() {
    NexusMigrationController controller = controllerWith(null);

    ResponseEntity<Map<String, Object>> response = controller.handleBadRequest(
        new IllegalArgumentException("target blob store 'default' must exist before Nexus migration"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals(
        "target blob store 'default' must exist before Nexus migration",
        response.getBody().get("message"));
  }

  @Test
  void sourceRequestFailureHandlerReturnsExplicitMessageBody() {
    NexusMigrationController controller = controllerWith(null);

    ResponseEntity<Map<String, Object>> response = controller.handleSourceRequestFailure(
        new IOException("Nexus API /service/rest/v1/security/users?source=default timed out after 30s"));

    assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    assertEquals(
        "Nexus API /service/rest/v1/security/users?source=default timed out after 30s",
        response.getBody().get("message"));
  }

  @Test
  void repositoryDataPathVariablesDeclareExplicitNames() {
    assertEquals("jobId", pathVariableName("startRepositoryDataMetadataWorker"));
    assertEquals("jobId", pathVariableName("startRepositoryDataPackageWorker"));
    assertEquals("jobId", pathVariableName("retryFailedRepositoryDataPackages"));
    assertEquals("jobId", pathVariableName("repositoryDataMigrationStatus"));
  }

  @Test
  void runRequestIgnoresLegacyDryRunFlagWhenRealMigrationIsRequested() throws Exception {
    NexusMigrationController controller = controllerWith(defaultBlobStore());
    NexusMigrationController.NexusMigrationCommand command = new NexusMigrationController.NexusMigrationCommand(
        "http://localhost:28090/",
        "admin",
        "secret",
        null,
        null,
        "3.29.2-02",
        true);

    NexusMigrationRequest request = toRequest(controller, command, false);

    assertEquals(false, request.dryRun());
  }

  @Test
  void preflightRequestForcesDryRun() throws Exception {
    NexusMigrationController controller = controllerWith(defaultBlobStore());
    NexusMigrationController.NexusMigrationCommand command = new NexusMigrationController.NexusMigrationCommand(
        "http://localhost:28090/",
        "admin",
        "secret",
        null,
        null,
        "3.29.2-02",
        false);

    NexusMigrationRequest request = toRequest(controller, command, true);

    assertEquals(true, request.dryRun());
  }

  @Test
  void migrationRequestRequiresSourcePassword() throws Exception {
    NexusMigrationController controller = controllerWith(defaultBlobStore());
    NexusMigrationController.NexusMigrationCommand command = new NexusMigrationController.NexusMigrationCommand(
        "http://localhost:28090/",
        "admin",
        "",
        null,
        null,
        "3.29.2-02",
        false);

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> toRequest(controller, command, true));

    assertEquals("sourcePassword is required", thrown.getMessage());
  }

  @Test
  void repositoryDataRequestRequiresSourcePassword() throws Exception {
    NexusMigrationController controller = new NexusMigrationController(
        null,
        new StubBlobStoreDao(defaultBlobStore()),
        null,
        null,
        null,
        null,
        new AllowingSecurityService(),
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
        null);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/internal/migration/nexus/repository-data/start");
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject());
    NexusMigrationController.RepositoryDataMigrationCommand command =
        new NexusMigrationController.RepositoryDataMigrationCommand(
            "http://localhost:28090/",
            "admin",
            " ",
            null,
            null,
            "3.29.2-02",
            List.of("docker-hosted"),
            null,
            List.of(),
            null,
            100,
            1,
            true,
            null);

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> controller.startRepositoryDataMigration(command, request));

    assertEquals("sourcePassword is required", thrown.getMessage());
  }

  @Test
  void repositoryDataCommandMergesExplicitRepositoryNames() throws Exception {
    NexusMigrationController.RepositoryDataMigrationCommand command =
        new NexusMigrationController.RepositoryDataMigrationCommand(
            "http://localhost:28090/",
            "admin",
            "secret",
            null,
            null,
            "3.29.2-02",
            List.of("docker-hosted"),
            "docker-extra docker-second,docker-third",
            List.of("docker-proxy"),
            "docker-proxy-extra",
            100,
            1,
            true,
            null);

    Method method = NexusMigrationController.class.getDeclaredMethod(
        "repositoryNames",
        List.class,
        String.class);
    method.setAccessible(true);

    assertEquals(
        List.of("docker-hosted", "docker-extra", "docker-second", "docker-third"),
        method.invoke(null, command.repositories(), command.repositoryNames()));
    assertEquals(
        List.of("docker-proxy", "docker-proxy-extra"),
        method.invoke(null, command.backupProxyRepositories(), command.backupProxyRepositoryNames()));
  }

  @Test
  void runRefreshesConfigCachesAfterMigrationWrites() throws Exception {
    CountingMigrationService migrationService = new CountingMigrationService();
    CountingRepositoryCatalogCache repositoryCatalogCache = new CountingRepositoryCatalogCache();
    CountingRepositoryRuntimeRegistry runtimeRegistry = new CountingRepositoryRuntimeRegistry();
    CountingBlobStorageRegistry blobStorageRegistry = new CountingBlobStorageRegistry();
    CountingSecurityCatalogCache securityCatalogCache = new CountingSecurityCatalogCache();
    CountingSecurityAuthorizationCache securityAuthorizationCache = new CountingSecurityAuthorizationCache();
    CountingApiKeyAuthCache apiKeyAuthCache = new CountingApiKeyAuthCache();
    CountingBasicAuthCache basicAuthCache = new CountingBasicAuthCache();
    CountingDockerConnectorRuntime dockerConnectorRuntime = new CountingDockerConnectorRuntime();
    NexusMigrationController controller = new TestableNexusMigrationController(
        defaultBlobStore(),
        migrationService,
        repositoryCatalogCache,
        runtimeRegistry,
        blobStorageRegistry,
        securityCatalogCache,
        securityAuthorizationCache,
        apiKeyAuthCache,
        basicAuthCache,
        dockerConnectorRuntime);
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST",
        "/internal/migration/nexus/run");
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, adminSubject());
    NexusMigrationController.NexusMigrationCommand command = new NexusMigrationController.NexusMigrationCommand(
        "http://localhost:28090/",
        "admin",
        "secret",
        null,
        null,
        "3.29.2-02",
        true);

    NexusMigrationResult result = controller.run(command, request);

    assertEquals("finished", result.status());
    assertEquals(1, migrationService.migrations);
    assertEquals(false, migrationService.request.dryRun());
    assertEquals(1, blobStorageRegistry.refreshes);
    assertEquals(1, repositoryCatalogCache.refreshes);
    assertEquals(1, runtimeRegistry.invalidations);
    assertEquals(1, securityCatalogCache.refreshes);
    assertEquals(1, securityAuthorizationCache.invalidations);
    assertEquals(1, apiKeyAuthCache.evictions);
    assertEquals(1, basicAuthCache.evictions);
    assertEquals(1, dockerConnectorRuntime.syncs);
  }

  private static NexusMigrationController controllerWith(BlobStoreRecord defaultStore) {
    return new NexusMigrationController(
        null,
        new StubBlobStoreDao(defaultStore),
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
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static String pathVariableName(String methodName) {
    Method method = Arrays.stream(NexusMigrationController.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    return Arrays.stream(method.getParameters())
        .map(Parameter::getAnnotations)
        .flatMap(Arrays::stream)
        .filter(PathVariable.class::isInstance)
        .map(PathVariable.class::cast)
        .findFirst()
        .map(PathVariable::value)
        .orElse("");
  }

  private static NexusMigrationRequest toRequest(
      NexusMigrationController controller,
      NexusMigrationController.NexusMigrationCommand command,
      boolean dryRun) throws Exception {
    Method method = NexusMigrationController.class.getDeclaredMethod(
        "toRequest",
        NexusMigrationController.NexusMigrationCommand.class,
        boolean.class);
    method.setAccessible(true);
    try {
      return (NexusMigrationRequest) method.invoke(controller, command, dryRun);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception exception) {
        throw exception;
      }
      throw e;
    }
  }

  private static BlobStoreRecord defaultBlobStore() {
    return new BlobStoreRecord(
        7L,
        "default",
        "s3",
        "http://s3.local",
        "us-east-1",
        "kkrepo",
        "target-prefix",
        Map.of("engine", "s3", "pathStyleAccess", true));
  }

  private static AuthenticatedSubject adminSubject() {
    return new AuthenticatedSubject(
        "Local",
        "admin",
        "local",
        null,
        new PermissionSubject("Local", "admin", Set.of("nx-admin"), null));
  }

  private static NexusMigrationResult successfulMigrationResult(NexusMigrationRequest request) {
    return new NexusMigrationResult(
        11L,
        "finished",
        request.dryRun(),
        new NexusMigrationPreflight(
            0,
            0,
            0,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            0,
            List.of(),
            List.of()),
        new ConfigMigrationCounts(0, 0, 0, 0, 0),
        null,
        List.of(),
        new NexusMigrationValidation(false, List.of(), List.of()));
  }

  private static final class TestableNexusMigrationController extends NexusMigrationController {
    private final NexusApiMigrationService migrationService;

    private TestableNexusMigrationController(
        BlobStoreRecord defaultStore,
        NexusApiMigrationService migrationService,
        RepositoryCatalogCache repositoryCatalogCache,
        RepositoryRuntimeRegistry runtimeRegistry,
        BlobStorageRegistry blobStorageRegistry,
        SecurityCatalogCache securityCatalogCache,
        SecurityAuthorizationCache securityAuthorizationCache,
        ApiKeyAuthCache apiKeyAuthCache,
        BasicAuthCache basicAuthCache,
        DockerConnectorRuntime dockerConnectorRuntime) {
      super(
          null,
          new StubBlobStoreDao(defaultStore),
          null,
          null,
          null,
          null,
          new AllowingSecurityService(),
          null,
          null,
          repositoryCatalogCache,
          runtimeRegistry,
          blobStorageRegistry,
          securityCatalogCache,
          securityAuthorizationCache,
          apiKeyAuthCache,
          basicAuthCache,
          dockerConnectorRuntime,
          null);
      this.migrationService = migrationService;
    }

    @Override
    NexusApiMigrationService service() {
      return migrationService;
    }
  }

  private static final class CountingMigrationService extends NexusApiMigrationService {
    private int migrations;
    private NexusMigrationRequest request;

    private CountingMigrationService() {
      super(null, null, null, null, null, null);
    }

    @Override
    public NexusMigrationResult migrate(NexusMigrationRequest request) {
      migrations++;
      this.request = request;
      return successfulMigrationResult(request);
    }
  }

  private static final class AllowingSecurityService extends SecurityManagementService {
    private AllowingSecurityService() {
      super(null);
    }

    @Override
    public AccessDecision decide(PermissionSubject subject, String requestedPermission) {
      return AccessDecision.allow();
    }
  }

  private static final class CountingRepositoryCatalogCache extends RepositoryCatalogCache {
    private int refreshes;

    private CountingRepositoryCatalogCache() {
      super(null, null, false, (ObjectProvider<CatalogCacheBroadcaster>) null);
    }

    @Override
    public void refreshAfterCommit() {
      refreshes++;
    }
  }

  private static final class CountingRepositoryRuntimeRegistry extends RepositoryRuntimeRegistry {
    private int invalidations;

    private CountingRepositoryRuntimeRegistry() {
      super(null, 0);
    }

    @Override
    public void invalidateAll() {
      invalidations++;
    }
  }

  private static final class CountingBlobStorageRegistry extends BlobStorageRegistry {
    private int refreshes;

    private CountingBlobStorageRegistry() {
      super(null, null, null, null, false);
    }

    @Override
    public void refreshAllAndBroadcast() {
      refreshes++;
    }
  }

  private static final class CountingSecurityCatalogCache extends SecurityCatalogCache {
    private int refreshes;

    private CountingSecurityCatalogCache() {
      super(null, false, (ObjectProvider<CatalogCacheBroadcaster>) null);
    }

    @Override
    public void refreshAfterCommit() {
      refreshes++;
    }
  }

  private static final class CountingSecurityAuthorizationCache extends SecurityAuthorizationCache {
    private int invalidations;

    private CountingSecurityAuthorizationCache() {
      super(null, null, false, 0);
    }

    @Override
    public void invalidateAllAfterCommit() {
      invalidations++;
    }
  }

  private static final class CountingApiKeyAuthCache extends ApiKeyAuthCache {
    private int evictions;

    private CountingApiKeyAuthCache() {
      super(new InMemorySharedCache(), true, 60, 5);
    }

    @Override
    public void evictAll() {
      evictions++;
    }
  }

  private static final class CountingBasicAuthCache extends BasicAuthCache {
    private int evictions;

    private CountingBasicAuthCache() {
      super(new InMemorySharedCache(), true, 60, 5);
    }

    @Override
    public void evictAll() {
      evictions++;
    }
  }

  private static final class CountingDockerConnectorRuntime extends DockerConnectorRuntime {
    private int syncs;

    private CountingDockerConnectorRuntime() {
      super(null);
    }

    @Override
    public synchronized DockerConnectorManager.Snapshot sync() {
      syncs++;
      return null;
    }
  }

  private static final class StubBlobStoreDao extends BlobStoreDaoAdapter {
    private final BlobStoreRecord defaultStore;

    private StubBlobStoreDao(BlobStoreRecord defaultStore) {
      super(null, null);
      this.defaultStore = defaultStore;
    }

    @Override
    public Optional<BlobStoreRecord> findByName(String name) {
      if (NexusMigrationController.REQUIRED_TARGET_BLOB_STORE.equals(name)) {
        return Optional.ofNullable(defaultStore);
      }
      return Optional.empty();
    }
  }
}
