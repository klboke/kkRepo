package com.github.klboke.kkrepo.server.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.auth.AccessDecision;
import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.core.RepositoryType;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationPreflight;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationRequest;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationResult;
import com.github.klboke.kkrepo.migration.nexus.NexusApiMigrationService.NexusMigrationTargetBlobStore;
import com.github.klboke.kkrepo.migration.nexus.security.SecurityDaoMigrationWriter;
import com.github.klboke.kkrepo.persistence.jdbc.api.BlobStoreDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.MigrationJobDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.TerraformRegistryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.BlobStoreRecord;
import com.github.klboke.kkrepo.server.cache.GroupMemberAssetCache;
import com.github.klboke.kkrepo.server.docker.DockerConnectorRuntime;
import com.github.klboke.kkrepo.server.helm.HelmGroupIndexCache;
import com.github.klboke.kkrepo.server.maven.BlobStorageRegistry;
import com.github.klboke.kkrepo.server.maven.RepositoryRuntimeRegistry;
import com.github.klboke.kkrepo.server.repositories.RepositoryCatalogCache;
import com.github.klboke.kkrepo.server.security.ApiKeyAuthCache;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.BasicAuthCache;
import com.github.klboke.kkrepo.server.security.SecurityAuthenticationService;
import com.github.klboke.kkrepo.server.security.SecurityAuthorizationCache;
import com.github.klboke.kkrepo.server.security.SecurityCatalogCache;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/migration/nexus")
public class NexusMigrationController {
  static final String REQUIRED_TARGET_BLOB_STORE = "default";

  private final ObjectMapper objectMapper;
  private final BlobStoreDao blobStoreDao;
  private final RepositoryDao repositoryDao;
  private final SecurityDao securityDao;
  private final MigrationJobDao migrationJobDao;
  private final SecurityAuthenticationService authenticationService;
  private final SecurityManagementService securityService;
  private final RepositoryDataMigrationService repositoryDataMigrationService;
  private final RepositoryDataMigrationWorker repositoryDataMigrationWorker;
  private final RepositoryCatalogCache repositoryCatalogCache;
  private final RepositoryRuntimeRegistry runtimeRegistry;
  private final BlobStorageRegistry blobStorageRegistry;
  private final SecurityCatalogCache securityCatalogCache;
  private final SecurityAuthorizationCache securityAuthorizationCache;
  private final ApiKeyAuthCache apiKeyAuthCache;
  private final BasicAuthCache basicAuthCache;
  private final DockerConnectorRuntime dockerConnectorRuntime;
  private final TerraformRegistryDao terraformRegistry;
  private final PlatformTransactionManager transactionManager;
  private HelmGroupIndexCache helmGroupIndexCache;
  private GroupMemberAssetCache groupMemberAssetCache;

  public NexusMigrationController(
      ObjectMapper objectMapper,
      BlobStoreDao blobStoreDao,
      RepositoryDao repositoryDao,
      SecurityDao securityDao,
      MigrationJobDao migrationJobDao,
      SecurityAuthenticationService authenticationService,
      SecurityManagementService securityService,
      RepositoryDataMigrationService repositoryDataMigrationService,
      RepositoryDataMigrationWorker repositoryDataMigrationWorker,
      RepositoryCatalogCache repositoryCatalogCache,
      RepositoryRuntimeRegistry runtimeRegistry,
      BlobStorageRegistry blobStorageRegistry,
      SecurityCatalogCache securityCatalogCache,
      SecurityAuthorizationCache securityAuthorizationCache,
      ApiKeyAuthCache apiKeyAuthCache,
      BasicAuthCache basicAuthCache,
      DockerConnectorRuntime dockerConnectorRuntime,
      TerraformRegistryDao terraformRegistry,
      PlatformTransactionManager transactionManager) {
    this.objectMapper = objectMapper;
    this.blobStoreDao = blobStoreDao;
    this.repositoryDao = repositoryDao;
    this.securityDao = securityDao;
    this.migrationJobDao = migrationJobDao;
    this.authenticationService = authenticationService;
    this.securityService = securityService;
    this.repositoryDataMigrationService = repositoryDataMigrationService;
    this.repositoryDataMigrationWorker = repositoryDataMigrationWorker;
    this.repositoryCatalogCache = repositoryCatalogCache;
    this.runtimeRegistry = runtimeRegistry;
    this.blobStorageRegistry = blobStorageRegistry;
    this.securityCatalogCache = securityCatalogCache;
    this.securityAuthorizationCache = securityAuthorizationCache;
    this.apiKeyAuthCache = apiKeyAuthCache;
    this.basicAuthCache = basicAuthCache;
    this.dockerConnectorRuntime = dockerConnectorRuntime;
    this.terraformRegistry = terraformRegistry;
    this.transactionManager = transactionManager;
  }

