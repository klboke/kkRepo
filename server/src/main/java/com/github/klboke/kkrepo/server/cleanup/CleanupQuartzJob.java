package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import java.time.Instant;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/** Executes one policy fire selected by the clustered Quartz JDBC JobStore. */
@DisallowConcurrentExecution
public class CleanupQuartzJob extends QuartzJobBean {
  static final String POLICY_ID = "policyId";
  static final String POLICY_REVISION = "policyRevision";
  private static final int MAX_IMMEDIATE_REFIRE_COUNT = 3;

  private static final Logger log = LoggerFactory.getLogger(CleanupQuartzJob.class);

  @Autowired
  private CleanupPolicyDao cleanupDao;

  @Autowired
  private CleanupRunService runs;

  @Autowired
  private CleanupRuntimeProperties runtimeProperties;

  @Override
  protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
    long policyId = context.getMergedJobDataMap().getLong(POLICY_ID);
    long expectedRevision = context.getMergedJobDataMap().getLong(POLICY_REVISION);
    if (!runtimeProperties.isEnabled()) {
      log.info("Ignoring cleanup Quartz fire while cleanup execution is disabled: policy={}", policyId);
      return;
    }
    Instant scheduledFor = context.getScheduledFireTime().toInstant();
    try {
      boolean current = cleanupDao.findPolicy(policyId)
          .filter(policy -> policy.revision() == expectedRevision && "ACTIVE".equals(policy.state()))
          .isPresent()
          && cleanupDao.findSchedule(policyId).filter(schedule -> schedule.enabled()).isPresent();
      if (!current) {
        log.info(
            "Ignoring stale cleanup Quartz fire: policy={} revision={}",
            policyId,
            expectedRevision);
        return;
      }
      runs.startScheduled(policyId, scheduledFor);
    } catch (CleanupValidationException | CleanupNotFoundException invalid) {
      log.error(
          "Scheduled cleanup fire was rejected: policy={} scheduledFor={}",
          policyId,
          scheduledFor,
          invalid);
    } catch (RuntimeException e) {
      log.error(
          "Scheduled cleanup execution failed: policy={} scheduledFor={}",
          policyId,
          scheduledFor,
          e);
      throw new JobExecutionException(
          e, context.getRefireCount() < MAX_IMMEDIATE_REFIRE_COUNT);
    }
  }
}
