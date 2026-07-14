package com.github.klboke.kkrepo.persistence.postgresql.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcPersistenceStoreFactory;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.postgresql.PostgreSqlDatabaseDialect;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgreSqlIntegrationTestSupport {
  private static final String POSTGRESQL_IMAGE =
      System.getProperty("kkrepo.test.postgresql.image", "postgres:12");
  private static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer(POSTGRESQL_IMAGE)
          .withDatabaseName("kkrepo")
          .withUsername("kkrepo")
          .withPassword("kkrepo");

  private static JdbcTemplate jdbcTemplate;
  private static JsonColumns jsonColumns;
  private static DatabaseDialect dialect;
  private static TransactionTemplate transactionTemplate;
  private static PersistenceStores stores;
  private static Flyway flyway;

  @BeforeAll
  protected static void startPostgreSql() {
    if (!POSTGRESQL.isRunning()) {
      POSTGRESQL.start();
      DriverManagerDataSource dataSource = new DriverManagerDataSource(
          POSTGRESQL.getJdbcUrl(),
          POSTGRESQL.getUsername(),
          POSTGRESQL.getPassword());
      jdbcTemplate = new JdbcTemplate(dataSource);
      dialect = new PostgreSqlDatabaseDialect();
      jsonColumns = new JsonColumns(new ObjectMapper(), dialect);
      transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      flyway = Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/postgresql")
          .failOnMissingLocations(true)
          .load();
      flyway.migrate();
      stores = JdbcPersistenceStoreFactory.createStores(jdbcTemplate, dialect);
    }
  }

  @BeforeEach
  protected void truncateDatabase() {
    List<String> tables = jdbcTemplate.queryForList("""
        SELECT tablename
        FROM pg_catalog.pg_tables
        WHERE schemaname = current_schema()
          AND tablename <> 'flyway_schema_history'
        ORDER BY tablename
        """, String.class);
    jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
      try (Statement statement = connection.createStatement()) {
        for (String table : tables) {
          statement.addBatch("TRUNCATE TABLE \"" + table.replace("\"", "\"\"")
              + "\" RESTART IDENTITY CASCADE");
        }
        statement.executeBatch();
      }
      return null;
    });
  }

  protected JdbcTemplate jdbc() {
    return jdbcTemplate;
  }

  protected JsonColumns jsonColumns() {
    return jsonColumns;
  }

  protected DatabaseDialect dialect() {
    return dialect;
  }

  protected PersistenceStores stores() {
    return stores;
  }

  protected Flyway flyway() {
    return flyway;
  }

  protected Set<String> databaseTables() {
    return Set.copyOf(jdbcTemplate.queryForList("""
        SELECT LOWER(tablename)
        FROM pg_catalog.pg_tables
        WHERE schemaname = current_schema()
          AND tablename <> 'flyway_schema_history'
        """, String.class));
  }

  protected <T> T inTransaction(Supplier<T> action) {
    return transactionTemplate.execute(status -> action.get());
  }

  protected void inTransaction(Runnable action) {
    transactionTemplate.executeWithoutResult(status -> action.run());
  }
}