  @Autowired
  void configureHelmGroupCaches(
      HelmGroupIndexCache helmGroupIndexCache,
      GroupMemberAssetCache groupMemberAssetCache) {
    this.helmGroupIndexCache = helmGroupIndexCache;
    this.groupMemberAssetCache = groupMemberAssetCache;
  }

  @PostMapping("/preflight")
  public NexusMigrationPreflight preflight(
      @RequestBody NexusMigrationCommand command,
      HttpServletRequest request) throws Exception {
    requireAdmin(request);
    return service().preflight(toRequest(command, true));
  }

  @PostMapping("/run")
  public NexusMigrationResult run(
      @RequestBody NexusMigrationCommand command,
      HttpServletRequest request) throws Exception {
    requireAdmin(request);
    NexusMigrationResult result = service().migrate(toRequest(command, false));
    refreshConfigCachesAfterMigration(result);
    return result;
  }

  /**
   * The Nexus config migration writes repositories, group members, blob stores and security rows
   * directly through DAOs, bypassing the {@code RepositoryService}/security write paths that
   * normally refresh and broadcast the node-local config caches. Reconcile those caches to MySQL
   * and broadcast to sibling replicas once the migration has committed, otherwise
   * {@code /internal/repositories} (and blob-store/security views) keep serving pre-migration state
   * until the periodic catalog refresh fires.
   */
  private void refreshConfigCachesAfterMigration(NexusMigrationResult result) {
    invalidateMigratedHelmRepositories(result);
    if (blobStorageRegistry != null) {
      blobStorageRegistry.refreshAllAndBroadcast();
    }
    if (repositoryCatalogCache != null) {
      repositoryCatalogCache.refreshAfterCommit();
    }
    if (runtimeRegistry != null) {
      runtimeRegistry.invalidateAll();
    }
    if (securityCatalogCache != null) {
      securityCatalogCache.refreshAfterCommit();
    }
    if (securityAuthorizationCache != null) {
      securityAuthorizationCache.invalidateAllAfterCommit();
    }
    if (apiKeyAuthCache != null) {
      apiKeyAuthCache.evictAll();
    }
    if (basicAuthCache != null) {
      basicAuthCache.evictAll();
    }
    if (dockerConnectorRuntime != null) {
      dockerConnectorRuntime.sync();
    }
  }

  private void invalidateMigratedHelmRepositories(NexusMigrationResult result) {
    if (repositoryDao == null || result == null || result.preflight() == null) return;
    result.preflight().repositoriesToMigrate().stream()
        .filter(repository ->
            RepositoryFormat.HELM.id().equalsIgnoreCase(repository.format()))
        .map(repository -> repositoryDao.findByName(repository.name()).orElse(null))
        .filter(repository -> repository != null
            && repository.id() != null
            && repository.format() == RepositoryFormat.HELM)
        .forEach(repository -> {
          // Config migration bypasses RepositoryService. Invalidate every migrated Helm member,
          // not only groups present in the Nexus source plan, so a pre-existing target-only group
          // cannot retain an old chart/provenance winner. A migrated group also invalidates its
          // own merged index while recursively covering any containing groups.
          boolean group = repository.type() == RepositoryType.GROUP;
          if (helmGroupIndexCache != null) {
            if (group) {
              helmGroupIndexCache.invalidateGroupAfterCommit(repository.id());
            } else {
              helmGroupIndexCache.invalidateMemberAfterCommit(repository.id());
            }
          }
          if (groupMemberAssetCache != null) {
            if (group) {
              groupMemberAssetCache.invalidateGroupAfterCommit(repository.id());
            } else {
              groupMemberAssetCache.invalidateMemberAfterCommit(repository.id());
            }
          }
        });
  }

