package com.github.klboke.kkrepo.server.cleanup;

import com.github.klboke.kkrepo.persistence.jdbc.api.CleanupPolicyDao;
import java.util.Locale;
import java.util.Properties;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/** Job creation and database-specific delegates for the clustered cleanup scheduler. */
@Configuration(proxyBeanMethods = false)
public class CleanupQuartzConfiguration {
  @Bean
  SchedulerFactoryBeanCustomizer cleanupQuartzJobFactoryCustomizer(
      ApplicationContext context,
      ObjectProvider<CleanupPolicyDao> cleanupDao,
      ObjectProvider<CleanupRunService> runs,
      ObjectProvider<CleanupRuntimeProperties> runtimeProperties) {
    // Quartz creates a fresh job for every fire, outside Spring's AOT bean registrations.
    // Resolve managed dependencies lazily to preserve their transaction proxies and avoid
    // initializing the cleanup service graph while the scheduler itself is being created.
    SpringBeanJobFactory jobs = new SpringBeanJobFactory() {
      @Override
      protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        if (bundle.getJobDetail().getJobClass() == CleanupQuartzJob.class) {
          return new CleanupQuartzJob(
              cleanupDao.getObject(), runs.getObject(), runtimeProperties.getObject());
        }
        return super.createJobInstance(bundle);
      }
    };
    jobs.setApplicationContext(context);
    return factory -> factory.setJobFactory(jobs);
  }

  @Bean
  SchedulerFactoryBeanCustomizer cleanupQuartzJdbcDelegateCustomizer(
      QuartzProperties quartzProperties,
      @Value("${kkrepo.database.type:mysql}") String databaseType) {
    Properties properties = new Properties();
    properties.putAll(quartzProperties.getProperties());
    String delegate = databaseType.trim().toLowerCase(Locale.ROOT).equals("postgresql")
        ? "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate"
        : "org.quartz.impl.jdbcjobstore.StdJDBCDelegate";
    properties.setProperty("org.quartz.jobStore.driverDelegateClass", delegate);
    return factory -> factory.setQuartzProperties(properties);
  }
}
