package com.github.klboke.kkrepo.persistence.mysql;

import com.github.klboke.kkrepo.persistence.jdbc.spi.ComponentPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import com.github.klboke.kkrepo.persistence.jdbc.spi.JsonPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SearchPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SecurityPersistenceDialect;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/** MySQL 8 implementation of the shared persistence dialect contracts. */
@Component
public final class MySqlDatabaseDialect implements DatabaseDialect {
  private final MySqlJsonPersistenceDialect json = new MySqlJsonPersistenceDialect();
  private final ComponentPersistenceDialect components = new MySqlComponentPersistenceDialect(json);
  private final CoordinationPersistenceDialect coordination = new MySqlCoordinationPersistenceDialect();
  private final SearchPersistenceDialect search = new MySqlSearchPersistenceDialect();
  private final SecurityPersistenceDialect security = new MySqlSecurityPersistenceDialect(json);

  @Override
  public DatabaseType type() {
    return DatabaseType.MYSQL;
  }

  @Override
  public ComponentPersistenceDialect components() {
    return components;
  }

  @Override
  public CoordinationPersistenceDialect coordination() {
    return coordination;
  }

  @Override
  public JsonPersistenceDialect json() {
    return json;
  }

  @Override
  public SearchPersistenceDialect search() {
    return search;
  }

  @Override
  public SecurityPersistenceDialect security() {
    return security;
  }

