package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.klboke.kkrepo.persistence.jdbc.api.SecurityScanDao.CompletionOrder;
import com.github.klboke.kkrepo.persistence.jdbc.internal.JdbcPersistenceStoreFactory;
import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import com.github.klboke.kkrepo.persistence.jdbc.api.InvalidScanCompletionCursorException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MySqlScanCursorTimezoneTest extends MySqlIntegrationTestSupport {
  @Test
  void timestampCursorUsesConnectionZoneRatherThanJvmZone() {
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      var source = (DriverManagerDataSource) jdbc().getDataSource();
      var shanghai = new DriverManagerDataSource(
          source.getUrl().replace("connectionTimeZone=LOCAL", "serverTimezone=Asia/Shanghai"),
          source.getUsername(), source.getPassword());
      var scans = JdbcPersistenceStoreFactory.createStores(new JdbcTemplate(shanghai), dialect()).securityScanning();
      var cursor = new CompletionOrder(false, 1, Instant.parse("9999-12-31T23:59:59Z"));
      assertThrows(InvalidScanCompletionCursorException.class,
          () -> scans.listTasks(null, null, null, 0, 10, cursor));
      assertThrows(InvalidScanCompletionCursorException.class,
          () -> scans.listRuns(null, null, 0, 10, cursor));
      var valid = new CompletionOrder(false, 1, Instant.parse("9999-12-30T00:00:00Z"));
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> scans.listTasks(null, null, null, 0, 10, valid));
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> scans.listRuns(null, null, 0, 10, valid));
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
