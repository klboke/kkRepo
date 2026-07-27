package com.github.klboke.kkrepo.server.securityscan;

import com.github.klboke.kkrepo.auth.PermissionAction;
import com.github.klboke.kkrepo.auth.RepositoryPermission;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.AssetSecurityState;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.RepositoryScanConfig;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanCandidate;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanFinding;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanProfile;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanRunSubject;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanTask;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.ScanWaiver;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.Sbom;
import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.TaskDraft;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetBlobRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.AssetRecord;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.security.scan.ScanEnums.EnforcementMode;
import com.github.klboke.kkrepo.security.scan.ScanEnums.PolicyAction;
import com.github.klboke.kkrepo.security.scan.ScanEnums.RequestReason;
import com.github.klboke.kkrepo.security.scan.ScanEnums.ScanStage;
import com.github.klboke.kkrepo.security.scan.ScanEnums.Severity;
import com.github.klboke.kkrepo.security.scan.ScanEnums.SubjectKind;
import com.github.klboke.kkrepo.security.scan.ScanEnums.TaskStatus;
import com.github.klboke.kkrepo.server.security.AuthenticatedSubject;
import com.github.klboke.kkrepo.server.security.SecurityManagementService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Permission-filtered management facade. It never returns task leases or scanner credentials. */
@Service
public class SecurityScanManagementService {
  private final SecurityScanDao scans;
  private final RepositoryDao repositories;
  private final AssetDao assets;
  private final SecurityManagementService security;
  private final SecurityScanDocumentStore documents;
  private final SecurityScanningProperties properties;
  private final SecurityScanRepositoryScope repositoryScope;

  public SecurityScanManagementService(
      SecurityScanDao scans,
      RepositoryDao repositories,
      AssetDao assets,
      SecurityManagementService security,
      SecurityScanDocumentStore documents,
      SecurityScanningProperties properties,
      SecurityScanRepositoryScope repositoryScope) {
    this.scans = scans;
    this.repositories = repositories;
    this.assets = assets;
    this.security = security;
    this.documents = documents;
    this.properties = properties;
    this.repositoryScope = repositoryScope;
  }

  public Overview overview(AuthenticatedSubject actor) {
    List<RepositoryRecord> visible = visibleRepositories(actor);
    ScanSummary aggregate = visible.stream()
        .map(repository -> scans.summary(repository.id()))
        .reduce(emptySummary(), SecurityScanManagementService::add);
    return new Overview(
        properties.isEnabled(),
        scans.latestScannerSnapshot().orElse(null),
        aggregate,
        visible.size());
  }

  public List<RepositoryView> repositoryViews(AuthenticatedSubject actor) {
    List<ScanProfile> profiles = scans.listProfiles();
    Map<Long, String> profileNames = new LinkedHashMap<>();
    for (ScanProfile profile : profiles) {
      if (profile.id() != null) profileNames.put(profile.id(), profile.name());
    }
    Map<Long, String> policyNames = new LinkedHashMap<>();
    for (ScanPolicy policy : scans.listPolicies()) {
      if (policy.id() != null) policyNames.put(policy.id(), policy.name());
    }
    return visibleRepositories(actor).stream()
        .map(repository -> {
          RepositoryScanConfig config = scans.findRepositoryConfig(repository.id())
              .orElseGet(() -> defaultConfig(repository.id(), profiles));
          return new RepositoryView(
              repository.id(),
              repository.name(),
              repository.format().name(),
              repository.type().name(),
              profileNames.get(config.profileId()),
              config.policyId() == null ? null : policyNames.get(config.policyId()),
              config);
        })
        .toList();
  }

  public List<TaskView> tasks(
      AuthenticatedSubject actor,
      Long repositoryId,
      TaskStatus status,
      long afterId,
      int limit) {
    int safeLimit = limit(limit);
    List<ScanTask> rows = collectByVisibleRepository(
        actor,
        repositoryId,
        repository -> scans.listTasks(repository.id(), status, afterId, safeLimit),
        ScanTask::id,
        safeLimit);
    return rows.stream().map(TaskView::from).toList();
  }

