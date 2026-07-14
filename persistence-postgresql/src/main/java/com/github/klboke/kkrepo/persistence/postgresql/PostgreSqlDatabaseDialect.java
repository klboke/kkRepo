package com.github.klboke.kkrepo.persistence.postgresql;

import com.github.klboke.kkrepo.persistence.jdbc.spi.ComponentPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.CoordinationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.DatabaseType;
import com.github.klboke.kkrepo.persistence.jdbc.spi.JsonPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.MigrationPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SearchPersistenceDialect;
import com.github.klboke.kkrepo.persistence.jdbc.spi.SecurityPersistenceDialect;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

/** PostgreSQL 12+ implementation of the shared persistence dialect contracts. */
public final class PostgreSqlDatabaseDialect implements DatabaseDialect {
  private final PostgreSqlJsonPersistenceDialect json = new PostgreSqlJsonPersistenceDialect();
  private final ComponentPersistenceDialect components =
      new PostgreSqlComponentPersistenceDialect(json);
  private final CoordinationPersistenceDialect coordination =
      new PostgreSqlCoordinationPersistenceDialect();
  private final SearchPersistenceDialect search = new PostgreSqlSearchPersistenceDialect();
  private final SecurityPersistenceDialect security =
      new PostgreSqlSecurityPersistenceDialect(json);
  private final MigrationPersistenceDialect migrations =
      new PostgreSqlMigrationPersistenceDialect(json);

  @Override
  public DatabaseType type() {
    return DatabaseType.POSTGRESQL;
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

  @Override
  public MigrationPersistenceDialect migrations() {
    return migrations;
  }

  private static final class PostgreSqlComponentPersistenceDialect
      implements ComponentPersistenceDialect {
    private static final String UPSERT_RETURNING_ID_SQL = """
        INSERT INTO component
          (repository_id, format, namespace, name, version, kind, coordinate_hash,
           attributes_json, last_updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (repository_id, coordinate_hash)
        DO UPDATE SET last_updated_at = EXCLUDED.last_updated_at
        RETURNING id
        """;
    private static final String UPSERT_SEARCH_DOCUMENT_SQL = """
        INSERT INTO component_search
          (component_id, repository_id, format, namespace, name, version, keywords, refreshed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        ON CONFLICT (component_id) DO UPDATE SET
          repository_id = EXCLUDED.repository_id,
          format = EXCLUDED.format,
          namespace = EXCLUDED.namespace,
          name = EXCLUDED.name,
          version = EXCLUDED.version,
          keywords = EXCLUDED.keywords,
          refreshed_at = CURRENT_TIMESTAMP
        """;

    private final JsonPersistenceDialect json;

    private PostgreSqlComponentPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public long upsertAndReturnId(JdbcOperations jdbc, ComponentUpsert command) {
      Long id = jdbc.execute((ConnectionCallback<Long>) connection -> {
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
          try (var resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : null;
          }
        }
      });
      if (id == null || id <= 0) {
        throw new IllegalStateException("Component upsert did not return an id");
      }
      return id;
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

  private static final class PostgreSqlCoordinationPersistenceDialect
      implements CoordinationPersistenceDialect {
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z0-9_.]+");

    @Override
    public long bumpCacheVersion(JdbcOperations jdbc, String name) {
      Long version = jdbc.queryForObject("""
          INSERT INTO cache_version (name, version, updated_at)
          VALUES (?, 1, CURRENT_TIMESTAMP)
          ON CONFLICT (name) DO UPDATE SET
            version = cache_version.version + 1,
            updated_at = EXCLUDED.updated_at
          RETURNING version
          """, Long.class, name);
      return version == null ? 0 : version;
    }

    @Override
    public String oldestBacklogAgeSecondsExpression(String timestampColumn) {
      if (timestampColumn == null || !COLUMN.matcher(timestampColumn).matches()) {
        throw new IllegalArgumentException("Unsafe timestamp column: " + timestampColumn);
      }
      return "COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN("
          + timestampColumn + "))), 0)";
    }
  }

  private static final class PostgreSqlJsonPersistenceDialect
      implements JsonPersistenceDialect {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.]+");
    private static final Pattern PATH_PART = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public Object jdbcValue(String json) {
      try {
        PGobject value = new PGobject();
        value.setType("jsonb");
        value.setValue(json);
        return value;
      } catch (SQLException e) {
        throw new IllegalArgumentException("Invalid JSONB value", e);
      }
    }

    @Override
    public void bind(PreparedStatement statement, int index, String json) throws SQLException {
      statement.setObject(index, jdbcValue(json));
    }

    @Override
    public String extractText(String column, String... path) {
      return column(column) + " #>> '" + path(path) + "'";
    }

    @Override
    public String setBoolean(String column, boolean value, String... path) {
      return "jsonb_set(" + column(column) + ", '" + path(path) + "', '"
          + value + "'::jsonb, true)";
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
      List<String> validated = new ArrayList<>(parts.length);
      for (String part : parts) {
        if (part == null || !PATH_PART.matcher(part).matches()) {
          throw new IllegalArgumentException("Unsafe JSON path part: " + part);
        }
        validated.add(part);
      }
      return "{" + String.join(",", validated) + "}";
    }
  }

