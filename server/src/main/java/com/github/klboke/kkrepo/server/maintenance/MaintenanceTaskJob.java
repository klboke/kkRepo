package com.github.klboke.kkrepo.server.maintenance;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

/** Quartz entry point for one registered maintenance task. */
@DisallowConcurrentExecution
public class MaintenanceTaskJob extends QuartzJobBean {
  public static final String TASK_ID = "taskId";
  private static final Logger log = LoggerFactory.getLogger(MaintenanceTaskJob.class);

  @Autowired
  private MaintenanceTaskRunner runner;

  @Override
  protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
    String taskId = context.getMergedJobDataMap().getString(TASK_ID);
    if (taskId == null || taskId.isBlank()) {
      throw new JobExecutionException("Maintenance task id is missing", false);
    }
    try {
      runner.run(taskId);
    } catch (RuntimeException failure) {
      log.error("Maintenance task {} failed", taskId, failure);
      throw new JobExecutionException(failure, false);
    }
  }
}
