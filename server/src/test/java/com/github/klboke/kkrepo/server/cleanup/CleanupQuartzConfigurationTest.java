package com.github.klboke.kkrepo.server.cleanup;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.core.RepositoryFormat;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupPolicy;
import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao.CleanupSchedule;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.TriggerBuilder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

class CleanupQuartzConfigurationTest {
  @Test
  void quartzCreatesInjectedJobsWithoutRuntimeAnnotationProcessing() throws Exception {
    CleanupPolicyDao dao = mock(CleanupPolicyDao.class);
    CleanupRunService runs = mock(CleanupRunService.class);
    Runnable otherTask = mock(Runnable.class);
    Instant now = Instant.now();
    when(dao.findPolicy(7)).thenReturn(Optional.of(new CleanupPolicy(
        7L, "scheduled", RepositoryFormat.APT, null, Map.of("retainCount", 1),
        3, "ACTIVE", 100, 100, now, now)));
    when(dao.findSchedule(7)).thenReturn(Optional.of(new CleanupSchedule(
        7, "0 * * * * ?", "UTC", true, null, now, now)));

    // No AutowiredAnnotationBeanPostProcessor: Native replaces it with generated bean code.
    try (StaticApplicationContext context = new StaticApplicationContext()) {
      context.getBeanFactory().registerSingleton("cleanupDao", dao);
      context.getBeanFactory().registerSingleton("runs", runs);
      context.getBeanFactory().registerSingleton("otherTask", otherTask);
      context.getBeanFactory().registerSingleton("properties", new CleanupRuntimeProperties());
      context.refresh();
      SchedulerFactoryBean factory = new SchedulerFactoryBean();
      factory.setSchedulerName("cleanup-factory-test");
      Properties properties = new Properties();
      properties.setProperty("org.quartz.threadPool.threadCount", "1");
      factory.setQuartzProperties(properties);
      new CleanupQuartzConfiguration().cleanupQuartzJobFactoryCustomizer(
          context, context.getBeanProvider(CleanupPolicyDao.class),
          context.getBeanProvider(CleanupRunService.class),
          context.getBeanProvider(CleanupRuntimeProperties.class)).customize(factory);
      factory.afterPropertiesSet();
      try {
        var job = JobBuilder.newJob(CleanupQuartzJob.class).withIdentity("policy-7")
            .usingJobData(CleanupQuartzJob.POLICY_ID, 7L)
            .usingJobData(CleanupQuartzJob.POLICY_REVISION, 3L).build();
        assertTrue(job.isConcurrentExecutionDisallowed());
        var trigger = TriggerBuilder.newTrigger().withIdentity("fire").startNow().build();
        var scheduled = factory.getScheduler().scheduleJob(job, trigger);
        factory.start();
        verify(runs, timeout(5000)).startScheduled(eq(7L), eq(scheduled.toInstant()));
        // Other job types retain SpringBeanJobFactory's managed dependency creation.
        factory.getScheduler().scheduleJob(
            JobBuilder.newJob(OtherJob.class).withIdentity("other-job").build(),
            TriggerBuilder.newTrigger().withIdentity("other-fire").startNow().build());
        verify(otherTask, timeout(5000)).run();
      } finally {
        factory.destroy();
      }
    }
  }

  public static class OtherJob implements Job {
    private final Runnable task;

    public OtherJob(Runnable task) {
      this.task = task;
    }

    @Override
    public void execute(JobExecutionContext context) {
      task.run();
    }
  }
}
