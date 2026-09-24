package com.github.klboke.kkrepo.server.config;

import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The signing endpoint must be the single endpoint actually used by the JDBC driver. */
record RdsIamConnectionSettings(String hostname, int port, String username) {
  static RdsIamConnectionSettings from(HikariDataSource pool, String configuredType) {
    DatabaseType type = DatabaseType.fromId(configuredType);
    String prefix = "jdbc:" + type.id() + "://";
    String url = pool.getJdbcUrl();
    if (url == null || !url.startsWith(prefix) || pool.getDataSource() != null
        || pool.getDataSourceClassName() != null || pool.getDataSourceJNDI() != null) {
      throw new IllegalArgumentException("IAM requires a single-host " + prefix + " JDBC URL");
    }
    URI endpoint;
    try {
      endpoint = new URI(url.substring(5));
    } catch (URISyntaxException e) {
      // Do not include the URL or the parsing exception: either can contain a password.
      throw new IllegalArgumentException("Invalid IAM database JDBC URL");
    }
    if (endpoint.getHost() == null || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
      throw new IllegalArgumentException("IAM requires a single database hostname without URL credentials");
    }
    if (pool.getUsername() == null || pool.getUsername().isBlank()) {
      throw new IllegalArgumentException("IAM requires spring.datasource.username");
    }
    Map<String, String> properties = new HashMap<>();
    pool.getDataSourceProperties().forEach((key, value) ->
        properties.put(key.toString(), value.toString()));
    String query = endpoint.getRawQuery();
    if (query != null) {
      for (String parameter : query.split("&")) {
        String[] pair = parameter.split("=", 2);
        String name = decode(pair[0]);
        String value = pair.length == 2 ? decode(pair[1]) : "";
        if (properties.putIfAbsent(name, value) != null) {
          throw new IllegalArgumentException("IAM JDBC properties must not contain duplicate options");
        }
      }
    }
    for (String key : properties.keySet()) {
      if (java.util.Set.of("user", "username", "password", "host", "port", "pghost", "pgport")
          .contains(key.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("IAM JDBC properties must not override credentials or the endpoint");
      }
    }
    String tlsMode = type == DatabaseType.MYSQL ? "VERIFY_IDENTITY" : "verify-full";
    String tlsProperty = type == DatabaseType.MYSQL ? "sslMode" : "sslmode";
    if (!tlsMode.equals(properties.get(tlsProperty))) {
      throw new IllegalArgumentException("IAM requires "
          + (type == DatabaseType.MYSQL ? "sslMode=" : "sslmode=") + tlsMode
          + " and a trusted RDS CA certificate");
    }
    if (Boolean.parseBoolean(properties.get("autoReconnect"))
        || Boolean.parseBoolean(properties.get("autoReconnectForPools"))) {
      throw new IllegalArgumentException("Disable JDBC autoReconnect for IAM; Hikari must create replacement connections");
    }
    int port = endpoint.getPort() == -1 ? (type == DatabaseType.MYSQL ? 3306 : 5432) : endpoint.getPort();
    return new RdsIamConnectionSettings(endpoint.getHost(), port, pool.getUsername());
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid encoding in IAM JDBC options");
    }
  }
}