  public List<RunView> runs(
      AuthenticatedSubject actor, Long repositoryId, long afterId, int limit) {
    return collectByVisibleRepository(
            actor,
            repositoryId,
            repository -> scans.listRuns(repository.id(), afterId, limit(limit)),
            ScanRun::id,
            limit(limit))
        .stream().map(RunView::from).toList();
  }

  public List<FindingView> findings(
      AuthenticatedSubject actor,
      Long repositoryId,
      Long runId,
      Severity severity,
      long afterId,
      int limit) {
    if (runId != null) requireVisibleRun(actor, runId);
    return collectByVisibleRepository(
            actor,
            repositoryId,
            repository -> scans.listFindings(
                repository.id(), runId, severity, afterId, limit(limit)),
            ScanFinding::id,
            limit(limit))
        .stream().map(FindingView::from).toList();
  }

  public FindingWaiverContext findingWaiverContext(
      AuthenticatedSubject actor, long findingId) {
    requireWaiverPermission(actor, "create");
    ScanFinding finding = scans.findFinding(findingId)
        .orElseThrow(() -> notFound("Security scan finding not found"));
    List<WaiverTargetView> targets = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (ScanRunSubject subject : scans.listRunSubjects(finding.scanRunId())) {
      RepositoryRecord repository = repositories.findById(subject.repositoryId()).orElse(null);
      AssetRecord asset = assets.findAssetById(subject.assetId()).orElse(null);
      if (repository == null
          || asset == null
          || asset.repositoryId() != subject.repositoryId()
          || !canAdministerRepository(actor, repository)) {
        continue;
      }
      String key = subject.repositoryId() + ":" + subject.assetId();
      if (seen.add(key)) {
        targets.add(new WaiverTargetView(
            subject.repositoryId(),
            repository.name(),
            subject.assetId(),
            asset.path()));
      }
    }
    targets.sort(Comparator.comparing(WaiverTargetView::repository)
        .thenComparing(WaiverTargetView::assetPath)
        .thenComparingLong(WaiverTargetView::assetId));
    if (targets.isEmpty()) {
      throw notFound("No administrable artifact is associated with this finding");
    }
    return new FindingWaiverContext(
        finding.id(),
        finding.advisoryId(),
        finding.packageUrl(),
        finding.packageName(),
        finding.installedVersion(),
        finding.severity().name(),
        List.copyOf(targets));
  }

  public AssetDetail asset(AuthenticatedSubject actor, long assetId) {
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(assetId)
        .orElseThrow(() -> notFound("Asset not found"));
    RepositoryRecord repository = requireVisibleRepository(actor, content.asset().repositoryId());
    ScanCandidate candidate = scans.findCandidate(assetId).orElse(null);
    List<AssetSecurityState> states = scans.listAssetStates(assetId);
    return new AssetDetail(
        content.asset().id(),
        repository.id(),
        repository.name(),
        content.asset().path(),
        content.asset().format().name(),
        content.asset().kind(),
        content.asset().contentType(),
        content.asset().size(),
        content.blob() == null ? null : content.blob().sha256(),
        candidate,
        states);
  }

  @Transactional
  public long rescan(AuthenticatedSubject actor, long assetId) {
    AssetDao.AssetWithBlob content = assets.findAssetWithBlobById(assetId)
        .orElseThrow(() -> notFound("Asset not found"));
    requireRepositoryAdmin(actor, content.asset().repositoryId());
    if (content.blob() == null || content.blob().sha256() == null) {
      throw conflict("Asset has no immutable blob identity");
    }
    RepositoryScanConfig config =
        repositoryScope.effectiveConfigsForSource(content.asset().repositoryId()).stream()
        .findFirst()
        .orElseThrow(() -> conflict("Security scanning is not enabled for this repository"));
    ScanProfile profile = scans.findProfile(config.profileId())
        .filter(ScanProfile::enabled)
        .orElseThrow(() -> conflict("Security scan profile is unavailable"));
    ScanCandidate candidate = scans.findCandidate(assetId).orElseGet(() -> {
      scans.markRepositoryAssetsForBackfill(
          content.asset().repositoryId(), Math.max(0, assetId - 1), 1);
      return scans.findCandidate(assetId)
          .orElseThrow(() -> conflict("Unable to materialize scan candidate"));
    });
    String requestUuid = UUID.randomUUID().toString();
    return scans.createTask(new TaskDraft(
        content.asset().repositoryId(),
        assetId,
        subjectKind(content.asset()),
        "sha256:" + content.blob().sha256(),
        candidate.contentGeneration(),
        profile.id(),
        profile.revision(),
        scans.latestScannerSnapshot().map(SecurityScanDao.ScannerSnapshot::id).orElse(null),
        ScanStage.CATALOG_AND_MATCH,
        RequestReason.MANUAL,
        100,
        properties.getWorker().getMaxAttempts(),
        actor.userId(),
        requestUuid,
        "manual:" + assetId + ":" + candidate.contentGeneration() + ":" + requestUuid,
        Instant.now()));
  }