  private static final class PostgreSqlSearchPersistenceDialect
      implements SearchPersistenceDialect {
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public String componentSearchPredicate(String searchAlias) {
      if (searchAlias == null || !ALIAS.matcher(searchAlias).matches()) {
        throw new IllegalArgumentException("Unsafe component search alias: " + searchAlias);
      }
      return searchAlias + ".search_vector @@ to_tsquery('simple', ?)";
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
      return String.join(" & ", terms);
    }

    private static void addTerm(List<String> terms, StringBuilder token) {
      if (!token.isEmpty()) {
        terms.add(token + ":*");
        token.setLength(0);
      }
    }
  }

  private static final class PostgreSqlSecurityPersistenceDialect
      implements SecurityPersistenceDialect {
    private final JsonPersistenceDialect json;

    private PostgreSqlSecurityPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public void insertPrivilegeIfAbsent(JdbcOperations jdbc, PrivilegeInsert privilege) {
      jdbc.update("""
          INSERT INTO security_privilege
            (privilege_id, name, description, type, read_only, properties_json)
          VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT (privilege_id) DO NOTHING
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
          INSERT INTO security_user_role (user_id, role_id)
          VALUES (?, ?)
          ON CONFLICT (user_id, role_id) DO NOTHING
          """, userId, roleId);
    }

    @Override
    public void grantPrivilegeIfAbsent(
        JdbcOperations jdbc,
        String roleId,
        String privilegeId) {
      jdbc.update("""
          INSERT INTO security_role_privilege (role_id, privilege_id)
          VALUES (?, ?)
          ON CONFLICT (role_id, privilege_id) DO NOTHING
          """, roleId, privilegeId);
    }

    @Override
    public void inheritRoleIfAbsent(
        JdbcOperations jdbc,
        String roleId,
        String childRoleId) {
      jdbc.update("""
          INSERT INTO security_role_inheritance (role_id, child_role_id)
          VALUES (?, ?)
          ON CONFLICT (role_id, child_role_id) DO NOTHING
          """, roleId, childRoleId);
    }
  }

