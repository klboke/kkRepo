package com.github.klboke.kkrepo.server.cleanup;

import static com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob.POLICY_ID;
import static com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob.POLICY_REVISION;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps Quartz's derived trigger state in sync with the cleanup policy aggregate. */
@Component
public class CleanupQuartzScheduleManager implements ApplicationRunner {
  static final String GROUP = "kkrepo-cleanup";
  private static final String RECONCILIATION_CURSOR = "cleanup_quartz_reconcile";

  private static final Logger log = LoggerFactory.getLogger(CleanupQuartzScheduleManager.class);

  private final CleanupPolicyDao cleanupDao;
  private final Scheduler scheduler;
  private final boolean enabled;
  private final MaintenanceCursorDao cursors;
  private final TransactionTemplate transactions;
  private final long fullReconciliationIntervalMillis;
  private final Set<Long> pendingPolicyIds = ConcurrentHashMap.newKeySet();

  @Autowired
  public CleanupQuartzScheduleManager(
      CleanupPolicyDao cleanupDao,
      Scheduler scheduler,
      CleanupRuntimeProperties runtimeProperties,
      MaintenanceCursorDao cursors,
      PlatformTransactionManager transactionManager,
      @Value("${kkrepo.cleanup.scheduler.full-reconcile-min-interval-ms:300000}")
      long fullReconciliationIntervalMillis,
      @Value("${kkrepo.cleanup.scheduler.enabled:true}") boolean enabled) {
    this(
        cleanupDao,
        scheduler,
        runtimeProperties.isEnabled() && enabled,
        cursors,
        new TransactionTemplate(transactionManager),
        fullReconciliationIntervalMillis);
  }

  CleanupQuartzScheduleManager(
      CleanupPolicyDao cleanupDao, Scheduler scheduler, boolean enabled) {
    this(cleanupDao, scheduler, enabled, null, null, 0);
  }

  CleanupQuartzScheduleManager(
      CleanupPolicyDao cleanupDao,
      Scheduler scheduler,
      boolean enabled,
      MaintenanceCursorDao cursors,
      TransactionTemplate transactions,
      long fullReconciliationIntervalMillis) {
    this.cleanupDao = cleanupDao;
    this.scheduler = scheduler;
    this.enabled = enabled;
    this.cursors = cursors;
    this.transactions = transactions;
    this.fullReconciliationIntervalMillis = Math.max(1_000, fullReconciliationIntervalMillis);
  }

  @Override
  public void run(ApplicationArguments args) {
    reconcileAllSafely();
  }

