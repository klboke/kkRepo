package com.github.klboke.kkrepo.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

class RdsIamConnectionSettingsTest {
  @Test
  void extractsBackendDefaultPortsAndExplicitPorts() {
    assertThrows(IllegalArgumentException.class, () -> settings("unsupported", "jdbc:h2:mem:test"));
    assertEquals(new RdsIamConnectionSettings("db.example", 3306, "db_user"),
        settings("mysql", "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY"));
    assertEquals(new RdsIamConnectionSettings("db.example", 5432, "db_user"),
        settings("postgresql", "jdbc:postgresql://db.example/kkrepo?sslmode=verify-full"));
    assertEquals(new RdsIamConnectionSettings("db.example", 15432, "db_user"),
        settings("postgresql", "jdbc:postgresql://db.example:15432/kkrepo?sslmode=verify-full"));
  }

  @Test
  void rejectsUnsafeOrAmbiguousUrlsWithoutDisclosingTheirContents() {
    for (String url : new String[] {
        "jdbc:mysql://db.example/kkrepo?useSSL=false",
        "jdbc:mysql://db.example/kkrepo?sslMode=REQUIRED",
        "jdbc:mysql://db.example/kkrepo?sslmode=VERIFY_IDENTITY",
        "jdbc:mysql:loadbalance://db.example/kkrepo?sslMode=VERIFY_IDENTITY",
        "jdbc:mysql://db.example,db2.example/kkrepo?sslMode=VERIFY_IDENTITY",
        "jdbc:mysql://db.example:0/kkrepo?sslMode=VERIFY_IDENTITY",
        "jdbc:mysql://db.example:65536/kkrepo?sslMode=VERIFY_IDENTITY",
        "jdbc:mysql://user:secret@db.example/kkrepo?sslMode=VERIFY_IDENTITY",
        "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&password=secret",
        "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&%75ser=other",
        "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&sslMode=DISABLED",
        "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&autoReconnect=true",
        "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&autoReconnectForPools=true",
        "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY#secret",
        "jdbc:mysql://db.example/kkrepo?password=secret%",
        "jdbc:postgresql://db.example/kkrepo?sslmode=verify-full"}) {
      var failure = assertThrows(IllegalArgumentException.class, () -> settings("mysql", url));
      assertFalse(failure.toString().contains("secret"));
    }
    assertThrows(IllegalArgumentException.class,
        () -> settings("postgresql", "jdbc:postgresql://db.example/kkrepo?sslmode=require"));
    assertThrows(IllegalArgumentException.class,
        () -> settings("postgresql", "jdbc:postgresql://db.example/kkrepo?sslMode=verify-full"));
  }

  @Test
  void rejectsAllEnabledMysqlReconnectValuesInUrlsAndDriverProperties() {
    for (String property : new String[] {"autoReconnect", "autoReconnectForPools"}) {
      for (String value : new String[] {"true", "TRUE", "yes", "YeS"}) {
        assertThrows(IllegalArgumentException.class, () -> settings("mysql",
            "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&" + property + "=" + value));
        try (var pool = new HikariDataSource()) {
          pool.setJdbcUrl("jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY");
          pool.setUsername("db_user");
          pool.addDataSourceProperty(property, value);
          assertThrows(IllegalArgumentException.class, () -> RdsIamConnectionSettings.from(pool, "mysql"));
        }
      }
      for (String value : new String[] {"false", "FALSE", "no", "No"}) {
        assertEquals(3306, settings("mysql",
            "jdbc:mysql://db.example/kkrepo?sslMode=VERIFY_IDENTITY&" + property + "=" + value).port());
      }
    }
  }

  @Test
  void validatesDriverPropertiesAndRejectsAlternateDataSources() {
    try (var pool = new HikariDataSource()) {
      pool.setJdbcUrl("jdbc:mysql://db.example/kkrepo");
      pool.setUsername("db_user");
      pool.addDataSourceProperty("sslMode", "VERIFY_IDENTITY");
      assertEquals(3306, RdsIamConnectionSettings.from(pool, "mysql").port());
      pool.addDataSourceProperty("password", "secret");
      assertThrows(IllegalArgumentException.class, () -> RdsIamConnectionSettings.from(pool, "mysql"));
      pool.getDataSourceProperties().remove("password");
      pool.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
      assertThrows(IllegalArgumentException.class, () -> RdsIamConnectionSettings.from(pool, "mysql"));
      pool.setDataSourceClassName(null);
      pool.setUsername(" ");
      assertThrows(IllegalArgumentException.class, () -> RdsIamConnectionSettings.from(pool, "mysql"));
    }
  }

  private static RdsIamConnectionSettings settings(String type, String url) {
    try (var pool = new HikariDataSource()) {
      pool.setJdbcUrl(url);
      pool.setUsername("db_user");
      return RdsIamConnectionSettings.from(pool, type);
    }
  }
}
