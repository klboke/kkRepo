package com.github.klboke.kkrepo.migration.nexus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Runs the legacy user_token probe of the source security script against in-memory H2 fixtures. */
public class LegacyUserTokenProbeScriptTest {
  private static final String SECRET = "AliceName1:secret-pass-code";

  @Test
  void absentTableExportsNothing() throws Exception {
    String url = database("absent", "create table api_key_v2(domain varchar(64))");
    List<String> warnings = new ArrayList<>();

    assertNull(probe(url, "sa", "", warnings));
    assertEquals(List.of(), warnings);
  }

  @Test
  void readableTableExportsCountAndOwnersOnly() throws Exception {
    String url = database("readable",
        "create table user_token(user_name varchar(64), token varchar(128))",
        "insert into user_token values ('alice', '" + SECRET + "')");
    List<String> warnings = new ArrayList<>();

    Object result = probe(url, "sa", "", warnings);

    assertEquals(Map.of("count", 1, "users", List.of("alice")), result);
    assertFalse(String.valueOf(result).contains(SECRET));
    assertEquals(List.of(), warnings);
  }

  @Test
  void tableWhoseSelectIsDeniedIsExportedAsUnreadable() throws Exception {
    String url = database("denied",
        "create table user_token(user_name varchar(64), token varchar(128))",
        "insert into user_token values ('alice', '" + SECRET + "')",
        "create user reader password 'reader'",
        // A revoked grant: the table stays visible in the JDBC metadata, its SELECT is denied.
        "grant select on user_token to reader",
        "revoke select on user_token from reader");
    List<String> warnings = new ArrayList<>();

    Object result = probe(url, "reader", "reader", warnings);

    assertEquals(Map.of("status", "unreadable", "table", "USER_TOKEN"), result);
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("USER_TOKEN could not be read"), warnings.get(0));
    assertFalse(warnings.get(0).contains(SECRET));
  }

  private static Object probe(String url, String user, String password, List<String> warnings) throws Exception {
    var field = NexusRestClient.class.getDeclaredField("LOCAL_SECURITY_EXPORT_SCRIPT");
    field.setAccessible(true);
    String script = (String) field.get(null);
    String fragment = script.substring(
        script.indexOf("// Pre-3.71 Nexus Pro user tokens"), script.indexOf("def exportResult"));
    Binding binding = new Binding();
    binding.setVariable("container", new Container(url, user, password));
    binding.setVariable("warnings", warnings);
    return new GroovyShell(binding).evaluate(
        "def errorText = { e -> e == null ? '' : e.class.name + ': ' + String.valueOf(e.message) }\n"
            + fragment + "\nreturn legacyUserTokens\n");
  }

  private static String database(String name, String... statements) throws SQLException {
    String url = "jdbc:h2:mem:" + name;
    // Keep the database open between connections; only an admin may set that, so the probe URL omits it.
    try (Connection connection = DriverManager.getConnection(url + ";DB_CLOSE_DELAY=-1", "sa", "");
        Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    }
    return url;
  }

  // Stand-ins for the Nexus DataStoreManager lookup the script performs.
  public record Container(String url, String user, String password) {
    public Object lookup(String role) {
      return new Stores(this);
    }
  }

  public record Stores(Container container) {
    public Optional<DataStore> get(String name) {
      return "nexus".equals(name) ? Optional.of(new DataStore(container)) : Optional.empty();
    }
  }

  public record DataStore(Container container) {
    public Connection openConnection() throws SQLException {
      return DriverManager.getConnection(container.url(), container.user(), container.password());
    }
  }
}