  @Transactional
  public void retry(AuthenticatedSubject actor, long taskId) {
    ScanTask task = scans.findTask(taskId).orElseThrow(() -> notFound("Task not found"));
    requireRepositoryAdmin(actor, task.repositoryId());
    if (!scans.requeueTask(taskId, Instant.now(), actor.userId())) {
      throw conflict("Only failed or cancelled tasks can be retried");
    }
  }

  @Transactional
  public void cancel(AuthenticatedSubject actor, long taskId) {
    ScanTask task = scans.findTask(taskId).orElseThrow(() -> notFound("Task not found"));
    requireRepositoryAdmin(actor, task.repositoryId());
    if (!scans.cancelTask(taskId, Instant.now())) {
      throw conflict("Task is already terminal");
    }
  }

  public RepositoryScanConfig repositoryConfig(
      AuthenticatedSubject actor, long repositoryId) {
    requireVisibleRepository(actor, repositoryId);
    return scans.findRepositoryConfig(repositoryId).orElseGet(() -> defaultConfig(repositoryId));
  }

  @Transactional
  public RepositoryScanConfig updateRepositoryConfig(
      AuthenticatedSubject actor, long repositoryId, ConfigCommand command) {
    requireRepositoryAdmin(actor, repositoryId);
    if (command == null) throw badRequest("Configuration is required");
    ScanProfile profile = scans.findProfile(command.profileId())
        .orElseThrow(() -> badRequest("Unknown scan profile"));
    if (!profile.enabled()) throw badRequest("Scan profile is disabled");
    if (command.policyId() != null && scans.findPolicy(command.policyId()).isEmpty()) {
      throw badRequest("Unknown scan policy");
    }
    RepositoryScanConfig previous =
        scans.findRepositoryConfig(repositoryId).orElse(defaultConfig(repositoryId));
    Instant now = Instant.now();
    RepositoryScanConfig updated = scans.upsertRepositoryConfig(new RepositoryScanConfig(
        repositoryId,
        command.enabled(),
        command.profileId(),
        command.scanHostedContent(),
        command.scanProxyContent(),
        value(command.enforcementMode(), EnforcementMode.AUDIT),
        value(command.pendingAction(), PolicyAction.ALLOW),
        value(command.failureAction(), PolicyAction.ALLOW),
        value(command.partialAction(), PolicyAction.ALLOW),
        positive(command.maxResultAgeSeconds()),
        command.policyId(),
        Math.max(1, previous.configRevision() + 1),
        previous.createdAt() == null ? now : previous.createdAt(),
        now));
    if (requiresBackfill(previous, updated)) {
      for (long sourceRepositoryId : repositoryScope.sourceRepositoryIds(repositoryId)) {
        scans.createBackfillJob(sourceRepositoryId, actor.userId(), now);
      }
    }
    return updated;
  }

  private static boolean requiresBackfill(
      RepositoryScanConfig previous, RepositoryScanConfig updated) {
    return updated.enabled()
        && (!previous.enabled()
            || previous.profileId() != updated.profileId()
            || (!previous.scanHostedContent() && updated.scanHostedContent())
            || (!previous.scanProxyContent() && updated.scanProxyContent()));
  }

  public List<ScanPolicy> policies(AuthenticatedSubject actor) {
    requireGlobalRead(actor);
    return scans.listPolicies();
  }

