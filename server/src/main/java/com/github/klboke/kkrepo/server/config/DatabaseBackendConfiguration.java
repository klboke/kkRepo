package com.github.klboke.kkrepo.server.config;

import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialects;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects and validates the configured relational database backend before migration. */
@Configuration(proxyBeanMethods = false)
public class DatabaseBackendConfiguration {
  @Bean
  DatabaseDialect databaseDialect(
      @Value("${kkrepo.database.type:mysql}") String configuredType) {
    return DatabaseDialects.load(DatabaseType.fromId(configuredType));
  }

  @Bean
  FlywayConfigurationCustomizer databaseFlywayCustomizer(DatabaseDialect dialect) {
    return configuration -> configuration.baselineOnMigrate(dialect.type() == DatabaseType.MYSQL);
  }

  @Bean
  FlywayMigrationStrategy validatingFlywayMigrationStrategy(
      DataSource dataSource,
      DatabaseDialect dialect) {
    return flyway -> {
      validateDatabaseProduct(dataSource, dialect.type());
      flyway.migrate();
    };
  }

  static void validateDatabaseProduct(DataSource dataSource, DatabaseType configuredType) {
    try (var connection = dataSource.getConnection()) {
      String productName = connection.getMetaData().getDatabaseProductName();
      DatabaseType actualType = DatabaseType.fromProductName(productName);
      if (actualType != configuredType) {
        throw new IllegalStateException(
            "Configured database type " + configuredType.id()
                + " does not match JDBC database product " + productName);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot validate JDBC database product", e);
    }
  }
}
