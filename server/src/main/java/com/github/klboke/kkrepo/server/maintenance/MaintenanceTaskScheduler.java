package com.github.klboke.kkrepo.server.maintenance;

import com.github.klboke.kkrepo.plugin.api.MaintenanceTask;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Schedules every registered maintenance task on the configured cron expression. No task is
 * scheduled when {@code kkrepo.maintenance.cron} is blank.
 */
@Component
public class MaintenanceTaskScheduler implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(MaintenanceTaskScheduler.class);
  private static final String GROUP = "maintenance";

  private final Scheduler scheduler;
  private final MaintenanceTaskRegistry registry;
  private final String cron;

  public MaintenanceTaskScheduler(
      Scheduler scheduler,
      MaintenanceTaskRegistry registry,
      @Value("${kkrepo.maintenance.cron:}") String cron) {
    this.scheduler = scheduler;
    this.registry = registry;
    this.cron = cron;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (cron == null || cron.isBlank()) {
      log.info("Maintenance scheduler is disabled (kkrepo.maintenance.cron is blank)");
      return;
    }
    for (MaintenanceTask task : registry.all()) {
      schedule(task, cron);
    }
  }

  private void schedule(MaintenanceTask task, String expression) {
    JobKey jobKey = JobKey.jobKey("maintenance:" + task.id(), GROUP);
    TriggerKey triggerKey = TriggerKey.triggerKey("maintenance:" + task.id(), GROUP);
    try {
      if (scheduler.checkExists(jobKey)) {
        scheduler.deleteJob(jobKey);
      }
      scheduler.scheduleJob(
          JobBuilder.newJob(MaintenanceTaskJob.class)
              .withIdentity(jobKey)
              .usingJobData(MaintenanceTaskJob.TASK_ID, task.id())
              .build(),
          TriggerBuilder.newTrigger()
              .withIdentity(triggerKey)
              .withSchedule(CronScheduleBuilder.cronSchedule(expression))
              .build());
      log.info("Scheduled maintenance task {} with cron {}", task.id(), expression);
    } catch (SchedulerException | RuntimeException failure) {
      log.error("Unable to schedule maintenance task {} with cron {}", task.id(), expression, failure);
    }
  }
}
