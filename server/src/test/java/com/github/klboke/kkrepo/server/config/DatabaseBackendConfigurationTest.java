package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseBackendConfigurationTest {
  @Test
  void acceptsMatchingConfiguredAndJdbcDatabaseTypes() throws Exception {
    assertDoesNotThrow(() -> DatabaseBackendConfiguration.validateDatabaseProduct(
        dataSource("PostgreSQL"), DatabaseType.POSTGRESQL));
    assertDoesNotThrow(() -> DatabaseBackendConfiguration.validateDatabaseProduct(
        dataSource("MySQL"), DatabaseType.MYSQL));
  }

  @Test
  void rejectsMismatchBeforeFlywayRuns() throws Exception {
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> DatabaseBackendConfiguration.validateDatabaseProduct(
            dataSource("PostgreSQL"), DatabaseType.MYSQL));
    assertTrue(failure.getMessage().contains("does not match"));
    assertTrue(failure.getMessage().contains("PostgreSQL"));
  }

  @Test
  void wrapsMetadataConnectionFailuresWithStartupContext() throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException("offline"));
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> DatabaseBackendConfiguration.validateDatabaseProduct(dataSource, DatabaseType.MYSQL));
    assertTrue(failure.getMessage().contains("Cannot validate JDBC database product"));
  }

  private static DataSource dataSource(String productName) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn(productName);
    return dataSource;
  }
}
