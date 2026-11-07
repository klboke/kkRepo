package com.github.klboke.kkrepo.persistence.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.klboke.kkrepo.persistence.mysql.support.MySqlIntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MySqlScanningPrivilegeMigrationTest extends MySqlIntegrationTestSupport {
  @Test
  void freshInstallSeedsFourImmutablePrivilegesForAdministratorsOnly() {
    Flyway current = migration(null);
    current.clean();
    current.migrate();
    assertEquals(4, jdbc().queryForObject("""
        SELECT COUNT(*) FROM security_privilege
        WHERE privilege_id LIKE 'nx-security-scanning-%' AND read_only = TRUE
        """, Integer.class));
    assertEquals(4, jdbc().queryForObject("""
        SELECT COUNT(*) FROM security_role_privilege
        WHERE role_id = 'nx-admin' AND privilege_id LIKE 'nx-security-scanning-%'
        """, Integer.class));
    assertEquals(0, jdbc().queryForObject("""
        SELECT COUNT(*) FROM security_role_privilege
        WHERE role_id <> 'nx-admin' AND privilege_id LIKE 'nx-security-scanning-%'
        """, Integer.class));
    assertTrue(current.validateWithResult().validationSuccessful);
    assertEquals(0, current.migrate().migrationsExecuted);
  }

  @Test
  void upgradePreservesManualPrivilegesByIdOrNameAndExistingRoleBindings() {
    Flyway old = migration("59");
    old.clean();
    old.migrate();
    jdbc().execute("""
        INSERT INTO security_privilege
          (privilege_id, name, description, type, read_only, properties_json)
        VALUES
          ('custom-scan-reader', 'nx-security-scanning-read', 'manual reader', 'application', FALSE,
            '{"domain":"security-scanning","actions":"read"}'),
          ('nx-security-scanning-update', 'custom-update-name', 'keep custom actions', 'application', FALSE,
            '{"domain":"security-scanning","actions":"read"}'),
          ('nx-security-scanning-waivers-create', 'nx-security-scanning-waivers-create', 'manual waiver', 'application', FALSE,
            '{"domain":"security-scanning-waivers","actions":"create"}')
        """);
    jdbc().execute("""
        INSERT INTO security_role (role_id, source, name, description, read_only, attributes_json)
        VALUES ('scan-reader', 'default', 'Scan reader', 'manual role', FALSE, '{}')
        """);
    jdbc().execute("""
        INSERT INTO security_role_privilege (role_id, privilege_id)
        VALUES ('scan-reader', 'custom-scan-reader'), ('scan-reader', 'nx-security-scanning-update')
        """);
    var definitions = jdbc().queryForList("""
        SELECT privilege_id, name, description, type, read_only, properties_json
        FROM security_privilege ORDER BY privilege_id
        """);
    var bindings = jdbc().queryForList("""
        SELECT role_id, privilege_id FROM security_role_privilege
        WHERE role_id <> 'nx-admin' ORDER BY role_id, privilege_id
        """);
    Flyway current = migration(null);
    assertEquals(4, current.migrate().migrationsExecuted);
    assertEquals(definitions, jdbc().queryForList("""
        SELECT privilege_id, name, description, type, read_only, properties_json
        FROM security_privilege WHERE privilege_id <> 'nx-security-scanning-waivers-delete'
        ORDER BY privilege_id
        """));
    assertEquals(bindings, jdbc().queryForList("""
        SELECT role_id, privilege_id FROM security_role_privilege
        WHERE role_id <> 'nx-admin' ORDER BY role_id, privilege_id
        """));
    assertEquals(1, jdbc().queryForObject("""
        SELECT COUNT(*) FROM security_privilege
        WHERE privilege_id = 'nx-security-scanning-waivers-delete' AND read_only = TRUE
        """, Integer.class));
    assertTrue(current.validateWithResult().validationSuccessful);
    assertEquals(0, current.migrate().migrationsExecuted);
    // Replaying after a partial MySQL data migration must also be harmless.
    jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource(
              "db/migration/mysql/V60__security_scanning_privileges.sql"));
      return null;
    });
    assertEquals(bindings, jdbc().queryForList("""
        SELECT role_id, privilege_id FROM security_role_privilege
        WHERE role_id <> 'nx-admin' ORDER BY role_id, privilege_id
        """));
  }

  private Flyway migration(String target) {
    var configuration = Flyway.configure()
        .dataSource(flyway().getConfiguration().getDataSource())
        .locations("classpath:db/migration/mysql")
        .mixed(true).cleanDisabled(false).failOnMissingLocations(true);
    if (target != null) configuration.target(target);
    
    return configuration.load();
  }
}
