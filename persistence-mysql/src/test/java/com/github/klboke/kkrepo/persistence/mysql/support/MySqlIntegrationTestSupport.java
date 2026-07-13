package com.github.klboke.kkrepo.persistence.mysql.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.klboke.kkrepo.persistence.jdbc.api.DatabaseConnectionSettings;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStoreFactories;
import com.github.klboke.kkrepo.persistence.jdbc.api.PersistenceStores;
import com.github.klboke.kkrepo.persistence.jdbc.internal.support.JsonColumns;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.mysql.MySqlDatabaseDialect;
import java.sql.Statement;
import java.util.List;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

public abstract class MySqlIntegrationTestSupport {
  private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
      .withDatabaseName("kkrepo")
      .withUsername("kkrepo")
      .withPassword("kkrepo");

  private static JdbcTemplate jdbcTemplate;
  private static JsonColumns jsonColumns;
  private static DatabaseDialect dialect;
  private static TransactionTemplate transactionTemplate;
  private static PersistenceStores stores;

  @BeforeAll
  protected static void startMySql() {
    if (!MYSQL.isRunning()) {
      MYSQL.start();
      DriverManagerDataSource dataSource = new DriverManagerDataSource(
          MYSQL.getJdbcUrl() + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
              + "&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL",
          MYSQL.getUsername(),
          MYSQL.getPassword());
      jdbcTemplate = new JdbcTemplate(dataSource);
      dialect = new MySqlDatabaseDialect();
      jsonColumns = new JsonColumns(new ObjectMapper(), dialect);
      transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      Flyway.configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration")
          .failOnMissingLocations(true)
          .load()
          .migrate();
      stores = PersistenceStoreFactories.connect(new DatabaseConnectionSettings(
          MYSQL.getJdbcUrl() + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
              + "&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL",
          MYSQL.getUsername(),
          MYSQL.getPassword()));
    }
  }

  @BeforeEach
  protected void truncateDatabase() {
    List<String> tables = jdbcTemplate.queryForList("""
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = ?
          AND table_type = 'BASE TABLE'
          AND table_name <> 'flyway_schema_history'
        """, String.class, MYSQL.getDatabaseName());
    jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
          for (String table : tables) {
            statement.execute("TRUNCATE TABLE `" + table.replace("`", "``") + "`");
          }
        } finally {
          statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
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

  protected <T> T inTransaction(Supplier<T> action) {
    return transactionTemplate.execute(status -> action.get());
  }

  protected void inTransaction(Runnable action) {
    transactionTemplate.executeWithoutResult(status -> action.run());
  }

  protected long insertBlobStore(String name) {
    jdbcTemplate.update("""
        INSERT INTO blob_store (name, type, attributes_json)
        VALUES (?, 'S3', JSON_OBJECT())
        """, name);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM blob_store WHERE name = ?", Long.class, name);
  }

  protected long insertRepository(String name, String format) {
    long blobStoreId = insertBlobStore(name + "-store");
    jdbcTemplate.update("""
        INSERT INTO repository
          (name, format, type, recipe_name, blob_store_id, attributes_json)
        VALUES (?, ?, 'hosted', ?, ?, JSON_OBJECT())
        """, name, format, format + "-hosted", blobStoreId);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM repository WHERE name = ?", Long.class, name);
  }

}
