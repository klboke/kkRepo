package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.test.util.ReflectionTestUtils;

class CleanupQuartzJobTest {
  private static final Instant FIRE_TIME = Instant.parse("2026-08-02T02:00:00Z");

  @Test
  void executesCurrentEnabledPolicyWithTheScheduledFireIdentity() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy(3, "ACTIVE")));
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(schedule(true)));
    CleanupQuartzJob job = job(cleanupDao, runs);

    job.executeInternal(context(7, 3));

    verify(runs).startScheduled(7, FIRE_TIME);
  }

  @Test
  void ignoresStaleRevisionEvenIfAnOldTriggerWasAlreadyAcquired() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy(4, "ACTIVE")));
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(schedule(true)));
    CleanupQuartzJob job = job(cleanupDao, runs);

    job.executeInternal(context(7, 3));

    verify(runs, never()).startScheduled(7, FIRE_TIME);
  }

  @Test
  void ignoresAlreadyAcquiredFireWhenCleanupExecutionIsDisabled() throws Exception {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    CleanupRuntimeProperties properties = new CleanupRuntimeProperties();
    properties.setEnabled(false);
    CleanupQuartzJob job = job(cleanupDao, runs, properties);

    job.executeInternal(context(7, 3));

    verify(runs, never()).startScheduled(7, FIRE_TIME);
  }

  @Test
  void transientRunCreationFailureRequestsABoundedImmediateRefire() {
    CleanupPolicyDao cleanupDao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    when(cleanupDao.findPolicy(7)).thenReturn(Optional.of(policy(3, "ACTIVE")));
    when(cleanupDao.findSchedule(7)).thenReturn(Optional.of(schedule(true)));
    when(runs.startScheduled(7, FIRE_TIME)).thenThrow(new IllegalStateException("database"));
    CleanupQuartzJob job = job(cleanupDao, runs);

    JobExecutionException failure = assertThrows(
        JobExecutionException.class, () -> job.executeInternal(context(7, 3)));

    assertTrue(failure.refireImmediately());
  }

  private static CleanupQuartzJob job(CleanupPolicyDao cleanupDao, CleanupRunService runs) {
    return job(cleanupDao, runs, new CleanupRuntimeProperties());
  }

  private static CleanupQuartzJob job(
      CleanupPolicyDao cleanupDao,
      CleanupRunService runs,
      CleanupRuntimeProperties runtimeProperties) {
    CleanupQuartzJob job = new CleanupQuartzJob();
    ReflectionTestUtils.setField(job, "cleanupDao", cleanupDao);
    ReflectionTestUtils.setField(job, "runs", runs);
    ReflectionTestUtils.setField(job, "runtimeProperties", runtimeProperties);
    return job;
  }

  private static JobExecutionContext context(long policyId, long revision) {
    JobExecutionContext context = mock(JobExecutionContext.class);
    JobDataMap data = new JobDataMap();
    data.put(CleanupQuartzJob.POLICY_ID, policyId);
    data.put(CleanupQuartzJob.POLICY_REVISION, revision);
    when(context.getMergedJobDataMap()).thenReturn(data);
    when(context.getScheduledFireTime()).thenReturn(Date.from(FIRE_TIME));
    return context;
  }

  private static CleanupPolicy policy(long revision, String state) {
    return new CleanupPolicy(
        7L,
        "cleanup",
        RepositoryFormat.RAW,
        null,
        Map.of("publishedOlderThanDays", 30),
        revision,
        state,
        100,
        10,
        FIRE_TIME,
        FIRE_TIME);
  }

  private static CleanupSchedule schedule(boolean enabled) {
    return new CleanupSchedule(
        7L, "0 0 2 * * ?", "UTC", enabled, null, FIRE_TIME, FIRE_TIME);
  }
}
