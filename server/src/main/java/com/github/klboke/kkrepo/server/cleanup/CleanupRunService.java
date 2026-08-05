package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.ClaimedRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRun;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunItem;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunItemSummary;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupRunRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupScanCursor;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.TargetRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cleanup.CleanupExecutionService.ExecutionResult;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.Candidate;
import com.github.klboke.kkrepo.server.cleanup.CleanupSubjectScanner.ScanResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable cleanup run coordinator. Repository work is performed only by claimed workers. */
@Service
public class CleanupRunService {
  static final int MAX_TOTAL_TRY_RUN_SUBJECTS = 50_000;
  private static final int RUN_ITEM_BATCH_SIZE = 100;
  private static final Duration MAX_INLINE_PULSE_INTERVAL = Duration.ofSeconds(5);

  private final CleanupPolicyDao cleanupDao;
  private final RepositoryDao repositoryDao;
  private final CleanupPolicyCapabilities capabilities;
  private final CleanupSubjectScanner scanner;
  private final CleanupExecutionService execution;
  private final CleanupRuntimeProperties properties;
  private final CleanupMetrics metrics;
  private final Clock clock;

  @Autowired
  public CleanupRunService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupPolicyCapabilities capabilities,
      CleanupSubjectScanner scanner,
      CleanupExecutionService execution,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics) {
    this(
        cleanupDao,
        repositoryDao,
        capabilities,
        scanner,
        execution,
        properties,
        metrics,
        Clock.systemUTC());
  }

  CleanupRunService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupPolicyCapabilities capabilities,
      CleanupSubjectScanner scanner,
      CleanupExecutionService execution,
      CleanupRuntimeProperties properties,
      CleanupMetrics metrics,
      Clock clock) {
    this.cleanupDao = cleanupDao;
    this.repositoryDao = repositoryDao;
    this.capabilities = capabilities;
    this.scanner = scanner;
    this.execution = execution;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Transactional
  public RunView startManual(long policyId, RunCommand command, String actorId) {
    if (command == null || command.mode() == null) {
      throw new CleanupValidationException("mode is required");
    }
    CleanupPolicy policy = requirePolicy(policyId);
    if (command.expectedPolicyRevision() == null
        || command.expectedPolicyRevision() != policy.revision()) {
      throw new CleanupRevisionConflictException(policyId, policy.revision());
    }
    String mode = command.mode().trim().toUpperCase(Locale.ROOT);
    if (!mode.equals("TRY_RUN") && !mode.equals("EXECUTE")) {
      throw new CleanupValidationException("mode must be TRY_RUN or EXECUTE");
    }
    validateExecuteCapability(policy, mode);
    int scanLimit = effectiveScanLimit(policy, command.scanLimitPerRepository(), mode);
    return create(policy, mode, "MANUAL", actorId, null, scanLimit);
  }

  @Transactional
  public RunView startScheduled(long policyId, Instant scheduledFor) {
    CleanupRun existing = cleanupDao.findScheduledRun(policyId, scheduledFor).orElse(null);
    if (existing != null) return view(existing);
    CleanupPolicy policy = requirePolicy(policyId);
    if (!"ACTIVE".equals(policy.state())) {
      throw new CleanupValidationException("cleanup policy is not active");
    }
    validateExecuteCapability(policy, "EXECUTE");
    return create(
        policy,
        "EXECUTE",
        "SCHEDULED",
        "system:cleanup-scheduler",
        scheduledFor,
        policy.scanLimitPerRepository());
  }

  public List<CleanupRun> listRuns(Long policyId, long afterId, int limit) {
    return cleanupDao.listRuns(policyId, afterId, Math.min(Math.max(1, limit), 100));
  }

  public RunPage listRunPage(Long policyId, long beforeId, int limit) {
    int safeLimit = Math.min(Math.max(1, limit), 100);
    List<CleanupRun> rows =
        cleanupDao.listRunsBefore(policyId, Math.max(0, beforeId), safeLimit + 1);
    boolean hasMore = rows.size() > safeLimit;
    List<CleanupRun> items = hasMore ? rows.subList(0, safeLimit) : rows;
    Long nextBefore = hasMore && !items.isEmpty() ? items.getLast().id() : null;
    return new RunPage(items, nextBefore);
  }

  public RunView getRun(long runId) {
    return view(requireRun(runId));
  }

  public CleanupRun getRunSummary(long runId) {
    return requireRun(runId);
  }

  public record RunPage(List<CleanupRun> items, Long nextBefore) {
    public RunPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public RunDetailView getRunDetails(long runId, int itemsPerRepository) {
    CleanupRun run = requireRun(runId);
    List<CleanupRunRepository> repositories = cleanupDao.listRunRepositories(runId);
    return new RunDetailView(
        run,
        repositories,
        cleanupDao.listRunItems(
            repositories.stream().map(CleanupRunRepository::id).toList(),
            Math.min(Math.max(1, itemsPerRepository), 200)));
  }

  public List<CleanupRunItem> listItems(
      long runId, long runRepositoryId, long afterId, int limit) {
    CleanupRunRepository repository = cleanupDao.findRunRepository(runId, runRepositoryId)
        .orElseThrow(() -> new CleanupNotFoundException(
            "cleanup run repository", runRepositoryId));
    return cleanupDao.listRunItems(
        repository.id(), afterId, Math.min(Math.max(1, limit), 200));
  }

  public RunView cancel(long runId) {
    requireRun(runId);
    if (!cleanupDao.requestRunCancellation(runId, databaseNow())) {
      throw new CleanupValidationException("cleanup run is already terminal");
    }
    aggregate(runId);
    return getRun(runId);
  }

  private RunView create(
      CleanupPolicy policy,
      String mode,
      String trigger,
      String actorId,
      Instant scheduledFor,
      int scanLimitPerRepository) {
    Instant createdAt = databaseNow();
    List<TargetRepository> targets = cleanupDao.listTargets(policy.id());
    if (targets.isEmpty()) {
      throw new CleanupValidationException("cleanup policy has no target repositories");
    }
    targets.forEach(target -> CleanupTargetRepositories.requireSupported(target.type()));
    List<Map<String, Object>> repositorySnapshot = targets.stream()
        .map(target -> Map.<String, Object>of(
            "id", target.id(),
            "name", target.name(),
            "format", target.format().id(),
            "type", target.type().name()))
        .toList();
    CleanupRun run = new CleanupRun(
        null,
        policy.id(),
        policy.revision(),
        mode,
        trigger,
        "PENDING",
        actorId,
        scheduledFor,
        scanLimitPerRepository,
        policy.deleteLimitPerRepository(),
        policy.criteria(),
        repositorySnapshot,
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        null,
        createdAt,
        createdAt);
    long runId;
    if (scheduledFor != null) {
      var inserted = cleanupDao.tryCreateRun(run);
      if (inserted.isEmpty()) {
        return cleanupDao.findScheduledRun(policy.id(), scheduledFor)
            .map(this::view)
            .orElseThrow(() -> new IllegalStateException(
                "scheduled cleanup winner was not visible"));
      }
      runId = inserted.getAsLong();
    } else {
      runId = cleanupDao.createRun(run);
    }
    List<CleanupRunRepository> runRepositories = targets.stream()
        .map(target -> new CleanupRunRepository(
          null,
          runId,
          target.id(),
          target.name(),
          target.format(),
          target.type(),
          "PENDING",
          0,
          0,
          0,
          0,
          false,
          null,
          null,
          null,
          createdAt,
          createdAt))
        .toList();
    cleanupDao.createRunRepositories(runRepositories);
    return getRun(runId);
  }

  /** Executes one already-claimed repository shard. Safe to call on any replica. */
  public void process(ClaimedRunRepository claim) {
    CleanupRun run = cleanupDao.findRun(claim.runId()).orElse(null);
    if (run == null) return;
    if (claim.takeover()) metrics.takeover();
    if (claim.attemptCount() > claim.maxAttempts()) {
      terminal(
          claim, run, "FAILED", 0, 0, 0, 0, 1, false,
          "cleanup shard exhausted retry attempts", null, null, false);
      return;
    }
    try {
      RepositoryTotals totals = evaluateAndApply(claim, run);
      terminal(
          claim,
          run,
          totals.state(),
          totals.scanned(),
          totals.matched(),
          totals.wouldDelete(),
          totals.deleted(),
          totals.failed(),
          totals.truncated(),
          totals.error(),
          totals.startCursor(),
          totals.nextCursor(),
          totals.advanceCursor());
    } catch (CleanupFenceLostException lost) {
      metrics.fenceRejected();
      return;
    } catch (RuntimeException error) {
      retryOrFail(claim, run, error);
    }
  }

  private RepositoryTotals evaluateAndApply(
      ClaimedRunRepository claim, CleanupRun run) {
    if (cleanupDao.isRunCancellationRequested(run.id())) {
      return RepositoryTotals.cancelled();
    }
    RepositoryRecord repository = repositoryDao.findById(claim.repositoryId())
        .orElseThrow(() -> new CleanupValidationException(
            "target repository no longer exists: " + claim.repositoryId()));
    if (repository.format() != claim.format()) {
      throw new CleanupValidationException("target repository format changed");
    }
    if (!repository.online()) {
      throw new CleanupValidationException(
          "target repository is offline: " + repository.name());
    }
    int scanLimit = run.scanLimitPerRepository();
    if ("TRY_RUN".equals(run.mode())) {
      scanLimit = cleanupDao.reserveTryRunScanBudget(
          run.id(), claim.id(), scanLimit, MAX_TOTAL_TRY_RUN_SUBJECTS);
      if (scanLimit == 0) {
        return new RepositoryTotals(
            "SUCCEEDED_TRUNCATED", 0, 0, 0, 0, 0, true,
            "server-wide Try Run scan limit reached", null, null, false);
      }
    }
    heartbeat(claim);
    Instant cutoff = run.createdAt();
    CleanupScanCursor scanCursor = null;
    ScanResult scan;
    if ("EXECUTE".equals(run.mode())) {
      scanCursor = cleanupDao.acquireRunRepositoryScanCursor(
          claim.id(),
          claim.leaseToken(),
          claim.fencingToken(),
          repository.format() == com.github.klboke.kkrepo.core.RepositoryFormat.DOCKER
              ? "DOCKER" : "COMPONENT",
          databaseNow());
      scan = scanner.scan(
          repository, run.criteriaSnapshot(), scanLimit, cutoff, scanCursor);
    } else {
      scan = scanner.scan(repository, run.criteriaSnapshot(), scanLimit, cutoff);
    }
    CleanupRunItemSummary durableDecisions = cleanupDao.summarizeRunItems(claim.id());
    long deleted = durableDecisions == null ? 0 : durableDecisions.deletedSubjects();
    long wouldDelete = durableDecisions == null ? 0 : durableDecisions.wouldDeleteSubjects();
    long failed = 0;
    boolean deleteLimitReached = "EXECUTE".equals(run.mode())
        && deleted >= run.deleteLimitPerRepository();
    Instant evaluatedAt = databaseNow();
    List<CleanupRunItem> itemBatch = new ArrayList<>(RUN_ITEM_BATCH_SIZE);
    long pulseIntervalNanos = durationNanos(minimum(
        properties.getWorker().getHeartbeatInterval(), MAX_INLINE_PULSE_INTERVAL));
    long nextPulseNanos = System.nanoTime() + pulseIntervalNanos;
    List<Candidate> candidates = scan.candidates();
    for (int index = 0; index < candidates.size(); ) {
      if (itemBatch.size() >= RUN_ITEM_BATCH_SIZE || System.nanoTime() >= nextPulseNanos) {
        boolean cancelled = pulse(claim, run.id());
        flushRunItems(itemBatch);
        if (cancelled) {
          return cancelledTotals(scan, wouldDelete, deleted, failed, scanCursor);
        }
        nextPulseNanos = System.nanoTime() + pulseIntervalNanos;
      }
      Candidate candidate = candidates.get(index);
      if ("EXECUTE".equals(run.mode()) && deleted >= run.deleteLimitPerRepository()) {
        deleteLimitReached = true;
        break;
      }
      if (isNpmHostedBatch(claim, run, candidate)) {
        int maximumBatch = Math.min(
            RUN_ITEM_BATCH_SIZE - itemBatch.size(),
            run.deleteLimitPerRepository() - Math.toIntExact(deleted));
        int end = index;
        while (end < candidates.size()
            && end - index < maximumBatch
            && candidate.subject().familyKey().equals(
                candidates.get(end).subject().familyKey())) {
          end++;
        }
        List<Candidate> familyBatch = candidates.subList(index, end);
        try {
          List<ExecutionResult> results = execution.applyBatch(
              claim,
              run,
              familyBatch,
              "cleanup-run:" + run.id() + ":" + run.requestedBy());
          if (results.size() != familyBatch.size()) {
            throw new IllegalStateException("cleanup execution batch returned an invalid result count");
          }
          for (int batchIndex = 0; batchIndex < familyBatch.size(); batchIndex++) {
            ExecutionResult result = results.get(batchIndex);
            if ("DELETED".equals(result.decision())) deleted++;
          }
        } catch (CleanupFenceLostException lost) {
          throw lost;
        } catch (RuntimeException batchFailure) {
          String error = boundedMessage(batchFailure);
          failed += familyBatch.size();
          for (Candidate batchedCandidate : familyBatch) {
            itemBatch.add(item(
                claim.id(),
                batchedCandidate,
                "FAILED",
                error,
                new LinkedHashMap<>(batchedCandidate.reason()),
                batchedCandidate.protectionId(),
                evaluatedAt));
          }
        }
        index = end;
        if (deleted >= run.deleteLimitPerRepository() && index < candidates.size()) {
          deleteLimitReached = true;
          break;
        }
        continue;
      }
      if ("TRY_RUN".equals(run.mode())) {
        Long protectionId = candidate.protectionId();
        String decision = protectionId == null ? "WOULD_DELETE" : "KEEP_PROTECTED";
        if ("WOULD_DELETE".equals(decision)) wouldDelete++;
        itemBatch.add(item(
            claim.id(),
            candidate,
            decision,
            null,
            new LinkedHashMap<>(candidate.reason()),
            protectionId,
            evaluatedAt));
      } else {
        try {
          ExecutionResult result = execution.apply(
              claim,
              run,
              candidate,
              "cleanup-run:" + run.id() + ":" + run.requestedBy());
          if ("DELETED".equals(result.decision())) deleted++;
        } catch (CleanupFenceLostException lost) {
          throw lost;
        } catch (RuntimeException itemFailure) {
          failed++;
          itemBatch.add(item(
              claim.id(),
              candidate,
              "FAILED",
              boundedMessage(itemFailure),
              new LinkedHashMap<>(candidate.reason()),
              candidate.protectionId(),
              evaluatedAt));
        }
      }
      if ("EXECUTE".equals(run.mode())
          && deleted >= run.deleteLimitPerRepository()
          && index + 1 < candidates.size()) {
        deleteLimitReached = true;
        break;
      }
      index++;
    }
    boolean cancelled = pulse(claim, run.id());
    flushRunItems(itemBatch);
    if (cancelled) {
      return cancelledTotals(scan, wouldDelete, deleted, failed, scanCursor);
    }
    String state = failed > 0
        ? "PARTIAL"
        : deleteLimitReached
            ? "PARTIAL_LIMIT_REACHED"
            : scan.truncated() ? "SUCCEEDED_TRUNCATED" : "SUCCEEDED";
    List<String> warnings = new ArrayList<>();
    if (scan.safetyStatus() != null) warnings.add(scan.safetyStatus());
    if (scan.incompleteFamily() != null) {
      warnings.add("incomplete family was excluded from cleanup decisions");
    }
    if (scan.cursorWarning() != null) warnings.add(scan.cursorWarning());
    String error = warnings.isEmpty() ? null : String.join("; ", warnings);
    return new RepositoryTotals(
        state,
        scan.scannedSubjects(),
        scan.candidates().size(),
        wouldDelete,
        deleted,
        failed,
        scan.truncated(),
        error,
        scanCursor,
        scan.nextCursor(),
        "EXECUTE".equals(run.mode()) && !deleteLimitReached);
  }

  private void heartbeat(ClaimedRunRepository claim) {
    Instant now = databaseNow();
    Instant leaseUntil = now.plus(properties.getWorker().getLeaseDuration());
    if (!cleanupDao.heartbeatRunRepository(
        claim.id(), claim.leaseToken(), claim.fencingToken(), leaseUntil, now)) {
      throw new CleanupFenceLostException(claim.id());
    }
  }

  private boolean pulse(ClaimedRunRepository claim, long runId) {
    heartbeat(claim);
    return cleanupDao.isRunCancellationRequested(runId);
  }

  private void flushRunItems(List<CleanupRunItem> items) {
    if (items.isEmpty()) return;
    cleanupDao.upsertRunItems(List.copyOf(items));
    items.clear();
  }

  private static RepositoryTotals cancelledTotals(
      ScanResult scan,
      long wouldDelete,
      long deleted,
      long failed,
      CleanupScanCursor scanCursor) {
    return new RepositoryTotals(
        "CANCELLED",
        scan.scannedSubjects(),
        scan.candidates().size(),
        wouldDelete,
        deleted,
        failed,
        scan.truncated(),
        "run cancellation requested",
        scanCursor,
        scan.nextCursor(),
        false);
  }

  private static Duration minimum(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }

  private static long durationNanos(Duration duration) {
    try {
      return Math.max(1, duration.toNanos());
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static boolean isNpmHostedBatch(
      ClaimedRunRepository claim, CleanupRun run, Candidate candidate) {
    return "EXECUTE".equals(run.mode())
        && claim.format() == com.github.klboke.kkrepo.core.RepositoryFormat.NPM
        && claim.repositoryType() == com.github.klboke.kkrepo.core.RepositoryType.HOSTED
        && "COMPONENT".equals(candidate.subject().kind());
  }

  private void terminal(
      ClaimedRunRepository claim,
      CleanupRun run,
      String state,
      long scanned,
      long matched,
      long wouldDelete,
      long deleted,
      long failed,
      boolean truncated,
      String error,
      CleanupScanCursor startCursor,
      CleanupScanCursor nextCursor,
      boolean advanceCursor) {
    CleanupRunItemSummary durableDecisions = cleanupDao.summarizeRunItems(claim.id());
    if (durableDecisions != null) {
      matched = Math.max(matched, durableDecisions.decisions());
      scanned = Math.max(scanned, matched);
      wouldDelete = durableDecisions.wouldDeleteSubjects();
      deleted = durableDecisions.deletedSubjects();
      failed = Math.max(failed, durableDecisions.failedSubjects());
      if (durableDecisions.failedSubjects() > 0
          && !"FAILED".equals(state)
          && !"CANCELLED".equals(state)) {
        state = "PARTIAL";
      }
    }
    Instant completedAt = databaseNow();
    boolean completed;
    if (advanceCursor && startCursor != null && nextCursor != null) {
      var completion = cleanupDao.completeClaimedRunRepositoryAndAdvanceCursor(
          claim.id(),
          claim.leaseToken(),
          claim.fencingToken(),
          state,
          scanned,
          matched,
          wouldDelete,
          deleted,
          failed,
          truncated,
          boundedText(error),
          completedAt,
          startCursor,
          nextCursor);
      completed = completion != null && completion.completed();
      if (completed && !completion.cursorAdvanced()) metrics.cursorConflict();
    } else {
      completed = cleanupDao.completeClaimedRunRepository(
          claim.id(),
          claim.leaseToken(),
          claim.fencingToken(),
          state,
          scanned,
          matched,
          wouldDelete,
          deleted,
          failed,
          truncated,
          boundedText(error),
          completedAt);
    }
    if (!completed) {
      metrics.fenceRejected();
      return;
    }
    metrics.shard(
        state.toLowerCase(Locale.ROOT), run.mode(), scanned, matched, deleted, failed);
    aggregate(run.id());
  }

  private void retryOrFail(
      ClaimedRunRepository claim, CleanupRun run, RuntimeException error) {
    String summary = boundedMessage(error);
    if (claim.attemptCount() >= claim.maxAttempts()) {
      terminal(claim, run, "FAILED", 0, 0, 0, 0, 1, false, summary, null, null, false);
      return;
    }
    Instant now = databaseNow();
    Duration base = properties.getWorker().getRetryBaseDelay();
    Duration maximum = properties.getWorker().getRetryMaxDelay();
    long multiplier = 1L << Math.min(20, Math.max(0, claim.attemptCount() - 1));
    Duration delay;
    try {
      delay = base.multipliedBy(multiplier);
    } catch (ArithmeticException overflow) {
      delay = maximum;
    }
    if (delay.compareTo(maximum) > 0) delay = maximum;
    boolean retrying = cleanupDao.retryClaimedRunRepository(
        claim.id(),
        claim.leaseToken(),
        claim.fencingToken(),
        now.plus(delay),
        error.getClass().getSimpleName(),
        summary,
        now);
    if (!retrying) metrics.fenceRejected();
  }

  void aggregate(long runId) {
    CleanupRun run = cleanupDao.findRun(runId).orElse(null);
    if (run == null || terminalState(run.state())) return;
    List<CleanupRunRepository> shards = cleanupDao.listRunRepositories(runId);
    if (shards.isEmpty() || shards.stream().anyMatch(shard -> !terminalState(shard.state()))) {
      return;
    }
    long scanned = shards.stream().mapToLong(CleanupRunRepository::scannedSubjects).sum();
    long matched = shards.stream().mapToLong(CleanupRunRepository::matchedSubjects).sum();
    long wouldDelete = shards.stream()
        .mapToLong(CleanupRunRepository::wouldDeleteSubjects)
        .sum();
    long deleted = shards.stream().mapToLong(CleanupRunRepository::deletedSubjects).sum();
    long failed = shards.stream().mapToLong(CleanupRunRepository::failedSubjects).sum();
    int truncated = (int) shards.stream().filter(CleanupRunRepository::truncated).count();
    long failedShards = shards.stream().filter(shard -> "FAILED".equals(shard.state())).count();
    long cancelledShards = shards.stream().filter(shard -> "CANCELLED".equals(shard.state())).count();
    boolean limit = shards.stream().anyMatch(
        shard -> "PARTIAL_LIMIT_REACHED".equals(shard.state()));
    String state;
    if (cleanupDao.isRunCancellationRequested(runId) || cancelledShards > 0) {
      state = "CANCELLED";
    } else if (failedShards == shards.size()) {
      state = "FAILED";
    } else if (failedShards > 0 || failed > 0) {
      state = "PARTIAL";
    } else if (limit) {
      state = "PARTIAL_LIMIT_REACHED";
    } else if (truncated > 0) {
      state = "SUCCEEDED_TRUNCATED";
    } else {
      state = "SUCCEEDED";
    }
    String errors = shards.stream()
        .filter(shard -> shard.errorSummary() != null && !shard.errorSummary().isBlank())
        .map(shard -> shard.repositoryName() + ": " + shard.errorSummary())
        .collect(java.util.stream.Collectors.joining("; "));
    Instant completedAt = databaseNow();
    boolean completed = cleanupDao.completeRun(
        runId,
        state,
        scanned,
        matched,
        wouldDelete,
        deleted,
        failed,
        truncated,
        errors.isBlank() ? null : boundedText(errors),
        completedAt);
    if (completed) {
      Instant startedAt = run.startedAt() == null ? run.createdAt() : run.startedAt();
      metrics.run(
          state.toLowerCase(Locale.ROOT),
          run.mode(),
          run.triggerKind(),
          startedAt == null ? Duration.ZERO : Duration.between(startedAt, completedAt));
    }
  }

  private RunView view(CleanupRun run) {
    return new RunView(run, cleanupDao.listRunRepositories(run.id()));
  }

  private Instant databaseNow() {
    Instant value = cleanupDao.currentTime();
    return value == null ? clock.instant() : value;
  }

  private CleanupPolicy requirePolicy(long policyId) {
    return cleanupDao.findPolicy(policyId)
        .orElseThrow(() -> new CleanupNotFoundException("cleanup policy", policyId));
  }

  private CleanupRun requireRun(long runId) {
    return cleanupDao.findRun(runId)
        .orElseThrow(() -> new CleanupNotFoundException("cleanup run", runId));
  }

  private void validateExecuteCapability(CleanupPolicy policy, String mode) {
    if ("EXECUTE".equals(mode) && !capabilities.supportsExecute(policy.format())) {
      throw new CleanupValidationException(
          "execution is not available for format " + policy.format());
    }
  }

  private static int effectiveScanLimit(
      CleanupPolicy policy, Integer requestedLimit, String mode) {
    if (mode.equals("EXECUTE")) return policy.scanLimitPerRepository();
    if (requestedLimit == null) {
      throw new CleanupValidationException("scanLimitPerRepository is required for TRY_RUN");
    }
    if (requestedLimit < 1 || requestedLimit > CleanupPolicyService.MAX_SCAN_LIMIT) {
      throw new CleanupValidationException(
          "scanLimitPerRepository must be between 1 and " + CleanupPolicyService.MAX_SCAN_LIMIT);
    }
    return Math.min(requestedLimit, policy.scanLimitPerRepository());
  }

  private static CleanupRunItem item(
      long runRepositoryId,
      Candidate candidate,
      String decision,
      String error,
      Map<String, Object> reason,
      Long protectionId,
      Instant evaluatedAt) {
    return new CleanupRunItem(
        null,
        runRepositoryId,
        candidate.subject().kind(),
        candidate.subject().key(),
        candidate.subject().keyHash(),
        candidate.subject().familyKey(),
        candidate.subject().displayName(),
        candidate.subject().version(),
        candidate.subject().deletePath(),
        candidate.subject().lastDownloadedAt(),
        candidate.subject().publishedAt(),
        candidate.subject().assetCount(),
        candidate.subject().estimatedBytes(),
        candidate.subject().contentToken(),
        candidate.subject().usageRevision(),
        protectionId,
        evaluatedAt,
        decision,
        Map.copyOf(reason),
        error,
        null,
        null);
  }

  private static boolean terminalState(String state) {
    return switch (state) {
      case "SUCCEEDED", "SUCCEEDED_TRUNCATED", "PARTIAL_LIMIT_REACHED", "PARTIAL",
          "FAILED", "CANCELLED" -> true;
      default -> false;
    };
  }

  private static String boundedMessage(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
    return boundedText(message);
  }

  private static String boundedText(String value) {
    if (value == null) return null;
    return value.length() <= 2_048 ? value : value.substring(0, 2_048);
  }

  public record RunCommand(
      String mode, Long expectedPolicyRevision, Integer scanLimitPerRepository) {
  }

  public record RunView(CleanupRun run, List<CleanupRunRepository> repositories) {
  }

  public record RunDetailView(
      CleanupRun run,
      List<CleanupRunRepository> repositories,
      Map<Long, List<CleanupRunItem>> itemsByRepository) {
  }

  private record RepositoryTotals(
      String state,
      long scanned,
      long matched,
      long wouldDelete,
      long deleted,
      long failed,
      boolean truncated,
      String error,
      CleanupScanCursor startCursor,
      CleanupScanCursor nextCursor,
      boolean advanceCursor) {
    static RepositoryTotals cancelled() {
      return new RepositoryTotals("CANCELLED", 0, 0, 0, 0, 0, false,
          "run cancellation requested", null, null, false);
    }
  }
}