  @PostMapping("/repository-data/start")
  public RepositoryDataMigrationService.RepositoryDataMigrationStatus startRepositoryDataMigration(
      @RequestBody RepositoryDataMigrationCommand command,
      HttpServletRequest request) throws Exception {
    requireAdmin(request);
    if (command == null) {
      throw new IllegalArgumentException("request body is required");
    }
    RepositoryDataMigrationService.RepositoryDataMigrationStatus status =
        repositoryDataMigrationService.start(new RepositoryDataMigrationService.RepositoryDataMigrationRequest(
        required(command.sourceBaseUrl(), "sourceBaseUrl"),
        required(command.sourceUsernameOrAlias(), "sourceUsername"),
        requiredSourcePassword(command.sourcePasswordOrAlias()),
        command.sourceNexusVersion(),
        command.pageSize(),
        command.concurrency(),
        command.checksumValidation(),
        parseInstant(command.metadataSince(), "metadataSince"),
        repositoryNames(command.repositories(), command.repositoryNames()),
        repositoryNames(command.backupProxyRepositories(), command.backupProxyRepositoryNames())));
    repositoryDataMigrationWorker.triggerMetadata(status.jobId());
    return status;
  }

  @PostMapping("/repository-data/jobs/{jobId}/metadata/start")
  public RepositoryDataMigrationService.RepositoryDataMigrationStatus startRepositoryDataMetadataWorker(
      @PathVariable("jobId") long jobId,
      HttpServletRequest request) {
    requireAdmin(request);
    RepositoryDataMigrationService.RepositoryDataMigrationStatus status =
        repositoryDataMigrationService.status(jobId);
    repositoryDataMigrationWorker.triggerMetadata(jobId);
    return status;
  }

  @PostMapping("/repository-data/jobs/{jobId}/packages/start")
  public RepositoryDataMigrationService.RepositoryDataMigrationStatus startRepositoryDataPackageWorker(
      @PathVariable("jobId") long jobId,
      HttpServletRequest request) {
    requireAdmin(request);
    RepositoryDataMigrationService.RepositoryDataMigrationStatus status =
        repositoryDataMigrationService.enablePackageMigration(jobId);
    repositoryDataMigrationWorker.triggerPackages(jobId);
    return status;
  }

  @PostMapping("/repository-data/jobs/{jobId}/packages/retry-failed")
  public RepositoryDataMigrationService.RepositoryDataMigrationStatus retryFailedRepositoryDataPackages(
      @PathVariable("jobId") long jobId,
      HttpServletRequest request) {
    requireAdmin(request);
    RepositoryDataMigrationService.RepositoryDataMigrationStatus status =
        repositoryDataMigrationService.retryFailedPackages(jobId);
    repositoryDataMigrationWorker.triggerPackages(jobId);
    return status;
  }

  @GetMapping("/repository-data/jobs/{jobId}")
  public RepositoryDataMigrationService.RepositoryDataMigrationStatus repositoryDataMigrationStatus(
      @PathVariable("jobId") long jobId,
      HttpServletRequest request) {
    requireAdmin(request);
    return repositoryDataMigrationService.status(jobId);
  }

  @GetMapping("/repository-data/jobs")
  public List<RepositoryDataMigrationService.RepositoryDataMigrationStatus> repositoryDataMigrationJobs(
      HttpServletRequest request) {
    requireAdmin(request);
    return repositoryDataMigrationService.listJobs(20);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
    return ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(Map.of(
            "status", HttpStatus.BAD_REQUEST.value(),
            "error", "Bad Request",
            "message", e.getMessage()));
  }

