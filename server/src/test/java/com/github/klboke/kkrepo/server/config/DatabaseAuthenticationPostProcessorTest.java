package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DatabaseAuthenticationPostProcessorTest {
  @Test
  void bootBindsPoolSettingsBeforeInstallingIamForBothBackends() {
    for (String type : new String[] {"mysql", "postgresql"}) {
      try (var context = new AnnotationConfigApplicationContext()) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "kkrepo.database.auth", "iam", "kkrepo.database.type", type,
            "kkrepo.database.iam.region", "us-east-1",
            "spring.datasource.url", url(type), "spring.datasource.username", "iam_user",
            "spring.datasource.password", "ignored-password",
            "spring.datasource.hikari.maximum-pool-size", "7")));
        context.register(DataSourceAutoConfiguration.class, DatabaseBackendConfiguration.class);
        context.refresh();
        HikariDataSource pool = context.getBean(HikariDataSource.class);
        assertInstanceOf(RdsIamCredentialsProvider.class, pool.getCredentialsProvider());
        assertEquals(7, pool.getMaximumPoolSize());
        assertEquals("iam_user", pool.getUsername());
        assertNull(pool.getPassword());
        assertFalse(pool.isRunning());
        assertTrue(context.containsBean("validatingFlywayMigrationStrategy"));
      }
    }
  }

  @Test
  void defaultPasswordModePreservesBootCredentialsWithoutAwsConfiguration() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
          "spring.datasource.url", "jdbc:mysql://localhost/test",
          "spring.datasource.username", "static_user", "spring.datasource.password", "static-password")));
      context.register(DataSourceAutoConfiguration.class, DatabaseBackendConfiguration.class);
      context.refresh();
      var pool = context.getBean(HikariDataSource.class);
      assertEquals("static-password", pool.getPassword());
      assertNull(pool.getCredentialsProvider());
    }
  }

  @Test
  void rejectsUnknownAuthenticationModesAndNonHikariPools() {
    var environment = new MockEnvironment().withProperty("kkrepo.database.auth", "typo");
    var processor = new DatabaseAuthenticationPostProcessor(environment);
    assertSame(this, processor.postProcessBeforeInitialization(this, "unrelated"));
    assertThrows(IllegalArgumentException.class,
        () -> processor.postProcessBeforeInitialization(new DriverManagerDataSource(), "dataSource"));
    environment.setProperty("kkrepo.database.auth", "iam");
    assertThrows(IllegalArgumentException.class,
        () -> processor.postProcessBeforeInitialization(new DriverManagerDataSource(), "dataSource"));
    processor.destroy();
  }

  @Test
  void rejectsSeparateFlywayCredentialsBeforeTheyCanBypassIam() {
    for (String name : new String[] {"url", "user", "password"}) {
      var environment = new MockEnvironment().withProperty("kkrepo.database.auth", "iam")
          .withProperty("spring.flyway." + name, "separate-value");
      var processor = new DatabaseAuthenticationPostProcessor(environment);
      try (var pool = new HikariDataSource()) {
        assertThrows(IllegalArgumentException.class,
            () -> processor.postProcessBeforeInitialization(pool, "dataSource"));
      }
    }
  }

  private static String url(String type) {
    return "jdbc:" + type + "://db.example/kkrepo?"
        + (type.equals("mysql") ? "sslMode=VERIFY_IDENTITY" : "sslmode=verify-full");
  }
}
