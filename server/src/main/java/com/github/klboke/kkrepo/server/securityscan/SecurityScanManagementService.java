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
  private static final int MAX_QUERY_LENGTH = 200;

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
    ScanSummary aggregate =
        scans.summary(visible.stream().map(RepositoryRecord::id).sorted().toList());
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

  public CursorPage<RepositoryView> repositoryPage(
      AuthenticatedSubject actor, String query, long afterId, int requestedLimit) {
    String normalizedQuery = normalizedQuery(query);
    int safeLimit = limit(requestedLimit);
    List<RepositoryView> candidates = repositoryViews(actor).stream()
        .filter(repository -> repository.id() > Math.max(0, afterId))
        .filter(repository -> matchesRepositoryQuery(repository, normalizedQuery))
        .sorted(Comparator.comparingLong(RepositoryView::id))
        .limit(safeLimit + 1L)
        .toList();
    return cursorPage(candidates, safeLimit, RepositoryView::id);
  }

  public List<TaskView> tasks(
      AuthenticatedSubject actor,
      Long repositoryId,
      TaskStatus status,
      long afterId,
      int limit) {
    return taskPage(actor, repositoryId, status, null, afterId, limit).items();
  }

  public CursorPage<TaskView> taskPage(
      AuthenticatedSubject actor,
      Long repositoryId,
      TaskStatus status,
      String query,
      long afterId,
      int requestedLimit) {
    String normalizedQuery = normalizedQuery(query);
    int safeLimit = limit(requestedLimit);
    List<ScanTask> rows = collectByVisibleRepository(
        actor,
        repositoryId,
        repository -> normalizedQuery == null
            ? scans.listTasks(repository.id(), status, afterId, safeLimit + 1)
            : scans.listTasks(
                repository.id(), status, normalizedQuery, afterId, safeLimit + 1),
        ScanTask::id,
        safeLimit + 1);
    Map<Long, String> repositoryNames = new LinkedHashMap<>();
    for (RepositoryRecord repository : visibleRepositories(actor)) {
      repositoryNames.put(repository.id(), repository.name());
    }
    List<TaskView> views = rows.stream()
        .map(task -> TaskView.from(task, repositoryNames.get(task.repositoryId())))
        .toList();
    return cursorPage(views, safeLimit, TaskView::id);
  }

  public List<RunView> runs(
      AuthenticatedSubject actor, Long repositoryId, long afterId, int limit) {
    return runPage(actor, repositoryId, null, afterId, limit).items();
  }

  public CursorPage<RunView> runPage(
      AuthenticatedSubject actor,
      Long repositoryId,
      String query,
      long afterId,
      int requestedLimit) {
    String normalizedQuery = normalizedQuery(query);
    int safeLimit = limit(requestedLimit);
    List<RunView> views = collectByVisibleRepository(
        actor,
        repositoryId,
        repository -> normalizedQuery == null
            ? scans.listRuns(repository.id(), afterId, safeLimit + 1)
            : scans.listRuns(repository.id(), normalizedQuery, afterId, safeLimit + 1),
        ScanRun::id,
        safeLimit + 1)
        .stream().map(RunView::from).toList();
    return cursorPage(views, safeLimit, RunView::id);
  }

  public List<FindingView> findings(
      AuthenticatedSubject actor,
      Long repositoryId,
      Long runId,
      Severity severity,
      long afterId,
      int limit) {
    return findingPage(
        actor, repositoryId, runId, severity, null, afterId, limit).items();
  }

  public CursorPage<FindingView> findingPage(
      AuthenticatedSubject actor,
      Long repositoryId,
      Long runId,
      Severity severity,
      String query,
      long afterId,
      int requestedLimit) {
    if (runId != null) requireVisibleRun(actor, runId);
    String normalizedQuery = normalizedQuery(query);
    int safeLimit = limit(requestedLimit);
    List<ScanFinding> candidates = collectByVisibleRepository(
        actor,
        repositoryId,
        repository -> normalizedQuery == null
            ? scans.listFindings(
                repository.id(), runId, severity, afterId, safeLimit + 1)
            : scans.listFindings(
                repository.id(),
                runId,
                severity,
                normalizedQuery,
                afterId,
                safeLimit + 1),
        ScanFinding::id,
        safeLimit + 1);
    CursorPage<ScanFinding> rawPage =
        cursorPage(candidates, safeLimit, ScanFinding::id);
    if (rawPage.items().isEmpty()) {
      return new CursorPage<>(List.of(), rawPage.nextAfter());
    }
    Set<Long> visibleRepositoryIds = visibleRepositoryIds(actor);
    List<ScanWaiver> waivers = loadAllWaivers();
    Instant now = Instant.now();
    Map<Long, List<ScanRunSubject>> subjectsByRun = new LinkedHashMap<>();
    List<FindingView> views = rawPage.items().stream().map(finding -> {
      List<ScanRunSubject> subjects = subjectsByRun.computeIfAbsent(
          finding.scanRunId(),
          id -> distinctSubjects(scans.listRunSubjects(id).stream()
              .filter(subject -> visibleRepositoryIds.contains(subject.repositoryId()))
              .toList()));
      List<ScanWaiver> matches = matchingWaivers(finding, subjects, waivers);
      List<ScanWaiver> activeMatches = matches.stream()
          .filter(waiver -> SecurityScanWaiverMatcher.isActive(waiver, now))
          .toList();
      int waivedTargets = (int) subjects.stream()
          .filter(subject -> activeMatches.stream()
              .anyMatch(waiver -> SecurityScanWaiverMatcher.matchesSubject(waiver, subject)))
          .count();
      return FindingView.from(
          finding,
          activeMatches.size(),
          matches.size() - activeMatches.size(),
          subjects.size(),
          waivedTargets);
    }).toList();
    return new CursorPage<>(views, rawPage.nextAfter());
  }

  public FindingWaiverContext findingWaiverContext(
      AuthenticatedSubject actor, long findingId) {
    requireWaiverPermission(actor, "create");
    ScanFinding finding = scans.findFinding(findingId)
        .orElseThrow(() -> notFound("Security scan finding not found"));
    List<ScanWaiver> waivers = loadAllWaivers();
    Instant now = Instant.now();
    List<WaiverTargetView> targets = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int administrableTargetCount = 0;
    int waivedTargetCount = 0;
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
        administrableTargetCount++;
        if (isSubjectWaived(finding, subject, waivers, now)) {
          waivedTargetCount++;
        } else {
          targets.add(new WaiverTargetView(
              subject.repositoryId(),
              repository.name(),
              subject.assetId(),
              asset.path()));
        }
      }
    }
    targets.sort(Comparator.comparing(WaiverTargetView::repository)
        .thenComparing(WaiverTargetView::assetPath)
        .thenComparingLong(WaiverTargetView::assetId));
    if (administrableTargetCount == 0) {
      throw notFound("No administrable artifact is associated with this finding");
    }
    return new FindingWaiverContext(
        finding.id(),
        finding.advisoryId(),
        finding.packageUrl(),
        finding.packageName(),
        finding.installedVersion(),
        finding.severity().name(),
        administrableTargetCount,
        waivedTargetCount,
        List.copyOf(targets));
  }

  public FindingWaiverDetail findingWaivers(
      AuthenticatedSubject actor, long findingId) {
    requireGlobalRead(actor);
    ScanFinding finding = scans.findFinding(findingId)
        .orElseThrow(() -> notFound("Security scan finding not found"));
    Set<Long> visibleRepositoryIds = visibleRepositoryIds(actor);
    List<ScanRunSubject> subjects = scans.listRunSubjects(finding.scanRunId()).stream()
        .filter(subject -> visibleRepositoryIds.contains(subject.repositoryId()))
        .toList();
    if (subjects.isEmpty()) throw notFound("Security scan finding not found");
    Instant now = Instant.now();
    List<WaiverView> waivers =
        matchingWaivers(finding, subjects, loadAllWaivers()).stream()
            .map(waiver -> waiverView(waiver, now))
            .toList();
    int active = (int) waivers.stream().filter(WaiverView::active).count();
    return new FindingWaiverDetail(
        finding.id(),
        finding.advisoryId(),
        finding.packageName(),
        finding.packageUrl(),
        active,
        waivers.size() - active,
        waivers);
  }

  private WaiverView waiverView(ScanWaiver waiver, Instant now) {
    AssetRecord asset = waiver.assetId() == null
        ? null
        : assets.findAssetById(waiver.assetId()).orElse(null);
    Long repositoryId = waiver.repositoryId() != null
        ? waiver.repositoryId()
        : (asset == null ? null : asset.repositoryId());
    String repositoryName = repositoryId == null
        ? null
        : repositories.findById(repositoryId)
            .map(RepositoryRecord::name)
            .orElse(null);
    String exception = !blank(waiver.advisorySelector())
        ? waiver.advisorySelector()
        : !blank(waiver.packageSelector())
            ? waiver.packageSelector()
            : waiver.findingId() == null
                ? null
                : scans.findFinding(waiver.findingId())
                    .map(ScanFinding::advisoryId)
                    .orElse("Finding #" + waiver.findingId());
    return new WaiverView(
        waiver.id(),
        SecurityScanWaiverMatcher.isActive(waiver, now),
        waiver.scopeType(),
        waiver.repositoryId(),
        repositoryName,
        waiver.assetId(),
        asset == null ? null : asset.path(),
        waiver.advisorySelector(),
        waiver.packageSelector(),
        exception,
        waiver.reason(),
        waiver.policyId(),
        waiver.policyRevision(),
        waiver.createdBy(),
        waiver.approvedBy(),
        waiver.expiresAt(),
        waiver.createdAt());
  }

  private static List<ScanWaiver> matchingWaivers(
      ScanFinding finding,
      List<ScanRunSubject> subjects,
      List<ScanWaiver> waivers) {
    return waivers.stream()
        .filter(SecurityScanWaiverMatcher::isApproved)
        .filter(waiver -> SecurityScanWaiverMatcher.matchesAnySubject(waiver, subjects))
        .filter(waiver -> SecurityScanWaiverMatcher.matchesFinding(waiver, finding))
        .toList();
  }

  private static boolean isSubjectWaived(
      ScanFinding finding,
      ScanRunSubject subject,
      List<ScanWaiver> waivers,
      Instant evaluatedAt) {
    return waivers.stream()
        .filter(SecurityScanWaiverMatcher::isApproved)
        .filter(waiver -> SecurityScanWaiverMatcher.isActive(waiver, evaluatedAt))
        .filter(waiver -> SecurityScanWaiverMatcher.matchesSubject(waiver, subject))
        .anyMatch(waiver -> SecurityScanWaiverMatcher.matchesFinding(waiver, finding));
  }

  private static List<ScanRunSubject> distinctSubjects(List<ScanRunSubject> subjects) {
    Map<String, ScanRunSubject> distinct = new LinkedHashMap<>();
    for (ScanRunSubject subject : subjects) {
      distinct.putIfAbsent(
          subject.repositoryId() + ":" + subject.assetId(),
          subject);
    }
    return List.copyOf(distinct.values());
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
        if (repositoryScope.appliesToSource(updated, sourceRepositoryId)) {
          scans.createBackfillJob(sourceRepositoryId, actor.userId(), now);
        }
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

  public CursorPage<ScanPolicy> policyPage(
      AuthenticatedSubject actor, String query, long afterId, int requestedLimit) {
    requireGlobalRead(actor);
    String normalizedQuery = normalizedQuery(query);
    int safeLimit = limit(requestedLimit);
    List<ScanPolicy> candidates =
        scans.listPolicies(normalizedQuery, afterId, safeLimit + 1);
    return cursorPage(candidates, safeLimit, ScanPolicy::id);
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

  public List<WaiverView> waivers(
      AuthenticatedSubject actor, Long repositoryId, long afterId, int limit) {
    return waiverPage(actor, repositoryId, null, afterId, limit).items();
  }

  public CursorPage<WaiverView> waiverPage(
      AuthenticatedSubject actor,
      Long repositoryId,
      String query,
      long afterId,
      int requestedLimit) {
    String normalizedQuery = normalizedQuery(query);
    int safeLimit = limit(requestedLimit);
    List<ScanWaiver> waivers;
    if (repositoryId != null) {
      requireVisibleRepository(actor, repositoryId);
      waivers = scans.listWaivers(
              repositoryId, normalizedQuery, afterId, safeLimit + 1)
          .stream()
          .filter(waiver -> {
            Long effectiveRepositoryId = effectiveWaiverRepositoryId(waiver);
            return effectiveRepositoryId == null || effectiveRepositoryId.equals(repositoryId);
          })
          .limit(safeLimit + 1L)
          .toList();
    } else {
      requireGlobalRead(actor);
      Set<Long> visible = visibleRepositoryIds(actor);
      waivers = collectVisibleWaivers(
          normalizedQuery, afterId, safeLimit + 1, visible);
    }
    Instant now = Instant.now();
    CursorPage<ScanWaiver> rawPage =
        cursorPage(waivers, safeLimit, waiver -> waiver.id());
    return new CursorPage<>(
        rawPage.items().stream().map(waiver -> waiverView(waiver, now)).toList(),
        rawPage.nextAfter());
  }

  private List<ScanWaiver> collectVisibleWaivers(
      String query, long afterId, int desiredItems, Set<Long> visibleRepositoryIds) {
    List<ScanWaiver> visibleWaivers = new ArrayList<>(desiredItems);
    long cursor = Math.max(0, afterId);
    int batchSize = Math.min(1000, Math.max(100, desiredItems));
    while (visibleWaivers.size() < desiredItems) {
      List<ScanWaiver> batch = scans.listWaivers(null, query, cursor, batchSize);
      if (batch.isEmpty()) break;
      for (ScanWaiver waiver : batch) {
        Long effectiveRepositoryId = effectiveWaiverRepositoryId(waiver);
        if (effectiveRepositoryId == null
            || visibleRepositoryIds.contains(effectiveRepositoryId)) {
          visibleWaivers.add(waiver);
          if (visibleWaivers.size() == desiredItems) break;
        }
      }
      if (visibleWaivers.size() == desiredItems || batch.size() < batchSize) break;
      long nextCursor = batch.getLast().id();
      if (nextCursor <= cursor) break;
      cursor = nextCursor;
    }
    return List.copyOf(visibleWaivers);
  }

  private List<ScanWaiver> loadAllWaivers() {
    List<ScanWaiver> waivers = new ArrayList<>();
    long cursor = 0;
    while (true) {
      List<ScanWaiver> page = scans.listWaivers(null, cursor, 1000);
      if (page.isEmpty()) break;
      waivers.addAll(page);
      long nextCursor = page.getLast().id();
      if (page.size() < 1000 || nextCursor <= cursor) break;
      cursor = nextCursor;
    }
    return List.copyOf(waivers);
  }

  private List<ScanWaiver> loadActiveWaivers(
      long repositoryId, long assetId, Instant evaluatedAt) {
    List<ScanWaiver> waivers = new ArrayList<>();
    long cursor = 0;
    while (true) {
      List<ScanWaiver> page =
          scans.listActiveWaivers(repositoryId, assetId, evaluatedAt, cursor, 1000);
      if (page.isEmpty()) break;
      waivers.addAll(page);
      long nextCursor = page.getLast().id();
      if (page.size() < 1000 || nextCursor <= cursor) break;
      cursor = nextCursor;
    }
    return List.copyOf(waivers);
  }

  private Long effectiveWaiverRepositoryId(ScanWaiver waiver) {
    if (waiver.repositoryId() != null) return waiver.repositoryId();
    if (waiver.assetId() == null) return null;
    return assets.findAssetById(waiver.assetId())
        .map(AssetRecord::repositoryId)
        .orElse(null);
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
    ScanRunSubject selectedSubject = null;
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
      selectedFinding = scans.findFindingForUpdate(command.findingId())
          .orElseThrow(() -> badRequest("Unknown waiver finding"));
      selectedSubject = scans.listRunSubjects(selectedFinding.scanRunId()).stream()
          .filter(subject ->
              subject.repositoryId() == command.repositoryId()
                  && subject.assetId() == command.assetId())
          .findFirst()
          .orElse(null);
      if (selectedSubject == null) {
        throw badRequest("Waiver finding is not associated with the selected artifact");
      }
    }
    Instant now = Instant.now();
    if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
      throw badRequest("Waiver expiration must be in the future");
    }
    if (selectedFinding != null
        && isSubjectWaived(
            selectedFinding,
            selectedSubject,
            loadActiveWaivers(command.repositoryId(), command.assetId(), now),
            now)) {
      throw conflict("Finding is already waived for this repository artifact");
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

  private static int limit(int requested) {
    return Math.max(1, Math.min(requested <= 0 ? 50 : requested, 200));
  }

  private static String normalizedQuery(String query) {
    if (blank(query)) return null;
    String normalized = query.trim();
    if (normalized.length() > MAX_QUERY_LENGTH) {
      throw badRequest("Search query must not exceed " + MAX_QUERY_LENGTH + " characters");
    }
    return normalized;
  }

  private static boolean matchesRepositoryQuery(
      RepositoryView repository, String query) {
    if (query == null) return true;
    RepositoryScanConfig config = repository.config();
    return containsQuery(
        query,
        repository.id(),
        repository.name(),
        repository.format(),
        repository.type(),
        repository.profileName(),
        repository.policyName(),
        config == null ? null : config.enforcementMode(),
        config != null && config.enabled() ? "enabled" : "disabled");
  }

  private static boolean containsQuery(String query, Object... values) {
    String needle = query.toLowerCase(java.util.Locale.ROOT);
    for (Object value : values) {
      if (value != null
          && String.valueOf(value).toLowerCase(java.util.Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static <T> CursorPage<T> cursorPage(
      List<T> candidates, int requestedLimit, Function<T, Long> id) {
    int safeLimit = limit(requestedLimit);
    boolean hasMore = candidates.size() > safeLimit;
    List<T> items = candidates.stream().limit(safeLimit).toList();
    Long nextAfter =
        hasMore && !items.isEmpty() ? id.apply(items.getLast()) : null;
    return new CursorPage<>(items, nextAfter);
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

  public record CursorPage<T>(List<T> items, Long nextAfter) {
    public CursorPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
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
      String repository,
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
    static TaskView from(ScanTask task, String repository) {
      return new TaskView(
          task.id(), task.repositoryId(), repository, task.assetId(), task.subjectKind().name(),
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
      String sourceStatus,
      int activeWaiverCount,
      int expiredWaiverCount,
      int waiverTargetCount,
      int waivedTargetCount) {
    static FindingView from(
        ScanFinding finding,
        int activeWaiverCount,
        int expiredWaiverCount,
        int waiverTargetCount,
        int waivedTargetCount) {
      return new FindingView(
          finding.id(), finding.scanRunId(), finding.advisoryId(), finding.aliases(),
          finding.dataSource(), finding.packageUrl(), finding.packageName(),
          finding.installedVersion(), finding.fixedVersions(), finding.severity().name(),
          finding.severitySource(), finding.cvssVector(), finding.cvssScore(), finding.title(),
          finding.primaryUrl(), finding.locations(), finding.sourceStatus(),
          activeWaiverCount, expiredWaiverCount, waiverTargetCount, waivedTargetCount);
    }
  }

  public record FindingWaiverDetail(
      long findingId,
      String advisoryId,
      String packageName,
      String packageUrl,
      int activeWaiverCount,
      int expiredWaiverCount,
      List<WaiverView> waivers) {
    public FindingWaiverDetail {
      waivers = waivers == null ? List.of() : List.copyOf(waivers);
    }
  }

  public record WaiverView(
      Long id,
      boolean active,
      String scopeType,
      Long repositoryId,
      String repository,
      Long assetId,
      String assetPath,
      String advisorySelector,
      String packageSelector,
      String exception,
      String reason,
      Long policyId,
      Long policyRevision,
      String createdBy,
      String approvedBy,
      Instant expiresAt,
      Instant createdAt) {}

  public record FindingWaiverContext(
      long findingId,
      String advisoryId,
      String packageUrl,
      String packageName,
      String installedVersion,
      String severity,
      int targetCount,
      int waivedTargetCount,
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