  public void reconcileAfterCommit(long policyId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          pendingPolicyIds.add(policyId);
        }
      });
      return;
    }
    reconcileSafely(policyId);
  }

  /**
   * Projects recently committed policy changes after Spring has fully unbound the transaction's
   * JDBC connection. The in-memory set is only a low-latency hint; the durable policy table and
   * full reconciliation remain the cross-replica source of correctness.
   */
  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.scheduler.projection-delay-ms:1000}",
      initialDelayString = "${kkrepo.cleanup.scheduler.projection-initial-delay-ms:1000}")
  public void reconcilePendingSafely() {
    for (Long policyId : Set.copyOf(pendingPolicyIds)) {
      if (pendingPolicyIds.remove(policyId)) {
        reconcileSafely(policyId);
      }
    }
  }

  @Scheduled(
      fixedDelayString = "${kkrepo.cleanup.scheduler.reconcile-interval-ms:60000}",
      initialDelayString = "${kkrepo.cleanup.scheduler.reconcile-initial-delay-ms:60000}")
  public void reconcileAllSafely() {
    try {
      if (!reserveFullReconciliation()) return;
      reconcileAll();
    } catch (RuntimeException | SchedulerException e) {
      log.warn("Cleanup Quartz schedule reconciliation failed", e);
    }
  }

  void reconcileAll() throws SchedulerException {
    Set<Long> persistedPolicyIds = new HashSet<>();
    var schedules = cleanupDao.listSchedules();
    var policies = cleanupDao.findPolicies(
        schedules.stream().map(CleanupSchedule::policyId).toList());
    Set<JobKey> existingJobKeys = scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP));
    for (CleanupSchedule schedule : schedules) {
      persistedPolicyIds.add(schedule.policyId());
      reconcile(
          schedule.policyId(),
          schedule,
          policies.get(schedule.policyId()),
          existingJobKeys.contains(jobKey(schedule.policyId())));
    }
    for (JobKey jobKey : existingJobKeys) {
      long policyId = policyId(jobKey);
      if (policyId > 0 && !persistedPolicyIds.contains(policyId)) {
        scheduler.deleteJob(jobKey);
      }
    }
  }

  void reconcile(long policyId) throws SchedulerException {
    CleanupSchedule schedule = cleanupDao.findSchedule(policyId).orElse(null);
    var policy = cleanupDao.findPolicy(policyId).orElse(null);
    // A targeted projection follows a known policy mutation. Quartz delete is idempotent and
    // getJobDetail already distinguishes create from replace, so an extra existence query adds no
    // value on this low-latency path.
    reconcile(policyId, schedule, policy, true);
  }

  private void reconcile(
      long policyId,
      CleanupSchedule schedule,
      CleanupPolicy policy,
      boolean jobExists)
      throws SchedulerException {
    JobKey jobKey = jobKey(policyId);
    if (!enabled || schedule == null || !schedule.enabled() || policy == null
        || !"ACTIVE".equals(policy.state())) {
      if (jobExists) scheduler.deleteJob(jobKey);
      return;
    }

    JobDetail job = JobBuilder.newJob(CleanupQuartzJob.class)
        .withIdentity(jobKey)
        .usingJobData(POLICY_ID, policyId)
        .usingJobData(POLICY_REVISION, policy.revision())
        .storeDurably()
        .requestRecovery(true)
        .build();
    JobDetail existingJob = jobExists ? scheduler.getJobDetail(jobKey) : null;
    boolean jobChanged = existingJob == null
        || existingJob.getJobDataMap().getLong(POLICY_REVISION) != policy.revision();
    if (existingJob == null) {
      scheduler.addJob(job, false);
    } else if (jobChanged) {
      scheduler.addJob(job, true);
    }

    TriggerKey triggerKey = triggerKey(policyId);
    Trigger existingTrigger = scheduler.getTrigger(triggerKey);
    TimeZone timeZone = TimeZone.getTimeZone(ZoneId.of(schedule.timeZone()));
    boolean triggerChanged = !(existingTrigger instanceof CronTrigger cronTrigger)
        || !cronTrigger.getCronExpression().equals(schedule.cronExpression())
        || !cronTrigger.getTimeZone().getID().equals(timeZone.getID());
    if (!triggerChanged) {
      return;
    }
    CronTrigger trigger = TriggerBuilder.newTrigger()
        .withIdentity(triggerKey)
        .forJob(jobKey)
        .startAt(new Date())
        .withSchedule(CronScheduleBuilder.cronSchedule(schedule.cronExpression())
            .inTimeZone(timeZone)
            .withMisfireHandlingInstructionDoNothing())
        .build();
    if (existingTrigger == null) {
      scheduler.scheduleJob(trigger);
    } else {
      scheduler.rescheduleJob(triggerKey, trigger);
    }
  }

  private boolean reserveFullReconciliation() {
    if (cursors == null || transactions == null) return true;
    cursors.ensureCursor(RECONCILIATION_CURSOR);
    Boolean reserved = transactions.execute(ignored -> {
      OptionalLong locked = cursors.tryLockLastSeenId(RECONCILIATION_CURSOR);
      if (locked.isEmpty()) return false;
      long now = cleanupDao.currentTime().toEpochMilli();
      if (locked.getAsLong() + fullReconciliationIntervalMillis > now) return false;
      return cursors.updateLastSeenId(RECONCILIATION_CURSOR, now) == 1;
    });
    return Boolean.TRUE.equals(reserved);
  }

  private void reconcileSafely(long policyId) {
    try {
      reconcile(policyId);
    } catch (RuntimeException | SchedulerException e) {
      log.warn("Cleanup Quartz policy reconciliation failed: policy={}", policyId, e);
    }
  }

  static JobKey jobKey(long policyId) {
    return JobKey.jobKey("policy-" + policyId, GROUP);
  }

  static TriggerKey triggerKey(long policyId) {
    return TriggerKey.triggerKey("policy-" + policyId, GROUP);
  }

  private static long policyId(JobKey jobKey) {
    String name = jobKey.getName();
    if (!name.startsWith("policy-")) {
      return -1;
    }
    try {
      return Long.parseLong(name.substring("policy-".length()));
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