  @Transactional
  public ScanPolicy createPolicy(AuthenticatedSubject actor, PolicyCommand command) {
    requireGlobalWrite(actor);
    if (command == null || blank(command.name())) throw badRequest("Policy name is required");
    String name = command.name().trim();
    if (scans.listPolicies().stream()
        .anyMatch(policy -> policy.name().equalsIgnoreCase(name))) {
      throw conflict("Policy already exists; edit it to create a new revision");
    }
    return createPolicyRevision(actor, name, 1, command);
  }

  @Transactional
  public ScanPolicy revisePolicy(
      AuthenticatedSubject actor, long policyId, PolicyCommand command) {
    requireGlobalWrite(actor);
    ScanPolicy current =
        scans.findPolicy(policyId).orElseThrow(() -> notFound("Policy not found"));
    if (command == null) throw badRequest("Policy configuration is required");
    if (!blank(command.name())
        && !current.name().equalsIgnoreCase(command.name().trim())) {
      throw badRequest("Policy name cannot be changed when creating a revision");
    }
    long revision = scans.listPolicies().stream()
        .filter(policy -> policy.name().equalsIgnoreCase(current.name()))
        .mapToLong(ScanPolicy::revision)
        .max().orElse(0) + 1;
    ScanPolicy replacement =
        createPolicyRevision(actor, current.name(), revision, command);
    scans.replaceRepositoryPolicy(current.id(), replacement.id(), Instant.now());
    return replacement;
  }

  private ScanPolicy createPolicyRevision(
      AuthenticatedSubject actor,
      String name,
      long revision,
      PolicyCommand command) {
    Instant now = Instant.now();
    return scans.createPolicy(new ScanPolicy(
        null,
        name,
        command.enabled(),
        value(command.blockSeverity(), Severity.CRITICAL),
        command.onlyFixable(),
        command.blockUnknownSeverity(),
        command.requireCompleteInventory(),
        positive(command.maxResultAgeSeconds()),
        command.requiredPlatforms(),
        Math.max(1, revision),
        actor.userId(),
        now,
        now));
  }

  public List<ScanWaiver> waivers(
      AuthenticatedSubject actor, Long repositoryId, long afterId, int limit) {
    if (repositoryId != null) {
      requireVisibleRepository(actor, repositoryId);
      return scans.listWaivers(repositoryId, afterId, limit(limit));
    }
    requireGlobalRead(actor);
    Set<Long> visible = visibleRepositoryIds(actor);
    return scans.listWaivers(null, afterId, 1000).stream()
        .filter(waiver -> waiver.repositoryId() == null || visible.contains(waiver.repositoryId()))
        .limit(limit(limit))
        .toList();
  }

