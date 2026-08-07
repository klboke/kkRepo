package com.github.klboke.kkrepo.server.cleanup;

import java.util.Locale;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.quartz.autoconfigure.QuartzProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Database-specific Quartz JDBC delegate selection for the supported persistence engines. */
@Configuration(proxyBeanMethods = false)
public class CleanupQuartzConfiguration {
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
