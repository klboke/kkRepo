package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import com.github.klboke.kkrepo.persistence.jdbc.api.MaintenanceCursorDao;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class CleanupQuartzScheduleManagerTest {
  private static final Instant NOW = Instant.parse("2026-08-02T03:00:00Z");

  @Test
  void materializesEnabledPolicyAsDurableCronJob() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    CleanupSchedule schedule = new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", true, null, NOW, NOW);
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(schedule));
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy("ACTIVE")));
    when(scheduler.getJobDetail(CleanupQuartzScheduleManager.jobKey(7))).thenReturn(null);
    when(scheduler.getTrigger(CleanupQuartzScheduleManager.triggerKey(7))).thenReturn(null);
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true);

    manager.reconcile(7);

    ArgumentCaptor<JobDetail> job = ArgumentCaptor.forClass(JobDetail.class);
    verify(scheduler).addJob(job.capture(), eq(false));
    assertEquals(7, job.getValue().getJobDataMap().getLong(CleanupQuartzJob.POLICY_ID));
    assertEquals(3, job.getValue().getJobDataMap().getLong(CleanupQuartzJob.POLICY_REVISION));
    assertEquals(true, job.getValue().requestsRecovery());
    ArgumentCaptor<CronTrigger> trigger = ArgumentCaptor.forClass(CronTrigger.class);
    verify(scheduler).scheduleJob(trigger.capture());
    assertEquals("0 0 2 * * ?", trigger.getValue().getCronExpression());
    assertEquals("UTC", trigger.getValue().getTimeZone().getID());
  }

  @Test
  void removesQuartzJobWhenPolicyScheduleIsDisabled() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", false, null, NOW, NOW)));
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy("PAUSED")));
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true);

    manager.reconcile(7);

    verify(scheduler).deleteJob(CleanupQuartzScheduleManager.jobKey(7));
  }

  @Test
  void defersAfterCommitProjectionUntilTransactionResourcesAreUnbound() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", false, null, NOW, NOW)));
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy("PAUSED")));
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true);

    TransactionSynchronizationManager.initSynchronization();
    try {
      manager.reconcileAfterCommit(7);
      verifyNoInteractions(scheduler);
      for (TransactionSynchronization synchronization
          : TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verifyNoInteractions(scheduler);
    manager.reconcilePendingSafely();
    verify(scheduler).deleteJob(CleanupQuartzScheduleManager.jobKey(7));
  }

  @Test
  void reconcilesImmediatelyWhenNoTransactionIsActive() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.empty());
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true);

    manager.reconcileAfterCommit(7);

    verify(scheduler).deleteJob(CleanupQuartzScheduleManager.jobKey(7));
  }

  @Test
  void fullReconciliationRemovesOnlyMissingCleanupPolicyJobs() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    CleanupSchedule persisted = new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", false, null, NOW, NOW);
    JobKey orphan = CleanupQuartzScheduleManager.jobKey(9);
    JobKey unrelated = JobKey.jobKey("maintenance", CleanupQuartzScheduleManager.GROUP);
    JobKey malformed = JobKey.jobKey("policy-invalid", CleanupQuartzScheduleManager.GROUP);
    when(cleanupDao.listSchedules()).thenReturn(List.of(persisted));
    when(cleanupDao.findPolicies(List.of(7L))).thenReturn(Map.of(7L, policy("PAUSED")));
    when(scheduler.getJobKeys(any())).thenReturn(Set.of(
        CleanupQuartzScheduleManager.jobKey(7), orphan, unrelated, malformed));
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true);

    manager.reconcileAll();

    verify(scheduler).deleteJob(CleanupQuartzScheduleManager.jobKey(7));
    verify(scheduler).deleteJob(orphan);
    verify(cleanupDao).findPolicies(List.of(7L));
    verify(scheduler, never()).deleteJob(unrelated);
    verify(scheduler, never()).deleteJob(malformed);
  }

  @Test
  void unchangedJobAndTriggerAreNotRewritten() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    CleanupSchedule schedule = new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", true, null, NOW, NOW);
    JobDetail existingJob = JobBuilder.newJob(CleanupQuartzJob.class)
        .withIdentity(CleanupQuartzScheduleManager.jobKey(7))
        .usingJobData(CleanupQuartzJob.POLICY_REVISION, 3L)
        .storeDurably()
        .build();
    CronTrigger existingTrigger = mock(CronTrigger.class);
    when(existingTrigger.getCronExpression()).thenReturn(schedule.cronExpression());
    when(existingTrigger.getTimeZone()).thenReturn(TimeZone.getTimeZone("UTC"));
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(schedule));
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy("ACTIVE")));
    when(scheduler.getJobDetail(CleanupQuartzScheduleManager.jobKey(7)))
        .thenReturn(existingJob);
    when(scheduler.getTrigger(CleanupQuartzScheduleManager.triggerKey(7)))
        .thenReturn(existingTrigger);

    new CleanupQuartzScheduleManager(cleanupDao, scheduler, true).reconcile(7);

    verify(scheduler, never()).addJob(any(JobDetail.class), anyBoolean());
    verify(scheduler, never()).scheduleJob(any(Trigger.class));
    verify(scheduler, never()).rescheduleJob(any(), any());
  }

  @Test
  void changedJobRevisionAndCronReplaceExistingQuartzState() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    CleanupSchedule schedule = new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", true, null, NOW, NOW);
    JobDetail existingJob = JobBuilder.newJob(CleanupQuartzJob.class)
        .withIdentity(CleanupQuartzScheduleManager.jobKey(7))
        .usingJobData(CleanupQuartzJob.POLICY_REVISION, 2L)
        .storeDurably()
        .build();
    CronTrigger existingTrigger = mock(CronTrigger.class);
    when(existingTrigger.getCronExpression()).thenReturn("0 0 1 * * ?");
    when(existingTrigger.getTimeZone()).thenReturn(TimeZone.getTimeZone("UTC"));
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(schedule));
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy("ACTIVE")));
    when(scheduler.getJobDetail(CleanupQuartzScheduleManager.jobKey(7)))
        .thenReturn(existingJob);
    when(scheduler.getTrigger(CleanupQuartzScheduleManager.triggerKey(7)))
        .thenReturn(existingTrigger);

    new CleanupQuartzScheduleManager(cleanupDao, scheduler, true).reconcile(7);

    verify(scheduler).addJob(any(JobDetail.class), eq(true));
    verify(scheduler).rescheduleJob(
        eq(CleanupQuartzScheduleManager.triggerKey(7)), any(CronTrigger.class));
  }

  @Test
  void fullReconciliationSkipsWhenClusterCursorIsBusy() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    when(cursors.tryLockLastSeenId("cleanup_quartz_reconcile"))
        .thenReturn(OptionalLong.empty());
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true, cursors, transactions(), 300_000);

    manager.reconcileAllSafely();

    verify(cursors).ensureCursor("cleanup_quartz_reconcile");
    verify(cleanupDao, never()).listSchedules();
  }

  @Test
  void fullReconciliationUpdatesCursorBeforeReadingSchedules() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    MaintenanceCursorDao cursors = mock(MaintenanceCursorDao.class);
    when(cursors.tryLockLastSeenId("cleanup_quartz_reconcile"))
        .thenReturn(OptionalLong.of(0));
    when(cursors.updateLastSeenId("cleanup_quartz_reconcile", NOW.toEpochMilli()))
        .thenReturn(1);
    when(cleanupDao.currentTime()).thenReturn(NOW);
    when(cleanupDao.listSchedules()).thenReturn(List.of());
    when(scheduler.getJobKeys(any())).thenReturn(Set.of());
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true, cursors, transactions(), 300_000);

    manager.run(null);

    verify(cursors).updateLastSeenId("cleanup_quartz_reconcile", NOW.toEpochMilli());
    verify(cleanupDao).listSchedules();
  }

  @Test
  void springConstructorHonorsTheGlobalCleanupSwitch() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.setEnabled(false);
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(new CleanupSchedule(
        7, "0 0 2 * * ?", "UTC", true, null, NOW, NOW)));
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy("ACTIVE")));
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao,
        scheduler,
        properties,
        mock(MaintenanceCursorDao.class),
        mock(PlatformTransactionManager.class),
        300_000,
        true);

    manager.reconcile(7);

    verify(scheduler).deleteJob(CleanupQuartzScheduleManager.jobKey(7));
  }

  @Test
  void reconciliationFailuresDoNotEscapeScheduledEntryPoints() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    Scheduler scheduler = mock(Scheduler.class);
    when(scheduler.getJobKeys(any(GroupMatcher.class)))
        .thenThrow(new IllegalStateException("quartz unavailable"));
    CleanupQuartzScheduleManager manager = new CleanupQuartzScheduleManager(
        cleanupDao, scheduler, true);

    assertDoesNotThrow(manager::reconcileAllSafely);

    when(cleanupDao.findSchedule(7)).thenThrow(new IllegalStateException("database unavailable"));
    assertDoesNotThrow(() -> manager.reconcileAfterCommit(7));
  }

  private static CleanupPolicy policy(String state) {
    return new CleanupPolicy(
        7L,
        "cleanup",
        RepositoryFormat.MAVEN2,
        null,
        Map.of("publishedOlderThanDays", 30),
        3,
        state,
        100,
        10,
        NOW,
        NOW);
  }

  private static TransactionTemplate transactions() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    return transactions;
  }
}