  private static final class MySqlComponentPersistenceDialect
      implements ComponentPersistenceDialect {
    private static final String UPSERT_RETURNING_ID_SQL = """
        INSERT INTO component
          (repository_id, format, namespace, name, version, kind, coordinate_hash,
           attributes_json, last_updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          id = LAST_INSERT_ID(id),
          last_updated_at = VALUES(last_updated_at)
        """;
    private static final String UPSERT_SEARCH_DOCUMENT_SQL = """
        INSERT INTO component_search
          (component_id, repository_id, format, namespace, name, version, keywords, refreshed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))
        ON DUPLICATE KEY UPDATE
          repository_id = VALUES(repository_id),
          format = VALUES(format),
          namespace = VALUES(namespace),
          name = VALUES(name),
          version = VALUES(version),
          keywords = VALUES(keywords),
          refreshed_at = NOW(3)
        """;

    private final JsonPersistenceDialect json;

    private MySqlComponentPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public long upsertAndReturnId(JdbcOperations jdbc, ComponentUpsert command) {
      Long key = jdbc.execute((ConnectionCallback<Long>) connection -> {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_RETURNING_ID_SQL)) {
          statement.setLong(1, command.repositoryId());
          statement.setString(2, command.format());
          statement.setString(3, command.namespace());
          statement.setString(4, command.name());
          statement.setString(5, command.version());
          statement.setString(6, command.kind());
          statement.setBytes(7, command.coordinateHash());
          json.bind(statement, 8, command.attributesJson());
          statement.setTimestamp(9, command.lastUpdatedAt());
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT LAST_INSERT_ID()");
            var resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong(1) : null;
        }
      });
      if (key == null || key <= 0) {
        throw new IllegalStateException("Component upsert did not return an id");
      }
      return key;
    }

    @Override
    public void upsertSearchDocument(JdbcOperations jdbc, ComponentSearchDocument document) {
      jdbc.update(
          UPSERT_SEARCH_DOCUMENT_SQL,
          document.componentId(),
          document.repositoryId(),
          document.format(),
          document.namespace(),
          document.name(),
          document.version(),
          document.keywords());
    }
  }

  private static final class MySqlCoordinationPersistenceDialect
      implements CoordinationPersistenceDialect {
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z0-9_.]+");

    @Override
    public long bumpCacheVersion(JdbcOperations jdbc, String name) {
      Long version = jdbc.execute((ConnectionCallback<Long>) connection -> {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO cache_version (name, version, updated_at)
            VALUES (?, LAST_INSERT_ID(1), NOW(3))
            ON DUPLICATE KEY UPDATE
              version = LAST_INSERT_ID(version + 1),
              updated_at = NOW(3)
            """)) {
          statement.setString(1, name);
          statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT LAST_INSERT_ID()");
            var resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong(1) : null;
        }
      });
      return version == null ? 0 : version;
    }

    @Override
    public String oldestBacklogAgeSecondsExpression(String timestampColumn) {
      if (timestampColumn == null || !COLUMN.matcher(timestampColumn).matches()) {
        throw new IllegalArgumentException("Unsafe timestamp column: " + timestampColumn);
      }
      return "COALESCE(TIMESTAMPDIFF(SECOND, MIN(" + timestampColumn + "), NOW(3)), 0)";
    }
  }

  private static final class MySqlJsonPersistenceDialect implements JsonPersistenceDialect {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.]+");
    private static final Pattern PATH_PART = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public Object jdbcValue(String json) {
      return json;
    }

    @Override
    public void bind(PreparedStatement statement, int index, String json) throws SQLException {
      statement.setString(index, json);
    }

    @Override
    public String extractText(String column, String... path) {
      return "JSON_UNQUOTE(JSON_EXTRACT(" + column(column) + ", '" + path(path) + "'))";
    }

    @Override
    public String setBoolean(String column, boolean value, String... path) {
      return "JSON_SET(" + column(column) + ", '" + path(path) + "', " + value + ")";
    }

    private static String column(String value) {
      if (value == null || !IDENTIFIER.matcher(value).matches()) {
        throw new IllegalArgumentException("Unsafe JSON column: " + value);
      }
      return value;
    }

    private static String path(String... parts) {
      if (parts == null || parts.length == 0) {
        throw new IllegalArgumentException("JSON path is required");
      }
      StringBuilder path = new StringBuilder("$");
      for (String part : parts) {
        if (part == null || !PATH_PART.matcher(part).matches()) {
          throw new IllegalArgumentException("Unsafe JSON path part: " + part);
        }
        path.append('.').append(part);
      }
      return path.toString();
    }
  }

  private static final class MySqlSearchPersistenceDialect implements SearchPersistenceDialect {
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public String componentSearchPredicate(String searchAlias) {
      if (searchAlias == null || !ALIAS.matcher(searchAlias).matches()) {
        throw new IllegalArgumentException("Unsafe component search alias: " + searchAlias);
      }
      return "MATCH(" + searchAlias + ".namespace, " + searchAlias + ".name, "
          + searchAlias + ".version, " + searchAlias + ".keywords) "
          + "AGAINST (? IN BOOLEAN MODE)";
    }

    @Override
    public String prepareComponentQuery(String keyword) {
      if (keyword == null || keyword.isBlank()) {
        return "";
      }
      List<String> terms = new ArrayList<>();
      StringBuilder token = new StringBuilder();
      for (int index = 0; index < keyword.length(); index++) {
        char value = keyword.charAt(index);
        if (Character.isLetterOrDigit(value)) {
          token.append(Character.toLowerCase(value));
        } else {
          addTerm(terms, token);
        }
      }
      addTerm(terms, token);
      return String.join(" ", terms);
    }

    private static void addTerm(List<String> terms, StringBuilder token) {
      if (!token.isEmpty()) {
        terms.add("+" + token + "*");
        token.setLength(0);
      }
    }
  }

  private static final class MySqlSecurityPersistenceDialect
      implements SecurityPersistenceDialect {
    private final JsonPersistenceDialect json;

    private MySqlSecurityPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public void insertPrivilegeIfAbsent(JdbcOperations jdbc, PrivilegeInsert privilege) {
      jdbc.update("""
          INSERT IGNORE INTO security_privilege
            (privilege_id, name, description, type, read_only, properties_json)
          VALUES (?, ?, ?, ?, ?, ?)
          """,
          privilege.privilegeId(),
          privilege.name(),
          privilege.description(),
          privilege.type(),
          privilege.readOnly(),
          json.jdbcValue(privilege.propertiesJson()));
    }

    @Override
    public void assignRoleIfAbsent(JdbcOperations jdbc, long userId, String roleId) {
      jdbc.update("""
          INSERT IGNORE INTO security_user_role (user_id, role_id)
          VALUES (?, ?)
          """, userId, roleId);
    }

    @Override
    public void grantPrivilegeIfAbsent(JdbcOperations jdbc, String roleId, String privilegeId) {
      jdbc.update("""
          INSERT IGNORE INTO security_role_privilege (role_id, privilege_id)
          VALUES (?, ?)
          """, roleId, privilegeId);
    }

    @Override
    public void inheritRoleIfAbsent(JdbcOperations jdbc, String roleId, String childRoleId) {
      jdbc.update("""
          INSERT IGNORE INTO security_role_inheritance (role_id, child_role_id)
          VALUES (?, ?)
          """, roleId, childRoleId);
    }
  }
}
