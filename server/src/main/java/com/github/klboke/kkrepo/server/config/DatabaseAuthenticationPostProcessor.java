package com.github.klboke.kkrepo.server.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;

/** Installs dynamic credentials after Boot binds Hikari settings, before any pool consumer runs. */
final class DatabaseAuthenticationPostProcessor implements BeanPostProcessor, Ordered, DisposableBean {
  private final Environment environment;
  private DefaultCredentialsProvider awsCredentials;

  DatabaseAuthenticationPostProcessor(Environment environment) {
    this.environment = environment;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (!(bean instanceof DataSource)) {
      return bean;
    }
    String auth = environment.getProperty("kkrepo.database.auth", "password")
        .trim().toLowerCase(Locale.ROOT);
    if (auth.equals("password")) {
      return bean;
    }
    if (!auth.equals("iam")) {
      throw new IllegalArgumentException("kkrepo.database.auth must be password or iam");
    }
    if (!(bean instanceof HikariDataSource pool)) {
      throw new IllegalArgumentException("IAM database authentication requires a Hikari DataSource");
    }
    // Flyway must use the application's authenticated pool instead of creating a static-password one.
    for (String property : new String[] {"spring.flyway.url", "spring.flyway.user", "spring.flyway.password"}) {
      if (environment.containsProperty(property)) {
        throw new IllegalArgumentException("Remove " + property + " when using IAM database authentication");
      }
    }
    RdsIamConnectionSettings settings = RdsIamConnectionSettings.from(pool,
        environment.getProperty("kkrepo.database.type", "mysql"));
    String configuredRegion = environment.getProperty("kkrepo.database.iam.region", "").trim();
    Region region = configuredRegion.isEmpty()
        ? DefaultAwsRegionProviderChain.builder().build().getRegion()
        : Region.of(configuredRegion);
    if (awsCredentials == null) {
      // An owned chain, not the SDK's shared singleton. It refreshes temporary workload credentials.
      awsCredentials = DefaultCredentialsProvider.builder().build();
    }
    var utilities = RdsUtilities.builder().region(region).credentialsProvider(awsCredentials).build();
    pool.setCredentialsProvider(new RdsIamCredentialsProvider(utilities, settings));
    pool.setPassword(null);
    return bean;
  }

  @Override
  public int getOrder() {
    // ConfigurationPropertiesBindingPostProcessor is PriorityOrdered and binds the final JDBC URL first.
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void destroy() {
    if (awsCredentials != null) {
      awsCredentials.close();
    }
  }
}