  private static final class PostgreSqlMigrationPersistenceDialect
      implements MigrationPersistenceDialect {
    private static final String UPSERT_DISCOVERED_ASSET_SQL = """
        INSERT INTO repository_data_migration_asset
          (repository_job_id, source_asset_id, source_component_id, source_path, source_path_hash,
           format, namespace, name, version, asset_kind, content_type, size, source_blob_ref,
           source_last_updated_at, source_last_downloaded_at, source_blob_created_at,
           source_blob_updated_at, source_created_by, source_created_by_ip, status,
           migrated_at, target_component_id, target_asset_id, target_asset_blob_id, metadata_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (repository_job_id, source_path_hash) DO UPDATE SET
          source_asset_id = EXCLUDED.source_asset_id,
          source_component_id = EXCLUDED.source_component_id,
          format = EXCLUDED.format,
          namespace = EXCLUDED.namespace,
          name = EXCLUDED.name,
          version = EXCLUDED.version,
          asset_kind = EXCLUDED.asset_kind,
          content_type = EXCLUDED.content_type,
          size = EXCLUDED.size,
          source_blob_ref = EXCLUDED.source_blob_ref,
          source_last_updated_at = EXCLUDED.source_last_updated_at,
          source_last_downloaded_at = EXCLUDED.source_last_downloaded_at,
          source_blob_created_at = EXCLUDED.source_blob_created_at,
          source_blob_updated_at = EXCLUDED.source_blob_updated_at,
          source_created_by = EXCLUDED.source_created_by,
          source_created_by_ip = EXCLUDED.source_created_by_ip,
          status = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              THEN repository_data_migration_asset.status
            ELSE EXCLUDED.status END,
          attempts = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              OR EXCLUDED.status = 'migrated'
              THEN repository_data_migration_asset.attempts
            ELSE 0 END,
          claimed_at = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              OR EXCLUDED.status = 'migrated'
              THEN repository_data_migration_asset.claimed_at
            ELSE NULL END,
          migrated_at = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              THEN repository_data_migration_asset.migrated_at
            ELSE EXCLUDED.migrated_at END,
          target_component_id = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              AND repository_data_migration_asset.target_component_id IS NOT NULL
              THEN repository_data_migration_asset.target_component_id
            ELSE EXCLUDED.target_component_id END,
          target_asset_id = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              AND repository_data_migration_asset.target_asset_id IS NOT NULL
              THEN repository_data_migration_asset.target_asset_id
            ELSE EXCLUDED.target_asset_id END,
          target_asset_blob_id = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              AND repository_data_migration_asset.target_asset_blob_id IS NOT NULL
              THEN repository_data_migration_asset.target_asset_blob_id
            ELSE EXCLUDED.target_asset_blob_id END,
          last_error = CASE
            WHEN repository_data_migration_asset.status = 'migrated'
              THEN repository_data_migration_asset.last_error
            ELSE NULL END,
          metadata_json = EXCLUDED.metadata_json
        """;

    private final JsonPersistenceDialect json;

    private PostgreSqlMigrationPersistenceDialect(JsonPersistenceDialect json) {
      this.json = json;
    }

    @Override
    public void upsertDiscoveredAssets(JdbcOperations jdbc, List<DiscoveredAsset> assets) {
      jdbc.batchUpdate(UPSERT_DISCOVERED_ASSET_SQL, assets, Math.min(assets.size(), 500),
          (statement, asset) -> bind(statement, asset));
    }

    private void bind(PreparedStatement statement, DiscoveredAsset asset) throws SQLException {
      statement.setLong(1, asset.repositoryJobId());
      statement.setString(2, asset.sourceAssetId());
      statement.setString(3, asset.sourceComponentId());
      statement.setString(4, asset.sourcePath());
      statement.setBytes(5, asset.sourcePathHash());
      statement.setString(6, asset.format());
      statement.setString(7, asset.namespace());
      statement.setString(8, asset.name());
      statement.setString(9, asset.version());
      statement.setString(10, asset.assetKind());
      statement.setString(11, asset.contentType());
      statement.setObject(12, asset.size(), Types.BIGINT);
      statement.setString(13, asset.sourceBlobRef());
      statement.setTimestamp(14, asset.sourceLastUpdatedAt());
      statement.setTimestamp(15, asset.sourceLastDownloadedAt());
      statement.setTimestamp(16, asset.sourceBlobCreatedAt());
      statement.setTimestamp(17, asset.sourceBlobUpdatedAt());
      statement.setString(18, asset.sourceCreatedBy());
      statement.setString(19, asset.sourceCreatedByIp());
      statement.setString(20, asset.status());
      statement.setTimestamp(21, asset.migratedAt());
      statement.setObject(22, asset.targetComponentId(), Types.BIGINT);
      statement.setObject(23, asset.targetAssetId(), Types.BIGINT);
      statement.setObject(24, asset.targetAssetBlobId(), Types.BIGINT);
      json.bind(statement, 25, asset.metadataJson());
    }
  }
}