  @Transactional
  public ScanWaiver createWaiver(AuthenticatedSubject actor, WaiverCommand command) {
    requireWaiverPermission(actor, "create");
    if (command == null || blank(command.reason())) throw badRequest("Waiver reason is required");
    if (command.findingId() == null
        && blank(command.advisorySelector())
        && blank(command.packageSelector())) {
      throw badRequest("A finding, advisory, or package selector is required");
    }
    ScanFinding selectedFinding = null;
    if (command.repositoryId() != null) requireRepositoryAdmin(actor, command.repositoryId());
    if (command.assetId() != null) {
      AssetRecord asset = assets.findAssetById(command.assetId())
          .orElseThrow(() -> badRequest("Unknown waiver asset"));
      requireRepositoryAdmin(actor, asset.repositoryId());
      if (command.repositoryId() != null
          && command.repositoryId() != asset.repositoryId()) {
        throw badRequest("Waiver asset does not belong to the repository");
      }
    }
    if (command.findingId() != null) {
      if (command.repositoryId() == null || command.assetId() == null) {
        throw badRequest("Finding waivers require a repository and artifact");
      }
      selectedFinding = scans.findFinding(command.findingId())
          .orElseThrow(() -> badRequest("Unknown waiver finding"));
      boolean associated = scans.listRunSubjects(selectedFinding.scanRunId()).stream()
          .anyMatch(subject ->
              subject.repositoryId() == command.repositoryId()
                  && subject.assetId() == command.assetId());
      if (!associated) {
        throw badRequest("Waiver finding is not associated with the selected artifact");
      }
    }
    Instant now = Instant.now();
    if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
      throw badRequest("Waiver expiration must be in the future");
    }
    String scopeType = command.findingId() != null
        ? "FINDING"
        : command.assetId() != null
            ? "ASSET"
            : command.repositoryId() != null ? "REPOSITORY" : "GLOBAL";
    String advisorySelector = selectedFinding == null
        ? trim(command.advisorySelector())
        : trim(selectedFinding.advisoryId());
    String packageSelector = selectedFinding == null
        ? trim(command.packageSelector())
        : !blank(selectedFinding.packageUrl())
            ? selectedFinding.packageUrl()
            : trim(selectedFinding.packageName());
    ScanWaiver created = scans.createWaiver(new ScanWaiver(
        null,
        scopeType,
        command.repositoryId(),
        command.assetId(),
        command.findingId(),
        advisorySelector,
        packageSelector,
        command.selector(),
        command.reason().trim(),
        command.policyId(),
        command.policyRevision(),
        actor.userId(),
        actor.userId(),
        command.expiresAt(),
        now,
        now));
    scans.bumpAllRepositoryConfigRevisions(now);
    return created;
  }

  @Transactional
  public ScanWaiver deleteWaiver(AuthenticatedSubject actor, long waiverId) {
    requireWaiverPermission(actor, "delete");
    ScanWaiver waiver = scans.findWaiver(waiverId)
        .orElseThrow(() -> notFound("Waiver not found"));
    if (waiver.repositoryId() != null) requireRepositoryAdmin(actor, waiver.repositoryId());
    if (!scans.deleteWaiver(waiverId)) throw notFound("Waiver not found");
    scans.bumpAllRepositoryConfigRevisions(Instant.now());
    return waiver;
  }

  public SbomDownload sbom(AuthenticatedSubject actor, long sbomId) {
    Sbom sbom = scans.findSbom(sbomId).orElseThrow(() -> notFound("SBOM not found"));
    requireAnyVisibleRepository(actor, scans.listRepositoryIdsForSbom(sbomId));
    try {
      return new SbomDownload(sbom, documents.open(sbom.documentBlobId()));
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "SBOM document is unavailable", e);
    }
  }

  private void requireVisibleRun(AuthenticatedSubject actor, long runId) {
    if (scans.findRun(runId).isEmpty()) throw notFound("Scan run not found");
    requireAnyVisibleRepository(actor, scans.listRepositoryIdsForRun(runId));
  }

  private void requireAnyVisibleRepository(AuthenticatedSubject actor, List<Long> repositoryIds) {
    boolean visible = repositoryIds.stream().anyMatch(repositoryId -> {
      try {
        requireVisibleRepository(actor, repositoryId);
        return true;
      } catch (ResponseStatusException ignored) {
        return false;
      }
    });
    if (!visible) throw notFound("Security scan object not found");
  }

  private <T> List<T> collectByVisibleRepository(
      AuthenticatedSubject actor,
      Long repositoryId,
      Function<RepositoryRecord, List<T>> loader,
      Function<T, Long> id,
      int limit) {
    List<RepositoryRecord> scope;
    if (repositoryId == null) {
      scope = visibleRepositories(actor);
    } else {
      scope = List.of(requireVisibleRepository(actor, repositoryId));
    }
    Map<Long, T> merged = new LinkedHashMap<>();
    for (RepositoryRecord repository : scope) {
      for (T value : loader.apply(repository)) merged.putIfAbsent(id.apply(value), value);
    }
    return merged.values().stream()
        .sorted(Comparator.comparing(id))
        .limit(limit)
        .toList();
  }

  private List<RepositoryRecord> visibleRepositories(AuthenticatedSubject actor) {
    return repositories.list().stream()
        .filter(repository -> canBrowse(actor, repository))
        .sorted(Comparator.comparing(RepositoryRecord::name))
        .toList();
  }

  private Set<Long> visibleRepositoryIds(AuthenticatedSubject actor) {
    return visibleRepositories(actor).stream()
        .map(RepositoryRecord::id)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private RepositoryRecord requireVisibleRepository(
      AuthenticatedSubject actor, long repositoryId) {
    RepositoryRecord repository = repositories.findById(repositoryId)
        .orElseThrow(() -> notFound("Repository not found"));
    if (!canBrowse(actor, repository)) throw notFound("Repository not found");
    return repository;
  }

  private void requireRepositoryAdmin(AuthenticatedSubject actor, long repositoryId) {
    RepositoryRecord repository = repositories.findById(repositoryId)
        .orElseThrow(() -> notFound("Repository not found"));
    if (!canAdministerRepository(actor, repository)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository administration required");
    }
  }

  private boolean canAdministerRepository(
      AuthenticatedSubject actor, RepositoryRecord repository) {
    return security.decide(
        actor.permissionSubject(),
        new RepositoryPermission(
            repository.name(), repository.format(), "*", PermissionAction.ADMIN)).allowed();
  }

  private boolean canBrowse(AuthenticatedSubject actor, RepositoryRecord repository) {
    return security.decide(
        actor.permissionSubject(),
        new RepositoryPermission(
            repository.name(), repository.format(), "*", PermissionAction.BROWSE)).allowed();
  }

  private void requireGlobalRead(AuthenticatedSubject actor) {
    if (!security.decide(actor.permissionSubject(), "nexus:security-scanning:read").allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Security scan view permission required");
    }
  }

  private void requireGlobalWrite(AuthenticatedSubject actor) {
    if (!security.decide(actor.permissionSubject(), "nexus:security-scanning:update").allowed()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Security administration permission required");
    }
  }

  private void requireWaiverPermission(AuthenticatedSubject actor, String action) {
    if (!security.decide(
        actor.permissionSubject(), "nexus:security-scanning-waivers:" + action).allowed()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Security scan waiver approval permission required");
    }
  }

  private RepositoryScanConfig defaultConfig(long repositoryId) {
    return defaultConfig(repositoryId, scans.listProfiles());
  }

  private RepositoryScanConfig defaultConfig(
      long repositoryId, List<ScanProfile> profiles) {
    ScanProfile profile = profiles.stream()
        .filter(ScanProfile::enabled)
        .findFirst()
        .orElseThrow(() -> conflict("No enabled security scan profile exists"));
    Instant now = Instant.now();
    return new RepositoryScanConfig(
        repositoryId,
        false,
        profile.id(),
        true,
        true,
        EnforcementMode.AUDIT,
        PolicyAction.ALLOW,
        PolicyAction.ALLOW,
        PolicyAction.ALLOW,
        null,
        null,
        0,
        now,
        now);
  }

  private static SubjectKind subjectKind(AssetRecord asset) {
    return asset.format() == com.github.klboke.kkrepo.core.RepositoryFormat.DOCKER
        && asset.kind() != null
        && asset.kind().toLowerCase(java.util.Locale.ROOT).contains("manifest")
        ? SubjectKind.OCI_MANIFEST : SubjectKind.ASSET_BLOB;
  }

  private static ScanSummary emptySummary() {
    return new ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  private static ScanSummary add(ScanSummary left, ScanSummary right) {
    return new ScanSummary(
        left.candidateBacklog() + right.candidateBacklog(),
        left.pendingTasks() + right.pendingTasks(),
        left.runningTasks() + right.runningTasks(),
        left.failedTasks() + right.failedTasks(),
        left.completeAssets() + right.completeAssets(),
        left.partialAssets() + right.partialAssets(),
        left.staleAssets() + right.staleAssets(),
        left.blockedAssets() + right.blockedAssets(),
        left.criticalFindings() + right.criticalFindings(),
        left.highFindings() + right.highFindings());
  }

  private static int limit(int requested) {
    return Math.max(1, Math.min(requested <= 0 ? 50 : requested, 200));
  }

  private static Long positive(Long value) {
    return value == null ? null : Math.max(1, value);
  }

  private static <T> T value(T value, T fallback) {
    return value == null ? fallback : value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String trim(String value) {
    return blank(value) ? null : value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  public record Overview(
      boolean deploymentEnabled,
      SecurityScanDao.ScannerSnapshot scanner,
      ScanSummary summary,
      int visibleRepositories) {}

  public record RepositoryView(
      long id,
      String name,
      String format,
      String type,
      String profileName,
      String policyName,
      RepositoryScanConfig config) {}

  public record TaskView(
      long id,
      long repositoryId,
      Long assetId,
      String subjectKind,
      String stage,
      String reason,
      int priority,
      String status,
      int attempts,
      int maxAttempts,
      Instant nextAttemptAt,
      String claimedBy,
      Instant leaseUntil,
      String lastErrorCode,
      String lastErrorSummary,
      String requestedBy,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt) {
    static TaskView from(ScanTask task) {
      return new TaskView(
          task.id(), task.repositoryId(), task.assetId(), task.subjectKind().name(),
          task.stage().name(), task.requestReason().name(), task.priority(), task.status().name(),
          task.attempts(), task.maxAttempts(), task.nextAttemptAt(), task.claimedBy(),
          task.leaseUntil(), task.lastErrorCode(), task.lastErrorSummary(), task.requestedBy(),
          task.requestedAt(), task.startedAt(), task.finishedAt());
    }
  }

  public record RunView(
      long id,
      Long taskId,
      long sbomId,
      long scannerSnapshotId,
      String status,
      String completeness,
      int findingCount,
      int fixableFindingCount,
      int criticalCount,
      int highCount,
      int mediumCount,
      int lowCount,
      int unknownCount,
      String maxSeverity,
      Instant completedAt) {
    static RunView from(ScanRun run) {
      return new RunView(
          run.id(), run.taskId(), run.sbomId(), run.scannerSnapshotId(), run.status().name(),
          run.scanCompleteness().name(), run.findingCount(), run.fixableFindingCount(),
          run.criticalCount(), run.highCount(), run.mediumCount(), run.lowCount(),
          run.unknownCount(), run.maxSeverity().name(), run.completedAt());
    }
  }

  public record FindingView(
      long id,
      long scanRunId,
      String advisoryId,
      List<String> aliases,
      String dataSource,
      String packageUrl,
      String packageName,
      String installedVersion,
      List<String> fixedVersions,
      String severity,
      String severitySource,
      String cvssVector,
      Double cvssScore,
      String title,
      String primaryUrl,
      List<String> locations,
      String sourceStatus) {
    static FindingView from(ScanFinding finding) {
      return new FindingView(
          finding.id(), finding.scanRunId(), finding.advisoryId(), finding.aliases(),
          finding.dataSource(), finding.packageUrl(), finding.packageName(),
          finding.installedVersion(), finding.fixedVersions(), finding.severity().name(),
          finding.severitySource(), finding.cvssVector(), finding.cvssScore(), finding.title(),
          finding.primaryUrl(), finding.locations(), finding.sourceStatus());
    }
  }

  public record FindingWaiverContext(
      long findingId,
      String advisoryId,
      String packageUrl,
      String packageName,
      String installedVersion,
      String severity,
      List<WaiverTargetView> targets) {
    public FindingWaiverContext {
      targets = targets == null ? List.of() : List.copyOf(targets);
    }
  }

  public record WaiverTargetView(
      long repositoryId,
      String repository,
      long assetId,
      String assetPath) {}

  public record AssetDetail(
      long id,
      long repositoryId,
      String repository,
      String path,
      String format,
      String kind,
      String contentType,
      Long size,
      String sha256,
      ScanCandidate candidate,
      List<AssetSecurityState> states) {}

  public record ConfigCommand(
      boolean enabled,
      long profileId,
      boolean scanHostedContent,
      boolean scanProxyContent,
      EnforcementMode enforcementMode,
      PolicyAction pendingAction,
      PolicyAction failureAction,
      PolicyAction partialAction,
      Long maxResultAgeSeconds,
      Long policyId) {}

  public record PolicyCommand(
      String name,
      boolean enabled,
      Severity blockSeverity,
      boolean onlyFixable,
      boolean blockUnknownSeverity,
      boolean requireCompleteInventory,
      Long maxResultAgeSeconds,
      List<String> requiredPlatforms) {
    public PolicyCommand {
      requiredPlatforms =
          requiredPlatforms == null ? List.of() : List.copyOf(requiredPlatforms);
    }
  }

  public record WaiverCommand(
      String scopeType,
      Long repositoryId,
      Long assetId,
      Long findingId,
      String advisorySelector,
      String packageSelector,
      Map<String, Object> selector,
      String reason,
      Long policyId,
      Long policyRevision,
      Instant expiresAt) {
    public WaiverCommand {
      selector = selector == null ? Map.of() : Map.copyOf(selector);
    }
  }

  public record SbomDownload(Sbom metadata, InputStream input) {}
}
