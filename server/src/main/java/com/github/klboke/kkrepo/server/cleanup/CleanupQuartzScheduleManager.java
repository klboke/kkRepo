package com.github.klboke.kkrepo.server.cleanup;

import static com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob.POLICY_ID;
import static com.github.klboke.kkrepo.server.cleanup.CleanupQuartzJob.POLICY_REVISION;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps Quartz's derived trigger state in sync with the cleanup policy aggregate. */
@Component
public class CleanupQuartzScheduleManager implements ApplicationRunner {
  static final String GROUP = "kkrepo-cleanup";

  private static final Logger log = LoggerFactory.getLogger(CleanupQuartzScheduleManager.class);

  private final CleanupPolicyDao cleanupDao;
  private final Scheduler scheduler;
  private final boolean enabled;
  private final Set<Long> pendingPolicyIds = ConcurrentHashMap.newKeySet();

  @Autowired
  public CleanupQuartzScheduleManager(
      CleanupPolicyDao cleanupDao,
      Scheduler scheduler,
      CleanupRuntimeProperties runtimeProperties,
      @Value("${kkrepo.cleanup.scheduler.enabled:true}") boolean enabled) {
    this(cleanupDao, scheduler, runtimeProperties.isEnabled() && enabled);
  }

  CleanupQuartzScheduleManager(
      CleanupPolicyDao cleanupDao, Scheduler scheduler, boolean enabled) {
    this.cleanupDao = cleanupDao;
    this.scheduler = scheduler;
    this.enabled = enabled;
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
      reconcileAll();
    } catch (RuntimeException | SchedulerException e) {
      log.warn("Cleanup Quartz schedule reconciliation failed", e);
    }
  }

  void reconcileAll() throws SchedulerException {
    Set<Long> persistedPolicyIds = new HashSet<>();
    for (CleanupSchedule schedule : cleanupDao.listSchedules()) {
      persistedPolicyIds.add(schedule.policyId());
      reconcile(schedule.policyId());
    }
    for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))) {
      long policyId = policyId(jobKey);
      if (policyId > 0 && !persistedPolicyIds.contains(policyId)) {
        scheduler.deleteJob(jobKey);
      }
    }
  }

  void reconcile(long policyId) throws SchedulerException {
    CleanupSchedule schedule = cleanupDao.findSchedule(policyId).orElse(null);
    var policy = cleanupDao.findPolicy(policyId).orElse(null);
    JobKey jobKey = jobKey(policyId);
    if (!enabled || schedule == null || !schedule.enabled() || policy == null
        || !"ACTIVE".equals(policy.state())) {
      scheduler.deleteJob(jobKey);
      return;
    }

    JobDetail job = JobBuilder.newJob(CleanupQuartzJob.class)
        .withIdentity(jobKey)
        .usingJobData(POLICY_ID, policyId)
        .usingJobData(POLICY_REVISION, policy.revision())
        .storeDurably()
        .requestRecovery(true)
        .build();
    JobDetail existingJob = scheduler.getJobDetail(jobKey);
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
