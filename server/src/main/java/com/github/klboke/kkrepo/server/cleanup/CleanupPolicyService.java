package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.TargetRepository;
import com.github.klboke.kkrepo.persistence.jdbc.api.RepositoryDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.model.RepositoryRecord;
import com.github.klboke.kkrepo.server.cleanup.CleanupPolicyCapabilities.FormatCapability;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CleanupPolicyService {
  static final int DEFAULT_SCAN_LIMIT = 1_000;
  static final int DEFAULT_DELETE_LIMIT = 100;
  static final int MAX_SCAN_LIMIT = 10_000;
  static final int MAX_DELETE_LIMIT = 1_000;
  static final int MAX_TARGET_REPOSITORIES = 100;

  private final CleanupPolicyDao cleanupDao;
  private final RepositoryDao repositoryDao;
  private final CleanupPolicyCapabilities capabilities;
  private final CleanupQuartzScheduleManager scheduleManager;
  private final CleanupUsageTrackingService usageTracking;
  private final Clock clock;

  @Autowired
  public CleanupPolicyService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupPolicyCapabilities capabilities,
      CleanupQuartzScheduleManager scheduleManager,
      CleanupUsageTrackingService usageTracking) {
    this(
        cleanupDao,
        repositoryDao,
        capabilities,
        scheduleManager,
        usageTracking,
        Clock.systemUTC());
  }

  CleanupPolicyService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupPolicyCapabilities capabilities,
      Clock clock) {
    this(cleanupDao, repositoryDao, capabilities, null, null, clock);
  }

  CleanupPolicyService(
      CleanupPolicyDao cleanupDao,
      RepositoryDao repositoryDao,
      CleanupPolicyCapabilities capabilities,
      CleanupQuartzScheduleManager scheduleManager,
      CleanupUsageTrackingService usageTracking,
      Clock clock) {
    this.cleanupDao = cleanupDao;
    this.repositoryDao = repositoryDao;
    this.capabilities = capabilities;
    this.scheduleManager = scheduleManager;
    this.usageTracking = usageTracking;
    this.clock = clock;
  }

  public List<PolicyView> list() {
    return listPage(0, 100).items();
  }

  public PolicyPage listPage(long afterId, int limit) {
    int safeLimit = Math.min(Math.max(1, limit), 100);
    List<CleanupPolicy> rows = cleanupDao.listPolicies(Math.max(0, afterId), safeLimit + 1);
    boolean hasMore = rows.size() > safeLimit;
    List<CleanupPolicy> page = hasMore ? rows.subList(0, safeLimit) : rows;
    List<PolicyView> items = views(page);
    Long nextAfter = hasMore && !page.isEmpty() ? page.get(page.size() - 1).id() : null;
    return new PolicyPage(items, nextAfter);
  }

  public PolicyView get(long policyId) {
    return view(requirePolicy(policyId));
  }

  public List<FormatCapability> formatCapabilities() {
    return capabilities.all();
  }

  public SchedulePreview previewSchedule(ScheduleCommand command) {
    ScheduleCommand validated = validateSchedule(command);
    if (validated == null) {
      throw new CleanupValidationException("schedule is required");
    }
    Instant evaluatedAt = databaseNow();
    List<Instant> nextRuns = nextRunTimes(validated, evaluatedAt, 2);
    if (nextRuns.isEmpty()) {
      throw new CleanupValidationException("schedule has no future fire time");
    }
    return new SchedulePreview(
        validated.cronExpression(),
        validated.timeZone(),
        evaluatedAt,
        nextRuns);
  }

  @Transactional
  public PolicyView create(PolicyCommand command) {
    ValidatedCommand validated = validate(command, null);
    Instant now = databaseNow();
    CleanupPolicy policy = new CleanupPolicy(
        null,
        validated.name(),
        validated.format(),
        validated.notes(),
        validated.criteria(),
        1,
        "PAUSED",
        validated.scanLimitPerRepository(),
        validated.deleteLimitPerRepository(),
        now,
        now);
    long policyId = cleanupDao.createPolicy(policy);
    cleanupDao.replaceTargets(policyId, validated.repositoryIds());
    if (validated.schedule() != null) {
      cleanupDao.upsertSchedule(schedule(policyId, validated.schedule(), false, now));
    }
    reconcileScheduleAfterCommit(policyId);
    reconcileUsageTrackingAfterCommit();
    return get(policyId);
  }

  @Transactional
  public PolicyView update(long policyId, PolicyCommand command) {
    CleanupPolicy existing = requirePolicy(policyId);
    if (command == null || command.revision() == null) {
      throw new CleanupValidationException("revision is required");
    }
    if (command.revision() != existing.revision()) {
      throw new CleanupRevisionConflictException(policyId, existing.revision());
    }
    ValidatedCommand validated = validate(command, policyId);
    List<Long> previousTargets = cleanupDao.listTargets(policyId).stream()
        .map(TargetRepository::id).sorted().toList();
    List<Long> nextTargets = validated.repositoryIds().stream().sorted().toList();
    boolean cleanupConfigurationChanged = existing.format() != validated.format()
        || !Objects.equals(existing.criteria(), validated.criteria())
        || existing.scanLimitPerRepository() != validated.scanLimitPerRepository()
        || existing.deleteLimitPerRepository() != validated.deleteLimitPerRepository()
        || !previousTargets.equals(nextTargets);

    CleanupSchedule previousSchedule = cleanupDao.findSchedule(policyId).orElse(null);
    ScheduleCommand requestedSchedule = validated.schedule();
    boolean requestedEnabled = requestedSchedule != null && requestedSchedule.enabled();
    boolean scheduleEnabled = requestedEnabled && !cleanupConfigurationChanged;
    if (scheduleEnabled && !capabilities.supportsExecute(validated.format())) {
      throw new CleanupValidationException(
          "scheduled execution is not available for format " + validated.format());
    }
    String state = scheduleEnabled ? "ACTIVE" : "PAUSED";
    Instant now = databaseNow();
    CleanupPolicy updated = new CleanupPolicy(
        policyId,
        validated.name(),
        validated.format(),
        validated.notes(),
        validated.criteria(),
        existing.revision() + 1,
        state,
        validated.scanLimitPerRepository(),
        validated.deleteLimitPerRepository(),
        existing.createdAt(),
        now);
    if (!cleanupDao.updatePolicy(updated, existing.revision())) {
      long currentRevision = cleanupDao.findPolicy(policyId)
          .map(CleanupPolicy::revision).orElse(existing.revision());
      throw new CleanupRevisionConflictException(policyId, currentRevision);
    }
    cleanupDao.replaceTargets(policyId, validated.repositoryIds());
    if (requestedSchedule == null) {
      cleanupDao.deleteSchedule(policyId);
    } else {
      boolean keepEnabled = scheduleEnabled
          || (!cleanupConfigurationChanged
              && previousSchedule != null
              && previousSchedule.enabled()
              && requestedSchedule.enabled());
      cleanupDao.upsertSchedule(schedule(policyId, requestedSchedule, keepEnabled, now));
    }
    reconcileScheduleAfterCommit(policyId);
    reconcileUsageTrackingAfterCommit();
    return get(policyId);
  }

  @Transactional
  public void delete(long policyId, long expectedRevision) {
    CleanupPolicy existing = requirePolicy(policyId);
    if (existing.revision() != expectedRevision) {
      throw new CleanupRevisionConflictException(policyId, existing.revision());
    }
    cleanupDao.deleteSchedule(policyId);
    cleanupDao.replaceTargets(policyId, List.of());
    if (!cleanupDao.markPolicyDeleted(policyId, expectedRevision, databaseNow())) {
      throw new CleanupRevisionConflictException(policyId, existing.revision());
    }
    reconcileScheduleAfterCommit(policyId);
    reconcileUsageTrackingAfterCommit();
  }

  private PolicyView view(CleanupPolicy policy) {
    CleanupSchedule persistedSchedule = cleanupDao.findSchedule(policy.id()).orElse(null);
    FormatCapability capability = capabilities.all().stream()
        .filter(item -> item.format() == policy.format())
        .findFirst()
        .orElseThrow();
    return new PolicyView(
        policy,
        cleanupDao.listTargets(policy.id()),
        scheduleView(
            persistedSchedule,
            persistedSchedule != null && persistedSchedule.enabled() ? databaseNow() : null),
        capability);
  }

  private List<PolicyView> views(List<CleanupPolicy> policies) {
    if (policies == null || policies.isEmpty()) return List.of();
    List<Long> policyIds = policies.stream().map(CleanupPolicy::id).toList();
    Map<Long, List<TargetRepository>> targets = cleanupDao.listTargets(policyIds);
    Map<Long, CleanupSchedule> schedules = cleanupDao.findSchedules(policyIds);
    Map<RepositoryFormat, FormatCapability> capabilitiesByFormat = new LinkedHashMap<>();
    capabilities.all().forEach(capability -> capabilitiesByFormat.put(
        capability.format(), capability));
    Instant evaluatedAt = schedules.values().stream().anyMatch(CleanupSchedule::enabled)
        ? databaseNow()
        : null;
    return policies.stream().map(policy -> new PolicyView(
        policy,
        targets.getOrDefault(policy.id(), List.of()),
        scheduleView(schedules.get(policy.id()), evaluatedAt),
        Objects.requireNonNull(
            capabilitiesByFormat.get(policy.format()),
            "cleanup capability is missing for " + policy.format())))
        .toList();
  }

  private CleanupSchedule scheduleView(CleanupSchedule persistedSchedule, Instant evaluatedAt) {
    CleanupSchedule schedule = persistedSchedule == null
        ? null
        : new CleanupSchedule(
            persistedSchedule.policyId(),
            persistedSchedule.cronExpression(),
            persistedSchedule.timeZone(),
            persistedSchedule.enabled(),
            persistedSchedule.enabled()
                ? nextRunAtOrNull(new ScheduleCommand(
                    persistedSchedule.cronExpression(),
                    persistedSchedule.timeZone(),
                    true), evaluatedAt)
                : null,
            persistedSchedule.createdAt(),
            persistedSchedule.updatedAt());
    return schedule;
  }

  private CleanupPolicy requirePolicy(long policyId) {
    return cleanupDao.findPolicy(policyId)
        .orElseThrow(() -> new CleanupNotFoundException("cleanup policy", policyId));
  }

  private Instant databaseNow() {
    Instant value = cleanupDao.currentTime();
    return value == null ? clock.instant() : value;
  }

  private ValidatedCommand validate(PolicyCommand command, Long policyId) {
    if (command == null) {
      throw new CleanupValidationException("request body is required");
    }
    String name = command.name() == null ? "" : command.name().trim();
    if (name.isEmpty() || name.length() > 200) {
      throw new CleanupValidationException("name must be between 1 and 200 characters");
    }
    if (command.format() == null) {
      throw new CleanupValidationException("format is required");
    }
    Map<String, Object> criteria = command.criteria() == null
        ? Map.of()
        : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(command.criteria()));
    CleanupCriteria parsedCriteria = CleanupCriteria.parse(criteria);
    if (parsedCriteria.retainCount() != null
        && !capabilities.supportsRetainCount(command.format())) {
      throw new CleanupValidationException(
          "retainCount is not available for format " + command.format());
    }
    if (parsedCriteria.lastDownloadedOlderThanDays() != null
        && !capabilities.supportsLastDownloaded(command.format())) {
      throw new CleanupValidationException(
          "lastDownloadedOlderThanDays is not available for format " + command.format());
    }
    int scanLimit = command.scanLimitPerRepository() == null
        ? DEFAULT_SCAN_LIMIT
        : command.scanLimitPerRepository();
    int deleteLimit = command.deleteLimitPerRepository() == null
        ? DEFAULT_DELETE_LIMIT
        : command.deleteLimitPerRepository();
    requireLimit(scanLimit, MAX_SCAN_LIMIT, "scanLimitPerRepository");
    requireLimit(deleteLimit, MAX_DELETE_LIMIT, "deleteLimitPerRepository");

    List<Long> repositoryIds = distinctIds(command.repositoryIds());
    if (repositoryIds.isEmpty()) {
      throw new CleanupValidationException("at least one target repository is required");
    }
    if (repositoryIds.size() > MAX_TARGET_REPOSITORIES) {
      throw new CleanupValidationException(
          "a policy can target at most " + MAX_TARGET_REPOSITORIES + " repositories");
    }
    for (Long repositoryId : repositoryIds) {
      RepositoryRecord repository = repositoryDao.findById(repositoryId)
          .orElseThrow(() -> new CleanupValidationException(
              "target repository does not exist: " + repositoryId));
      if (repository.format() != command.format()) {
        throw new CleanupValidationException(
            "all target repositories must use format " + command.format());
      }
      CleanupTargetRepositories.requireSupported(repository.type());
    }
    ScheduleCommand schedule = validateSchedule(command.schedule());
    String notes = blankToNull(command.notes());
    if (notes != null && notes.length() > 2_048) {
      throw new CleanupValidationException("notes must be at most 2048 characters");
    }
    return new ValidatedCommand(
        name,
        command.format(),
        notes,
        criteria,
        repositoryIds,
        scanLimit,
        deleteLimit,
        schedule,
        policyId);
  }

  private ScheduleCommand validateSchedule(ScheduleCommand schedule) {
    if (schedule == null) {
      return null;
    }
    String cronExpression = schedule.cronExpression() == null
        ? ""
        : schedule.cronExpression().trim();
    if (cronExpression.length() > 120 || !CronExpression.isValidExpression(cronExpression)) {
      throw new CleanupValidationException(
          "schedule.cronExpression must be a valid Quartz Cron expression");
    }
    String timeZone = schedule.timeZone() == null ? "" : schedule.timeZone().trim();
    try {
      ZoneId.of(timeZone);
    } catch (RuntimeException e) {
      throw new CleanupValidationException("schedule.timeZone must be a valid IANA time zone");
    }
    return new ScheduleCommand(
        cronExpression,
        timeZone,
        schedule.enabled());
  }

  private CleanupSchedule schedule(
      long policyId,
      ScheduleCommand command,
      boolean enabled,
      Instant now) {
    return new CleanupSchedule(
        policyId,
        command.cronExpression(),
        command.timeZone(),
        enabled,
        enabled ? nextRunAt(command, now) : null,
        now,
        now);
  }

  static Instant nextRunAt(ScheduleCommand schedule, Instant after) {
    List<Instant> nextRuns = nextRunTimes(schedule, after, 1);
    if (nextRuns.isEmpty()) {
      throw new CleanupValidationException("schedule has no future fire time");
    }
    return nextRuns.get(0);
  }

  private static Instant nextRunAtOrNull(ScheduleCommand schedule, Instant after) {
    List<Instant> nextRuns = nextRunTimes(schedule, after, 1);
    return nextRuns.isEmpty() ? null : nextRuns.get(0);
  }

  static List<Instant> nextRunTimes(ScheduleCommand schedule, Instant after, int count) {
    if (count < 1) {
      return List.of();
    }
    try {
      CronExpression expression = new CronExpression(schedule.cronExpression());
      expression.setTimeZone(java.util.TimeZone.getTimeZone(ZoneId.of(schedule.timeZone())));
      List<Instant> result = new ArrayList<>(count);
      Date cursor = Date.from(after);
      while (result.size() < count) {
        Date next = expression.getNextValidTimeAfter(cursor);
        if (next == null) {
          break;
        }
        result.add(next.toInstant());
        cursor = next;
      }
      return List.copyOf(result);
    } catch (java.text.ParseException e) {
      throw new CleanupValidationException(
          "schedule.cronExpression must be a valid Quartz Cron expression");
    }
  }

  private void reconcileScheduleAfterCommit(long policyId) {
    if (scheduleManager != null) {
      scheduleManager.reconcileAfterCommit(policyId);
    }
  }

  private void reconcileUsageTrackingAfterCommit() {
    if (usageTracking != null) {
      usageTracking.reconcileAfterCommit();
    }
  }

  private static List<Long> distinctIds(List<Long> repositoryIds) {
    if (repositoryIds == null) {
      return List.of();
    }
    Set<Long> result = new LinkedHashSet<>();
    for (Long repositoryId : repositoryIds) {
      if (repositoryId == null || repositoryId <= 0) {
        throw new CleanupValidationException("repositoryIds must contain positive ids");
      }
      result.add(repositoryId);
    }
    List<Long> ids = new ArrayList<>(result);
    ids.sort(Comparator.naturalOrder());
    return List.copyOf(ids);
  }

  private static void requireLimit(int value, int maximum, String field) {
    if (value < 1 || value > maximum) {
      throw new CleanupValidationException(field + " must be between 1 and " + maximum);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record PolicyCommand(
      String name,
      RepositoryFormat format,
      String notes,
      Map<String, Object> criteria,
      List<Long> repositoryIds,
      Integer scanLimitPerRepository,
      Integer deleteLimitPerRepository,
      ScheduleCommand schedule,
      Long revision) {
  }

  public record ScheduleCommand(
      String cronExpression,
      String timeZone,
      boolean enabled) {
  }

  public record SchedulePreview(
      String cronExpression,
      String timeZone,
      Instant evaluatedAt,
      List<Instant> nextRuns) {
  }

  public record PolicyView(
      CleanupPolicy policy,
      List<TargetRepository> repositories,
      CleanupSchedule schedule,
      FormatCapability capability) {
  }

  public record PolicyPage(List<PolicyView> items, Long nextAfter) {
  }

  private record ValidatedCommand(
      String name,
      RepositoryFormat format,
      String notes,
      Map<String, Object> criteria,
      List<Long> repositoryIds,
      int scanLimitPerRepository,
      int deleteLimitPerRepository,
      ScheduleCommand schedule,
      Long policyId) {
  }
}