  @ExceptionHandler(IOException.class)
  public ResponseEntity<Map<String, Object>> handleSourceRequestFailure(IOException e) {
    return ResponseEntity
        .status(HttpStatus.BAD_GATEWAY)
        .body(Map.of(
            "status", HttpStatus.BAD_GATEWAY.value(),
            "error", "Bad Gateway",
            "message", e.getMessage()));
  }

  NexusApiMigrationService service() {
    return new NexusApiMigrationService(
        objectMapper,
        blobStoreDao,
        repositoryDao,
        securityDao,
        migrationJobDao,
        new SecurityDaoMigrationWriter(securityDao),
        terraformRegistry,
        transactionManager);
  }

  private NexusMigrationRequest toRequest(NexusMigrationCommand command, boolean dryRun) {
    if (command == null) {
      throw new IllegalArgumentException("request body is required");
    }
    return new NexusMigrationRequest(
        required(command.sourceBaseUrl(), "sourceBaseUrl"),
        required(command.sourceUsernameOrAlias(), "sourceUsername"),
        requiredSourcePassword(command.sourcePasswordOrAlias()),
        command.sourceNexusVersion(),
        dryRun,
        targetBlobStore());
  }

  NexusMigrationTargetBlobStore targetBlobStore() {
    BlobStoreRecord record = blobStoreDao.findByName(REQUIRED_TARGET_BLOB_STORE)
        .orElseThrow(() -> new IllegalArgumentException(
            "target blob store 'default' must exist before Nexus migration"));
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>(
        record.attributes() == null ? Map.of() : record.attributes());
    attributes.put("source", "existing-target-default");
    return new NexusMigrationTargetBlobStore(
        record.name(),
        record.type(),
        record.endpoint(),
        record.region(),
        record.bucket(),
        record.prefix(),
        attributes);
  }

  private AuthenticatedSubject requireAdmin(HttpServletRequest request) {
    AuthenticatedSubject subject = currentSubject(request)
        .or(() -> authenticationService.authenticate(request))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    request.setAttribute(AuthenticatedSubject.REQUEST_ATTRIBUTE, subject);
    AccessDecision decision = securityService.decide(subject.permissionSubject(), "nexus:*");
    if (!decision.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
    }
    return subject;
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

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static String requiredSourcePassword(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sourcePassword is required");
    }
    return value;
  }

  private static Instant parseInstant(String value, String name) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(name + " must be an ISO-8601 instant");
    }
  }

  private static List<String> repositoryNames(List<String> names, String text) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (names != null) {
      for (String name : names) {
        String value = trimToNull(name);
        if (value != null) {
          normalized.add(value);
        }
      }
    }
    if (text != null && !text.isBlank()) {
      for (String token : text.split("[,\\s]+")) {
        String value = trimToNull(token);
        if (value != null) {
          normalized.add(value);
        }
      }
    }
    return List.copyOf(normalized);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  public record NexusMigrationCommand(
      String sourceBaseUrl,
      String sourceUsername,
      String sourcePassword,
      String username,
      String password,
      String sourceNexusVersion,
      Boolean dryRun) {

    private String sourceUsernameOrAlias() {
      return sourceUsername == null || sourceUsername.isBlank() ? username : sourceUsername;
    }

    private String sourcePasswordOrAlias() {
      return sourcePassword == null ? password : sourcePassword;
    }
  }

  public record RepositoryDataMigrationCommand(
      String sourceBaseUrl,
      String sourceUsername,
      String sourcePassword,
      String username,
      String password,
      String sourceNexusVersion,
      List<String> repositories,
      String repositoryNames,
      List<String> backupProxyRepositories,
      String backupProxyRepositoryNames,
      Integer pageSize,
      Integer concurrency,
      Boolean checksumValidation,
      String metadataSince) {

    private String sourceUsernameOrAlias() {
      return sourceUsername == null || sourceUsername.isBlank() ? username : sourceUsername;
    }

    private String sourcePasswordOrAlias() {
      return sourcePassword == null ? password : sourcePassword;
    }
  }
}
